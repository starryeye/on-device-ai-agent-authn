package dev.starryeye.ondeviceagent

/**
 * 화면이 처한 상황. 입력창은 [Ready]에서만, 그리고 turn이 돌고 있지 않을 때만 열린다
 * ([AgentViewModel.inputEnabled] 참고).
 */
sealed interface AgentUiState {

  /** 아직 모델 파일이 없다. 내려받거나 `adb push` 해야 한다. */
  data object NeedsModel : AgentUiState

  /** 모델을 내려받는 중. [progress]는 0f..1f. */
  data class Downloading(val progress: Float) : AgentUiState

  /** 가중치를 읽어 네이티브 엔진을 여는 중. 수 초 걸린다. */
  data object Loading : AgentUiState

  /** 대화할 수 있다. */
  data object Ready : AgentUiState

  /** 회복할 수 없는 실패. [reason]을 그대로 사용자에게 보여준다. */
  data class Failed(val reason: String) : AgentUiState
}
