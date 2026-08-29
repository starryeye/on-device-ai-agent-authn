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
  /**
   * DPoP proof 자체는 유효하지만, 그 서명자 지문으로 등록된 신원이 없다. `DPOP_INVALID` 와
   * 뭉뚱그리면 클라이언트가 "proof 가 틀렸다"와 "이 키로 등록된 적이 없다"를 구분하지
   * 못한다 — 후자만 재등록해도 안전하다(proof 는 방금 검증된 진짜 키 소유 증명이고,
   * 다만 그 키로 등록된 적이 없을 뿐이다). 전자를 재등록의 신호로 쓰면 위조·재생·만료된
   * proof 로도 새 신원을 얻을 수 있게 된다.
   */
  AGENT_NOT_FOUND,
  /**
   * 신원은 있지만 `ACTIVE` 가 아니다(관리자가 비활성화했다고 가정 — 지금은 이 상태를
   * 만드는 경로가 없다). `AGENT_NOT_FOUND` 와 섞지 않는다 — 섞으면 비활성화된 신원이
   * 새 키로 재등록해서 비활성화를 그냥 우회하게 된다.
   */
  AGENT_INACTIVE,
  CREDENTIAL_EXPIRED,
  REATTESTATION_REQUIRED,
}
