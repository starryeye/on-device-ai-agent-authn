package dev.starryeye.ondeviceagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.starryeye.ondeviceagent.AgentUiState

/**
 * 채팅 화면 전체. 이 컴포저블은 에이전트도 ViewModel도 모른다 — 상태를 인자로 받고 콜백을
 * 돌려줄 뿐이다.
 */
@Composable
fun ChatScreen(
  messages: List<ChatMessage>,
  uiState: AgentUiState,
  inputEnabled: Boolean,
  onSend: (String) -> Unit,
  onDownload: () -> Unit,
) {
  val listState = rememberLazyListState()

  // 새 메시지가 붙거나 스트리밍으로 마지막 말풍선이 자라면 바닥을 따라간다.
  LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
  }

  Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
    LazyColumn(
      state = listState,
      modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
      items(messages.size) { index -> MessageBubble(messages[index]) }
    }

    StatusBar(uiState = uiState, onDownload = onDownload)

    InputRow(enabled = inputEnabled, onSend = onSend)
  }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
  when (message.author) {
    ChatAuthor.SYSTEM ->
      Text(
        text = message.text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      )
    else -> {
      val fromUser = message.author == ChatAuthor.USER
      Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart,
      ) {
        Text(
          text = message.text,
          style = MaterialTheme.typography.bodyMedium,
          color =
            if (fromUser) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
          modifier =
            Modifier.background(
                color =
                  if (fromUser) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(16.dp),
              )
              .padding(horizontal = 14.dp, vertical = 10.dp),
        )
      }
    }
  }
}

/** 모델을 아직 못 쓰는 상황에서만 무언가를 보여준다. 준비되면 사라진다. */
@Composable
private fun StatusBar(uiState: AgentUiState, onDownload: () -> Unit) {
  when (uiState) {
    is AgentUiState.NeedsModel ->
      Button(
        onClick = onDownload,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      ) {
        Text("모델 내려받기")
      }
    is AgentUiState.Downloading ->
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
          text = "내려받는 중… ${(uiState.progress * 100).toInt()}%",
          style = MaterialTheme.typography.bodySmall,
        )
        LinearProgressIndicator(
          progress = { uiState.progress },
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
      }
    is AgentUiState.Loading ->
      LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
      )
    is AgentUiState.Failed ->
      Text(
        text = uiState.reason,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      )
    is AgentUiState.Ready -> Unit
  }
}

@Composable
private fun InputRow(enabled: Boolean, onSend: (String) -> Unit) {
  var draft by remember { mutableStateOf("") }

  Row(
    modifier = Modifier.fillMaxWidth().padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    OutlinedTextField(
      value = draft,
      onValueChange = { draft = it },
      enabled = enabled,
      singleLine = true,
      placeholder = { Text("메시지를 입력하세요") },
      modifier = Modifier.weight(1f),
    )
    Button(
      onClick = {
        val text = draft.trim()
        if (text.isNotEmpty()) {
          draft = ""
          onSend(text)
        }
      },
      enabled = enabled && draft.isNotBlank(),
    ) {
      Text("보내기")
    }
  }
}
