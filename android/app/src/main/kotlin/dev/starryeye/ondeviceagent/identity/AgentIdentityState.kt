package dev.starryeye.ondeviceagent.identity

/** 에이전트 신원의 현재 상태. 화면에 시스템 줄로 표시된다. */
sealed interface AgentIdentityState {
  data object Registering : AgentIdentityState
  /** [origin] 은 이번 등록이 어느 경로로 성립했는지. */
  data class Registered(val agentId: String, val origin: RegistrationOrigin) : AgentIdentityState
  /** [reason] 은 서버의 사유 코드. 재시도가 무의미한 사유는 반복하지 않는다. */
  data class Failed(val reason: String) : AgentIdentityState
}

/**
 * [AgentIdentityState.Registered] 가 어느 경로로 성립했는지. 화면 관찰용이자, "왜 새 신원이
 * 됐는지"를 재현·디버깅할 때 남는 유일한 단서다.
 */
enum class RegistrationOrigin {
  /** 키가 없었다 — 이 기기의 첫 등록. */
  FIRST_RUN,
  /** 기존 키가 있었고, attestation 없이 자격증명만 갱신했다. 재시작해도 같은 신원이 유지된
   * 경우다. */
  REUSED,
  /** 서버가 `REATTESTATION_REQUIRED` 로 갱신을 거절해 새 키로 다시 등록했다. attestation 이
   * 너무 오래돼 더 이상 연장할 수 없다는 판단이었다 — 새 신원이 된다. */
  REATTESTATION_REQUIRED,
  /** 서버가 `AGENT_NOT_FOUND` 로 갱신을 거절해(키는 있지만 서버에 등록된 적이 없다) 새 키로
   * 다시 등록했다. 등록 도중 프로세스가 죽는 등으로 키만 만들어지고 서버에는 남지 않은
   * 경우를 복구한다 — 새 신원이 된다. */
  AGENT_NOT_FOUND,
}
