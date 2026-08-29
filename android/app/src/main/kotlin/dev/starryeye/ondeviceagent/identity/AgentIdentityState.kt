package dev.starryeye.ondeviceagent.identity

/** 에이전트 신원의 현재 상태. 화면에 시스템 줄로 표시된다. */
sealed interface AgentIdentityState {
  data object Registering : AgentIdentityState
  data class Registered(val agentId: String) : AgentIdentityState
  /** [reason] 은 서버의 사유 코드. 재시도가 무의미한 사유는 반복하지 않는다. */
  data class Failed(val reason: String) : AgentIdentityState
}
