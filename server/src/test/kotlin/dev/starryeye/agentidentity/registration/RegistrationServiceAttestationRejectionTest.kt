package dev.starryeye.agentidentity.registration

import dev.starryeye.agentidentity.attestation.AttestationResult
import dev.starryeye.agentidentity.attestation.AttestationVerifier
import dev.starryeye.agentidentity.identity.AgentIdentityRepository
import dev.starryeye.agentidentity.policy.PolicyProperties
import dev.starryeye.agentidentity.policy.RegistrationPolicy
import dev.starryeye.agentidentity.policy.RejectionReason
import dev.starryeye.agentidentity.proof.JwsProofVerifier
import java.security.cert.CertPathValidatorException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * `RegistrationService.register` 가 `AttestationVerifier` 의 거절을 사유 코드로 옮기는
 * 지점을 시험한다.
 *
 * 이 매핑 자체는 지금까지 어떤 테스트도 실행하지 않았다 — 다른 모든 `RegistrationService`
 * 픽스처(`RegistrationServicePopTest` 등)는 `attestationVerifier.verify(...)` 를 항상
 * `AttestationResult.Verified` 로만 스텁했기 때문이다. `Rejected` 갈래(그리고 그 안에서
 * `CHALLENGE_INVALID`/`CHAIN_REVOKED`/`CHAIN_EXPIRED`/`CHAIN_VERIFICATION_UNAVAILABLE`/
 * `CHAIN_UNTRUSTED` 로 갈리는 분기)는 이 파일이 처음 다룬다.
 */
class RegistrationServiceAttestationRejectionTest {

  companion object {
    private val NOW: Instant = Instant.parse("2026-08-28T12:00:00Z")
    private const val URL = "https://example.test/agent/registration"

    private fun defaultProperties(): PolicyProperties =
        PolicyProperties().apply {
          requireSecurityLevel = "TRUSTED_ENVIRONMENT"
          requireVerifiedBoot = true
          requireDeviceLocked = true
          allowedPackages = emptyList()
          requireDeviceBinding = false
          requirePlayIntegrity = false
        }
  }

  /** 체인 검증이 [rejected] 를 돌려줄 때 등록이 어떤 [RejectionReason] 으로 거절되는지 확인한다. */
  private fun registerAndReject(rejected: AttestationResult.Rejected): RejectionReason? {
    val properties = defaultProperties()
    val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    val challenges = ChallengeStore(properties, clock)
    val attestationVerifier = Mockito.mock(AttestationVerifier::class.java)
    val repository = Mockito.mock(AgentIdentityRepository::class.java)
    val service =
        RegistrationService(
            challenges,
            attestationVerifier,
            RegistrationPolicy(properties),
            JwsProofVerifier(clock, Duration.ofSeconds(60)),
            repository,
            properties,
            clock)

    val issued = challenges.issue()
    Mockito.`when`(attestationVerifier.verify(emptyList(), issued.value)).thenReturn(rejected)

    val outcome = service.register(issued.registrationId, emptyList(), null, URL, null, null)

    assertThat(outcome.isAccepted).isFalse()
    return outcome.reason
  }

  @Test
  fun `challenge_불일치는_CHALLENGE_INVALID_로_거절한다`() {
    val reason = registerAndReject(AttestationResult.Rejected("ChallengeMismatch"))

    assertThat(reason).isEqualTo(RejectionReason.CHALLENGE_INVALID)
  }

  @Test
  fun `certPathReason_이_REVOKED_이면_CHAIN_REVOKED_로_거절한다`() {
    val reason =
        registerAndReject(
            AttestationResult.Rejected(
                "PathValidationFailure",
                certPathReason = CertPathValidatorException.BasicReason.REVOKED))

    assertThat(reason).isEqualTo(RejectionReason.CHAIN_REVOKED)
  }

  @Test
  fun `certPathReason_이_EXPIRED_이면_CHAIN_EXPIRED_로_거절한다`() {
    val reason =
        registerAndReject(
            AttestationResult.Rejected(
                "PathValidationFailure",
                certPathReason = CertPathValidatorException.BasicReason.EXPIRED))

    assertThat(reason).isEqualTo(RejectionReason.CHAIN_EXPIRED)
  }

  @Test
  fun `infrastructureFailure_는_CHAIN_VERIFICATION_UNAVAILABLE_로_거절한다`() {
    // CHAIN_UNTRUSTED 로 합치면 클라이언트는 "재시도 무의미"로 읽는다 — 실제로는 우리(또는
    // 구글) 인프라가 일시적으로 죽은 것이라 재시도가 유의미하다.
    val reason =
        registerAndReject(
            AttestationResult.Rejected("infrastructure failure: boom", infrastructureFailure = true))

    assertThat(reason).isEqualTo(RejectionReason.CHAIN_VERIFICATION_UNAVAILABLE)
  }

  @Test
  fun `그_밖의_경로_검증_실패는_CHAIN_UNTRUSTED_로_거절한다`() {
    val reason = registerAndReject(AttestationResult.Rejected("PathValidationFailure"))

    assertThat(reason).isEqualTo(RejectionReason.CHAIN_UNTRUSTED)
  }
}
