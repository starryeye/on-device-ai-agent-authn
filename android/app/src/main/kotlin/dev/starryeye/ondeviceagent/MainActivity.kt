package dev.starryeye.ondeviceagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.starryeye.ondeviceagent.ui.ChatScreen

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          // ViewModel이 회전을 견딘다 — 2.5GB 모델을 다시 로드하지 않는다.
          val viewModel: AgentViewModel = viewModel()
          ChatScreen(
            messages = viewModel.messages,
            uiState = viewModel.uiState,
            inputEnabled = viewModel.inputEnabled,
            onSend = viewModel::send,
            onDownload = viewModel::downloadModel,
          )
        }
      }
    }
  }
}
