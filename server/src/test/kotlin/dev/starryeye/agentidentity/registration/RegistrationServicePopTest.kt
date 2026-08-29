package dev.starryeye.agentidentity.registration

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.starryeye.agentidentity.attestation.AttestationResult
import dev.starryeye.agentidentity.attestation.AttestationVerifier
import dev.starryeye.agentidentity.identity.AgentIdentityRepository
import dev.starryeye.agentidentity.policy.PolicyProperties
import dev.starryeye.agentidentity.policy.RegistrationPolicy
import dev.starryeye.agentidentity.policy.RejectionReason
import dev.starryeye.agentidentity.proof.JwsProofVerifier
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * `RegistrationService.register` 가 등록 PoP 를 실제로 attested 키에 묶는지 시험한다.
 *
 * 체인은 "TEE 가 이 키를 이 challenge 와 함께 만들었다"만 증명하고, PoP 는 "그 키의
 * 개인키를 지금 이 순간 쥐고 있다"를 증명한다 — 이 둘이 다른 키를 가리키면 거절해야 한다.
 *
 * 픽스처 체인(`AttestationVerifierTest` 가 쓰는 것)의 개인키는 기기 TEE 안에만 있어 여기서
 * 서명할 수 없다. 그래서 `RegistrationServiceThumbprintTest` 와 같은 이음매를 쓴다 —
 * `AttestationResult.Verified` 는 평범한 데이터 클래스이므로 로컬에서 만든 키페어로 직접
 * 구성하고, `AttestationVerifier`(최종 클래스)는 Mockito 로 대체한다. Mockito 5 의 기본
 * mock maker(inline)가 최종 클래스도 그대로 mock 으로 만들 수 있다는 것은 별도로 확인했다.
 */
class RegistrationServicePopTest {

  companion object {
    private val NOW: Instant = Instant.parse("2026-08-28T12:00:00Z")
    private const val URL = "https://example.test/agent/registration"

    private fun signingKey(): ECKey =
        ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).generate()

    private fun pop(
        signingKey: ECKey,
        typ: String,
        url: String,
        iat: Instant,
        challengeClaim: String?,
    ): String {
      val claims =
          JWTClaimsSet.Builder()
              .claim("htm", "POST")
              .claim("htu", url)
              .jwtID(UUID.randomUUID().toString())
              .issueTime(Date.from(iat))
      if (challengeClaim != null) {
        claims.claim("challenge", challengeClaim)
      }
      val jwt =
          SignedJWT(
              JWSHeader.Builder(JWSAlgorithm.ES256)
                  .type(JOSEObjectType(typ))
                  .jwk(signingKey.toPublicJWK())
                  .build(),
              claims.build())
      jwt.sign(ECDSASigner(signingKey))
      return jwt.serialize()
    }

    private fun defaultProperties(): PolicyProperties =
        PolicyProperties().apply {
          requireSecurityLevel = "TRUSTED_ENVIRONMENT"
          requireVerifiedBoot = true
          requireDeviceLocked = true
          allowedPackages = emptyList()
          requireDeviceBinding = false
          requirePlayIntegrity = false
        }

    private fun verifiedFor(attestedKey: ECKey, challenge: ByteArray): AttestationResult.Verified =
        AttestationResult.Verified(
            publicKey = attestedKey.toECPublicKey(),
            challenge = challenge,
            securityLevel = "TRUSTED_ENVIRONMENT",
            verifiedBootState = "VERIFIED",
            deviceLocked = true,
            packageName = "dev.starryeye.ondeviceagent",
            signingDigests = emptyList())

    /**
     * Mockito 의 `any()` 를 코틀린 non-null 매개변수에 직접 넘기면
     * `NullPointerException: any(...) must not be null` 로 죽는다 — `any()` 는 자바
     * 제네릭 함수라 코틀린이 호출 지점에서 반환값에 null-아님 단언을 끼워 넣기 때문이다.
     * `mockito-kotlin` 이 쓰는 것과 같은 우회를 새 의존성 없이 그대로 적용한다: `any()` 호출은
     * matcher 등록이라는 부수효과만 쓰고, 실제 반환값은 별도의 순수 코틀린 제네릭 함수
     * ([uninitializedKt])에서 만든다 — 그 함수는 자바에서 온 값이 아니므로 코틀린이 같은
     * 단언을 끼워 넣지 않는다.
     */
    private fun <T> anyKt(): T {
      Mockito.any<T>()
      return uninitializedKt()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> uninitializedKt(): T = null as T
  }

  /** 검증·정책·저장소를 갖춘 `RegistrationService` 한 벌. 테스트마다 새로 만든다. */
  private class Fixture {
    val properties = defaultProperties()
    val clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    val challenges = ChallengeStore(properties, clock)
    val attestationVerifier: AttestationVerifier = Mockito.mock(AttestationVerifier::class.java)
    val repository: AgentIdentityRepository = Mockito.mock(AgentIdentityRepository::class.java)
    val service =
        RegistrationService(
            challenges,
            attestationVerifier,
            RegistrationPolicy(properties),
            JwsProofVerifier(clock, Duration.ofSeconds(60)),
            repository,
            properties,
            clock)

