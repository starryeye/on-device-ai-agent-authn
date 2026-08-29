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
import dev.starryeye.agentidentity.identity.AgentIdentity
import dev.starryeye.agentidentity.identity.AgentIdentityRepository
import dev.starryeye.agentidentity.policy.PolicyProperties
import dev.starryeye.agentidentity.policy.RegistrationPolicy
import dev.starryeye.agentidentity.proof.JwsProofVerifier
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException

/**
 * `RegistrationService.register` 의 멱등 경로("같은 키로 다시 등록하면 같은 agentId")와
 * 유니크 제약 경쟁 복구 경로를 시험한다.
 *
 * 이전까지 이 두 경로는 어느 테스트도 실행하지 않았다 — `RegistrationServicePopTest` 를 포함한
 * 모든 기존 픽스처가 `repository.findByJwkThumbprint(...)` 를 항상 `null` 로 스텁했기
 * 때문이다. 그래서 등록마다 무조건 새 `agentId` 를 만드는 구현이었어도 기존 56개 테스트를
 * 전부 통과했을 것이다 — 설계 문서가 이 성질(§5.2 "신원은 키다")을 신원이 자격증명 수명을
 * 따라가지 않게 지키는 핵심 불변식으로 명시하는데도 그렇다.
 */
class RegistrationServiceIdempotencyTest {

  companion object {
    private val NOW: Instant = Instant.parse("2026-08-28T12:00:00Z")
    private const val URL = "https://example.test/agent/registration"

    private fun signingKey(): ECKey =
        ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).generate()

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

    private fun identityFor(id: String, verified: AttestationResult.Verified, thumbprint: String): AgentIdentity =
        AgentIdentity(
            id,
            thumbprint,
            "galaxy-personal-agent",
            verified.packageName,
            verified.securityLevel,
            verified.verifiedBootState,
            verified.deviceLocked,
            NOW.minus(Duration.ofDays(1)))

    /** 등록 PoP. `RegistrationServicePopTest` 와 같은 형태다 — 여기서는 유효한 PoP 한 종류만 필요하다. */
    private fun pop(signingKey: ECKey, challenge: ByteArray): String {
      val claims =
          JWTClaimsSet.Builder()
              .claim("htm", "POST")
              .claim("htu", URL)
              .jwtID(UUID.randomUUID().toString())
              .issueTime(Date.from(NOW))
              .claim("challenge", ChallengeStore.encode(challenge))
              .build()
      val jwt =
          SignedJWT(
              JWSHeader.Builder(JWSAlgorithm.ES256)
                  .type(JOSEObjectType("agent-reg-pop+jwt"))
                  .jwk(signingKey.toPublicJWK())
                  .build(),
              claims)
      jwt.sign(ECDSASigner(signingKey))
      return jwt.serialize()
    }

    /** `RegistrationServicePopTest.anyKt()` 와 같은 우회. Mockito 의 `any()` 를 코틀린
     * non-null 매개변수에 직접 넘기면 죽는다. */
    private fun <T> anyKt(): T {
      Mockito.any<T>()
      return uninitializedKt()
    }

    @Suppress("UNCHECKED_CAST") private fun <T> uninitializedKt(): T = null as T
  }

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

    fun issueAndStubChain(attestedKey: ECKey): ChallengeStore.Challenge {
      val issued = challenges.issue()
      val verified = verifiedFor(attestedKey, issued.value)
      Mockito.`when`(attestationVerifier.verify(emptyList(), issued.value)).thenReturn(verified)
      return issued
    }
  }

  @Test
  fun `이미_등록된_키로_다시_등록하면_같은_agentId_를_돌려주고_새_신원을_만들지_않는다`() {
    val fixture = Fixture()
    val key = signingKey()
    val issued = fixture.issueAndStubChain(key)
    val verified = verifiedFor(key, issued.value)
    val thumbprint = RegistrationService.thumbprintOf(verified)
    val existing = identityFor("urn:samsung:agent:galaxy-personal-agent:already-registered", verified, thumbprint)

    Mockito.`when`(fixture.repository.findByJwkThumbprint(thumbprint)).thenReturn(existing)
    Mockito.`when`(fixture.repository.save(anyKt())).thenAnswer { it.arguments[0] }

    val outcome =
        fixture.service.register(
            issued.registrationId, emptyList(), pop(key, issued.value), URL, null, null)

    assertThat(outcome.isAccepted).isTrue()
    assertThat(outcome.identity?.id).isEqualTo(existing.id)

    // save() 가 정확히 한 번, 기존 신원 그 참조로만 호출됐는지 — 새 AgentIdentity 를 만들어
    // 저장하지 않았다는 뜻이다.
    val captor = ArgumentCaptor.forClass(AgentIdentity::class.java)
    Mockito.verify(fixture.repository).save(captor.capture())
    assertThat(captor.value).isSameAs(existing)
  }

  @Test
  fun `저장_중_유니크_제약_위반이_나면_재조회로_이긴_쪽_신원을_결과로_돌려준다`() {
    val fixture = Fixture()
    val key = signingKey()
    val issued = fixture.issueAndStubChain(key)
    val verified = verifiedFor(key, issued.value)
    val thumbprint = RegistrationService.thumbprintOf(verified)
    val winner = identityFor("urn:samsung:agent:galaxy-personal-agent:winner", verified, thumbprint)

    // 첫 조회는 아무도 없다(null) — 그래서 새 신원을 만들어 저장을 시도한다. 그 저장이
    // 유니크 제약 위반으로 실패하면(동시에 들어온 다른 요청이 먼저 이겼다), 재조회는
    // 그 이긴 쪽을 돌려준다.
    Mockito.`when`(fixture.repository.findByJwkThumbprint(thumbprint)).thenReturn(null, winner)
    Mockito.`when`(fixture.repository.save(anyKt()))
        .thenThrow(DataIntegrityViolationException("unique constraint violated"))
        .thenAnswer { it.arguments[0] }

    val outcome =
        fixture.service.register(
            issued.registrationId, emptyList(), pop(key, issued.value), URL, null, null)

    assertThat(outcome.isAccepted).isTrue()
    assertThat(outcome.identity?.id).isEqualTo(winner.id)
    Mockito.verify(fixture.repository, Mockito.times(2)).findByJwkThumbprint(thumbprint)
  }
}
