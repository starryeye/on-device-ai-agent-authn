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

  /**
   * 문자열이 아니라 등급(SOFTWARE < TRUSTED_ENVIRONMENT < STRONG_BOX)으로 비교한다.
   * 증명된 등급이 요구 등급 이상이면 통과 — "STRONGBOX 를 요구하면 TEE 는 통과하지 못하고,
   * 그 반대는 통과한다"는 규칙이 특수 케이스가 아니라 이 순서에서 자연히 나온다.
   */
  private fun securityLevelSatisfied(actual: String): Boolean {
    val required = parseSecurityLevel(properties.requireSecurityLevel)
    val attested = parseSecurityLevel(actual)
    return attested.ordinal >= required.ordinal
  }

  /**
   * 설정값(`STRONGBOX`)과 라이브러리가 실제로 내보내는 값(`STRONG_BOX`)의 철자가 다르므로
   * 밑줄과 대소문자를 무시하고 매칭한다.
   *
   * 인식하지 못하는 값은 조용히 기본값으로 넘기지 않고 예외로 실패시킨다 — 이 계층은
   * 정책을 실험 손잡이로 켜고 끄며 관찰하는 용도라서, 오타 하나가 조용히 더 약한 정책을
   * 적용해버리면 그 손잡이로 관측한 모든 결과를 믿을 수 없게 된다. 증명된 값이 알 수 없는
   * 경우도 마찬가지로, 등급을 매길 수 없는 값을 통과시켜서는 안 된다.
   */
  private fun parseSecurityLevel(value: String): SecurityLevel {
    val normalized = value.replace("_", "").uppercase()
    return SecurityLevel.entries.firstOrNull { it.name.replace("_", "") == normalized }
        ?: throw IllegalArgumentException(
            "알 수 없는 보안 레벨 값: '$value' " +
                "(허용값: ${SecurityLevel.entries.joinToString(", ") { it.name }})")
  }

  /** 등급 순서 그 자체가 정책 판단이다 — 선언 순서가 SOFTWARE < TRUSTED_ENVIRONMENT < STRONG_BOX. */
  private enum class SecurityLevel {
    SOFTWARE,
    TRUSTED_ENVIRONMENT,
    STRONG_BOX,
  }
}
