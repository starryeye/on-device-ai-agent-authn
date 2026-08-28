package dev.starryeye.agentidentity.policy

/**
 * 거절 사유. 클라이언트와 로그가 함께 쓴다.
 *
 * "거절됨" 하나로 뭉뚱그리면 정책을 바꿔가며 관찰하는 이 연구가 성립하지 않는다.
 */
enum class RejectionReason {
  CHALLENGE_INVALID,
  CHAIN_UNTRUSTED,
  CHAIN_REVOKED,
  CHAIN_EXPIRED,
  POLICY_SECURITY_LEVEL,
  POLICY_VERIFIED_BOOT,
  POLICY_DEVICE_LOCKED,
  POLICY_APPLICATION,
  POLICY_DEVICE_BINDING,
  POLICY_INTEGRITY,
  POP_INVALID,
  DPOP_INVALID,
  CREDENTIAL_EXPIRED,
  REATTESTATION_REQUIRED,
}
