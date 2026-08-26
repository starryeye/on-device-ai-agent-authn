package dev.starryeye.ondeviceagent.ui

/** 말풍선을 그린 주체. */
enum class ChatAuthor {
  USER,
  AGENT,
  /** 앱이 상황을 알리는 줄. 모델과 무관하다. */
  SYSTEM,
}

/** 화면에 그려지는 한 줄. */
data class ChatMessage(val author: ChatAuthor, val text: String)
