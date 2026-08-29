package dev.starryeye.ondeviceagent.identity

/** 에이전트 신원의 현재 상태. 화면에 시스템 줄로 표시된다. */
sealed interface AgentIdentityState {
  data object Registering : AgentIdentityState
  /** [reused] 가 true 면 기존 키의 자격증명을 갱신한 것이고, false 면 이번에 새로 등록한 것이다. */
  data class Registered(val agentId: String, val reused: Boolean) : AgentIdentityState
  /** [reason] 은 서버의 사유 코드. 재시도가 무의미한 사유는 반복하지 않는다. */
  data class Failed(val reason: String) : AgentIdentityState
}
