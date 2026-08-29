package dev.starryeye.agentidentity.proof

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JwsProofVerifierTest {

  /** 재생 방지 캐시의 보관 기간을 시험하려면 시각을 직접 밀 수 있어야 한다. */
  private class MutableClock(private var current: Instant) : Clock() {
    override fun instant(): Instant = current

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    fun advanceTo(instant: Instant) {
      current = instant
    }
  }

  companion object {
    private val NOW: Instant = Instant.parse("2026-08-28T12:00:00Z")
    private const val URL = "https://example.test/agent/credential"

    private fun key(): ECKey =
        ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).generate()

    private fun proof(key: ECKey, typ: String, method: String, url: String, iat: Instant): String {
      val jwt =
          SignedJWT(
              JWSHeader.Builder(JWSAlgorithm.ES256)
                  .type(JOSEObjectType(typ))
                  .jwk(key.toPublicJWK())
                  .build(),
              JWTClaimsSet.Builder()
                  .claim("htm", method)
                  .claim("htu", url)
                  .jwtID(UUID.randomUUID().toString())
                  .issueTime(Date.from(iat))
                  .build())
      jwt.sign(ECDSASigner(key))
      return jwt.serialize()
    }

    private fun verifier(): JwsProofVerifier =
        JwsProofVerifier(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60))
  }

  @Test
  fun `올바른_DPoP_proof_는_지문을_돌려준다`() {
    val key = key()
    val jws = proof(key, "dpop+jwt", "POST", URL, NOW)

    assertThat(verifier().verify(jws, ProofType.DPOP, "POST", URL))
        .isEqualTo(key.computeThumbprint().toString())
  }

  @Test
  fun `typ_헤더가_없으면_거절한다`() {
    // 등록 PoP 와 런타임 DPoP 는 typ 하나로 서로의 자리에서 통과하지 못하게 나뉜다(이
    // 프로젝트가 스스로 명시한 핵심 관심사). typ 이 아예 없는 JWS 를 만들어 이 분리가
    // "값이 다르면 거절"이 아니라 "값이 없어도 거절"임을 확인한다.
    val key = key()
    val jwt =
        SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256).jwk(key.toPublicJWK()).build(),
            JWTClaimsSet.Builder()
                .claim("htm", "POST")
                .claim("htu", URL)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(NOW))
                .build())
    jwt.sign(ECDSASigner(key))

    assertThat(verifier().verify(jwt.serialize(), ProofType.DPOP, "POST", URL)).isNull()
  }

  @Test
  fun `등록_PoP_를_DPoP_자리에_내면_거절한다`() {
    val jws = proof(key(), "agent-reg-pop+jwt", "POST", URL, NOW)

    assertThat(verifier().verify(jws, ProofType.DPOP, "POST", URL)).isNull()
  }

  @Test
  fun `DPoP_를_등록_PoP_자리에_내면_거절한다`() {
    val jws = proof(key(), "dpop+jwt", "POST", URL, NOW)

    assertThat(verifier().verify(jws, ProofType.REGISTRATION, "POST", URL)).isNull()
  }

  @Test
  fun `같은_proof_를_두_번_쓰면_거절한다`() {
    val jws = proof(key(), "dpop+jwt", "POST", URL, NOW)
    val verifier = verifier()

    assertThat(verifier.verify(jws, ProofType.DPOP, "POST", URL)).isNotNull()
    assertThat(verifier.verify(jws, ProofType.DPOP, "POST", URL)).isNull()
  }

  @Test
  fun `시계_오차를_벗어난_proof_는_거절한다`() {
    val jws = proof(key(), "dpop+jwt", "POST", URL, NOW.minus(Duration.ofMinutes(10)))

    assertThat(verifier().verify(jws, ProofType.DPOP, "POST", URL)).isNull()
  }

  @Test
  fun `다른_URL_로_만든_proof_는_거절한다`() {
    val jws = proof(key(), "dpop+jwt", "POST", "https://example.test/other", NOW)

    assertThat(verifier().verify(jws, ProofType.DPOP, "POST", URL)).isNull()
  }

  /**
   * 재생 방지 캐시는 `skew` 의 두 배 동안 `jti` 를 기억해야 한다 — 그보다 짧으면, `iat`
   * 검사가 아직 받아줄 시각인데도 캐시에서는 이미 걷혀나가 재생이 통과해 버린다.
   *
   * 경계를 정확히 겨냥한다: `iat` 를 첫 검증 시각(`NOW`)보다 `skew` 만큼 미래로 잡으면,
   * `iat` 검사가 이 proof 를 계속 받아줄 수 있는 가장 늦은 시각은 `iat + skew`, 즉 첫
   * 검증 시각으로부터 `skew * 2` 뒤다. 캐시 보관 기간이 정확히 이 값이어야 그 순간까지
   * 재생이 막힌다.
   */
  @Test
  fun `재생_방지_캐시는_iat_검사가_받아주는_기간_동안_jti_를_기억한다`() {
    val clock = MutableClock(NOW)
    val verifier = JwsProofVerifier(clock, Duration.ofSeconds(60))
    val jws = proof(key(), "dpop+jwt", "POST", URL, NOW.plusSeconds(60))

    // 첫 검증: iat 검사 경계(|NOW - (NOW+60)| = 60 <= skew 60)를 정확히 통과한다.
    assertThat(verifier.verify(jws, ProofType.DPOP, "POST", URL)).isNotNull()

    // skew(60초)를 살짝 넘겨 시계를 민다 — iat 검사는 이 시각에서도 여전히 통과한다
    // (|NOW+61 - (NOW+60)| = 1 <= 60). skew*2(120초)에는 한참 못 미치므로, 캐시가
    // 제대로 구현됐다면 jti 가 아직 기억되고 있어야 한다.
    clock.advanceTo(NOW.plusSeconds(61))

    assertThat(verifier.verify(jws, ProofType.DPOP, "POST", URL)).isNull()
  }

  /**
   * 시간 청소만으로는 `skew*2` 창 안에서 몰아치는 홍수를 막지 못한다 — 상한이 별도로
   * 필요하다. 상한에 닿으면 살아있는 jti 를 밀어내는 대신 새 jti 기억을 거절해야 한다:
   * 밀어내면 그 자리에서 재생 방지가 뚫린다.
   */
  @Test
  fun `재생_캐시가_상한에_닿으면_새_jti_기억을_거절한다`() {
    val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    val verifier = JwsProofVerifier(clock, Duration.ofSeconds(60), maxSeenJti = 2)
    val first = proof(key(), "dpop+jwt", "POST", URL, NOW)
    val second = proof(key(), "dpop+jwt", "POST", URL, NOW)
    val third = proof(key(), "dpop+jwt", "POST", URL, NOW)

    assertThat(verifier.verify(first, ProofType.DPOP, "POST", URL)).isNotNull()
    assertThat(verifier.verify(second, ProofType.DPOP, "POST", URL)).isNotNull()

    // 캐시가 이미 상한(2)이다. 세 번째는 서명도 typ 도 다 유효하지만, 자리가 없어
    // 거절돼야 한다 — first/second 의 jti 를 밀어내고 자리를 만들면 그 둘의 재생
    // 방지가 그 순간 뚫린다.
    assertThat(verifier.verify(third, ProofType.DPOP, "POST", URL)).isNull()
  }
}
