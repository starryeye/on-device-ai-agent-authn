package dev.starryeye.agentidentity.policy

import dev.starryeye.agentidentity.attestation.AttestationResult
import org.springframework.stereotype.Component

/**
 * 증명이 정책을 통과하는지 판단한다.
 *
 * 순서에 의미가 있다. 앱 신원(`packageName`)은 소프트웨어 강제 값이라 그 자체로는 사칭을 막지
 * 못한다 — 부팅이 검증된 기기에서만 플랫폼 코드를 신뢰할 수 있으므로, 부팅 검사를 먼저 한다.
 * `requireVerifiedBoot` 를 끄면 앱 신원 검사도 함께 무의미해진다는 뜻이다.
 */
@Component
class RegistrationPolicy(private val properties: PolicyProperties) {

  fun evaluate(
      attestation: AttestationResult.Verified,
      deviceBinding: String?,
      integrityToken: String?,
  ): RejectionReason? {

    if (!securityLevelSatisfied(attestation.securityLevel)) {
      return RejectionReason.POLICY_SECURITY_LEVEL
    }
    if (properties.requireVerifiedBoot && attestation.verifiedBootState != "VERIFIED") {
      return RejectionReason.POLICY_VERIFIED_BOOT
    }
    if (properties.requireDeviceLocked && !attestation.deviceLocked) {
      return RejectionReason.POLICY_DEVICE_LOCKED
    }
    if (properties.allowedPackages.isNotEmpty() &&
        attestation.packageName !in properties.allowedPackages) {
      return RejectionReason.POLICY_APPLICATION
    }
    if (properties.requireDeviceBinding && deviceBinding == null) {
      return RejectionReason.POLICY_DEVICE_BINDING
    }
    if (properties.requirePlayIntegrity && integrityToken == null) {
      return RejectionReason.POLICY_INTEGRITY
    }
    return null
  }

  /** StrongBox 를 요구하면 TEE 는 통과하지 못한다. 반대는 통과한다. */
  private fun securityLevelSatisfied(actual: String): Boolean {
    val required = properties.requireSecurityLevel
    if (required == "STRONGBOX") {
      return actual == "STRONG_BOX" || actual == "STRONGBOX"
    }
    return actual != "SOFTWARE"
  }
}