    /**
     * challenge 를 발급하고, [attestedKey] 가 그 challenge 로 attestation 을 통과한 것처럼
     * 체인 검증을 스텁한다. `challenges.issue()` 가 돌려준 `value` 참조를 그대로 스텁에
     * 쓰므로, `register()` 안에서 `challenges.consume(...)` 이 돌려주는 것과 같은
     * `ByteArray` 참조가 매칭된다 (`ByteArray` 는 `equals` 를 오버라이드하지 않아 참조가
     * 같아야 매칭된다).
     */
    fun issueAndStubChain(
        attestedKey: ECKey,
        chain: List<X509Certificate> = emptyList(),
    ): ChallengeStore.Challenge {
      val issued = challenges.issue()
      val verified = verifiedFor(attestedKey, issued.value)
      Mockito.`when`(attestationVerifier.verify(chain, issued.value)).thenReturn(verified)
      val thumbprint = RegistrationService.thumbprintOf(verified)
      Mockito.`when`(repository.findByJwkThumbprint(thumbprint)).thenReturn(null)
      Mockito.`when`(repository.save(anyKt())).thenAnswer { it.arguments[0] }
      return issued
    }
  }

  @Test
  fun `attested_키로_서명한_유효한_PoP_는_등록을_수용한다`() {
    val fixture = Fixture()
    val key = signingKey()
    val issued = fixture.issueAndStubChain(key)
    val validPop = pop(key, "agent-reg-pop+jwt", URL, NOW, ChallengeStore.encode(issued.value))

    val outcome = fixture.service.register(issued.registrationId, emptyList(), validPop, URL, null, null)

    assertThat(outcome.isAccepted).isTrue()
    assertThat(outcome.identity).isNotNull()
  }

  @Test
  fun `PoP_의_typ_이_dpop_jwt_이면_거절한다`() {
    val fixture = Fixture()
    val key = signingKey()
    val issued = fixture.issueAndStubChain(key)
    // 런타임 DPoP 자리(typ)로 만든 proof 를 등록에 들이민다 — 두 자리가 분리돼 있어야 한다.
    val wrongTypPop = pop(key, "dpop+jwt", URL, NOW, ChallengeStore.encode(issued.value))

    val outcome = fixture.service.register(issued.registrationId, emptyList(), wrongTypPop, URL, null, null)

    assertThat(outcome.isAccepted).isFalse()
    assertThat(outcome.reason).isEqualTo(RejectionReason.POP_INVALID)
  }

  @Test
  fun `다른_키로_서명한_PoP_는_거절한다`() {
    val fixture = Fixture()
    val attestedKey = signingKey()
    val otherKey = signingKey()
    val issued = fixture.issueAndStubChain(attestedKey)
    // 체인은 attestedKey 를 증명하지만, proof 는 전혀 다른 키로 서명됐다 — 체인을 훔쳤어도
    // 그 개인키가 없으면 통과하면 안 된다.
    val popFromOtherKey = pop(otherKey, "agent-reg-pop+jwt", URL, NOW, ChallengeStore.encode(issued.value))

    val outcome = fixture.service.register(issued.registrationId, emptyList(), popFromOtherKey, URL, null, null)

    assertThat(outcome.isAccepted).isFalse()
    assertThat(outcome.reason).isEqualTo(RejectionReason.POP_INVALID)
  }

  @Test
  fun `PoP_가_없으면_거절한다`() {
    val fixture = Fixture()
    val key = signingKey()
    val issued = fixture.issueAndStubChain(key)

    val outcome = fixture.service.register(issued.registrationId, emptyList(), null, URL, null, null)

    assertThat(outcome.isAccepted).isFalse()
    assertThat(outcome.reason).isEqualTo(RejectionReason.POP_INVALID)
  }

  @Test
  fun `PoP_가_JWS_형식이_아니면_거절한다`() {
    val fixture = Fixture()
    val key = signingKey()
    val issued = fixture.issueAndStubChain(key)

    val outcome = fixture.service.register(issued.registrationId, emptyList(), "not-a-jws", URL, null, null)

    assertThat(outcome.isAccepted).isFalse()
    assertThat(outcome.reason).isEqualTo(RejectionReason.POP_INVALID)
  }

  @Test
  fun `PoP_의_challenge_클레임이_이_거래의_challenge_와_다르면_거절한다`() {
    val fixture = Fixture()
    val key = signingKey()
    val issued = fixture.issueAndStubChain(key)
    // 서명도 typ 도 htm-htu 도 다 맞지만, challenge 클레임만 이 거래의 것이 아니다 — 다른
    // 거래용으로 만든 PoP 를 여기 들이미는 상황을 흉내낸다.
    val wrongChallengePop =
        pop(key, "agent-reg-pop+jwt", URL, NOW, ChallengeStore.encode(ByteArray(32) { 9 }))

    val outcome =
        fixture.service.register(issued.registrationId, emptyList(), wrongChallengePop, URL, null, null)

    assertThat(outcome.isAccepted).isFalse()
    assertThat(outcome.reason).isEqualTo(RejectionReason.POP_INVALID)
  }
}
