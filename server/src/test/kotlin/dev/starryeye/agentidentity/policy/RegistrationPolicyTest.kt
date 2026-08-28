package dev.starryeye.agentidentity.policy

import dev.starryeye.agentidentity.attestation.AttestationResult
import java.security.KeyPairGenerator
import java.security.PublicKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RegistrationPolicyTest {

  companion object {
    /** publicKey 는 정책 판단에 쓰이지 않는다 — 자리만 채우는 더미 키. */
    private val DUMMY_PUBLIC_KEY: PublicKey =
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public

    private fun attestation(
        securityLevel: String,
        bootState: String,
        locked: Boolean,
        packageName: String,
    ): AttestationResult.Verified =
        AttestationResult.Verified(
            publicKey = DUMMY_PUBLIC_KEY,
            challenge = ByteArray(32),
            securityLevel = securityLevel,
            verifiedBootState = bootState,
            deviceLocked = locked,
            packageName = packageName,
            signingDigests = emptyList())

    private fun defaults(): PolicyProperties =
        PolicyProperties().apply {
          requireSecurityLevel = "TRUSTED_ENVIRONMENT"
          requireVerifiedBoot = true
          requireDeviceLocked = true
          allowedPackages = listOf("dev.starryeye.ondeviceagent")
          requireDeviceBinding = false
          requirePlayIntegrity = false
        }
  }

  @Test
  fun `정상_증명은_수용한다`() {
    val reason =
        RegistrationPolicy(defaults())
            .evaluate(
                attestation("TRUSTED_ENVIRONMENT", "VERIFIED", true, "dev.starryeye.ondeviceagent"),
                null,
                null)

    assertThat(reason).isNull()
  }

  @Test
  fun `StrongBox_를_요구하면_TEE_기기는_거절한다`() {
    val strict = defaults().apply { requireSecurityLevel = "STRONGBOX" }

    val reason =
        RegistrationPolicy(strict)
            .evaluate(
                attestation("TRUSTED_ENVIRONMENT", "VERIFIED", true, "dev.starryeye.ondeviceagent"),
                null,
                null)

    assertThat(reason).isEqualTo(RejectionReason.POLICY_SECURITY_LEVEL)
  }

  @Test
  fun `부팅이_검증되지_않으면_거절한다`() {
    val reason =
        RegistrationPolicy(defaults())
            .evaluate(
                attestation("TRUSTED_ENVIRONMENT", "UNVERIFIED", true, "dev.starryeye.ondeviceagent"),
                null,
                null)

    assertThat(reason).isEqualTo(RejectionReason.POLICY_VERIFIED_BOOT)
  }

  @Test
  fun `다른_앱의_패키지명은_거절한다`() {
    val reason =
        RegistrationPolicy(defaults())
            .evaluate(attestation("TRUSTED_ENVIRONMENT", "VERIFIED", true, "com.evil.clone"), null, null)

    assertThat(reason).isEqualTo(RejectionReason.POLICY_APPLICATION)
  }

  @Test
  fun `기기_증명을_요구하면_소매기기는_거절한다`() {
    val strict = defaults().apply { requireDeviceBinding = true }

    val reason =
        RegistrationPolicy(strict)
            .evaluate(
                attestation("TRUSTED_ENVIRONMENT", "VERIFIED", true, "dev.starryeye.ondeviceagent"),
                null,
                null)

    assertThat(reason).isEqualTo(RejectionReason.POLICY_DEVICE_BINDING)
  }
}
