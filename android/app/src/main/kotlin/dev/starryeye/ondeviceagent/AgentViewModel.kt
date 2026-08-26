package dev.starryeye.ondeviceagent

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.events.Event
import com.google.adk.kt.litertlm.LiteRtLmModel
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import dev.starryeye.ondeviceagent.agent.AndroidBatteryReader
import dev.starryeye.ondeviceagent.agent.OnDeviceAgent
import dev.starryeye.ondeviceagent.model.ModelStore
import dev.starryeye.ondeviceagent.ui.ChatAuthor
import dev.starryeye.ondeviceagent.ui.ChatMessage
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 화면의 상태를 들고, 모델을 확보해 ADK Runner를 세우고, 한 turn을 돌린다.
 *
 * Activity가 아니라 ViewModel인 이유는 하나다. 모델 로드가 수 초 걸리고 2.5GB 가중치를
 * 붙잡으므로, 화면 회전마다 다시 로드하면 앱을 쓸 수 없다.
 *
 * 코루틴은 [viewModelScope]에서 메인 디스패처로 돌고, 블로킹 구간만 [Dispatchers.IO]로
 * 넘긴다. 그래서 상태 갱신은 항상 메인 스레드에서 일어난다.
 */
class AgentViewModel(application: Application) : AndroidViewModel(application) {

  private val sessionService = InMemorySessionService()
  private var runner: InMemoryRunner? = null
  private var model: LiteRtLmModel? = null

  private val _messages = mutableStateListOf<ChatMessage>()
  val messages: List<ChatMessage> = _messages

  var uiState: AgentUiState by mutableStateOf(AgentUiState.Loading)
    private set

  /** turn이 도는 동안 입력을 잠근다. 엔진은 한 번에 하나의 대화만 다룬다. */
  private var busy by mutableStateOf(false)

  val inputEnabled: Boolean
    get() = uiState is AgentUiState.Ready && !busy

  /** 모델을 직접 밀어 넣고 싶은 개발자에게 보여줄 `adb push` 목적지. */
  private val pushHint: String
    get() = ModelStore.pushDirectory(getApplication<Application>())

  init {
    viewModelScope.launch {
      val modelFile = withContext(Dispatchers.IO) { ModelStore.find(getApplication<Application>()) }
      if (modelFile == null) {
        addSystem(
          "이 앱은 온디바이스 모델 파일(약 ${ModelStore.DOWNLOAD_SIZE_LABEL})이 필요합니다. " +
            "아래에서 내려받거나 직접 밀어 넣으세요:\n\nadb push your-model.litertlm $pushHint/"
        )
        uiState = AgentUiState.NeedsModel
      } else {
        loadModel(modelFile)
      }
    }
  }

  /** 가중치를 받고 이어서 로드한다. 이 앱이 네트워크를 쓰는 유일한 경로다. */
  fun downloadModel() {
    if (uiState !is AgentUiState.NeedsModel) return
    viewModelScope.launch {
      uiState = AgentUiState.Downloading(0f)
      try {
        ModelStore.download(getApplication<Application>()).collect { fraction ->
          uiState = AgentUiState.Downloading(fraction)
        }
        val modelFile =
          withContext(Dispatchers.IO) { ModelStore.find(getApplication<Application>()) }
            ?: error("다운로드는 끝났는데 모델 파일을 찾을 수 없습니다.")
        loadModel(modelFile)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        addSystem("다운로드 실패: ${e.message ?: e::class.simpleName}")
        uiState = AgentUiState.NeedsModel
      }
    }
  }

  private suspend fun loadModel(modelFile: File) {
    uiState = AgentUiState.Loading
    addSystem("${modelFile.name} 을(를) 불러오는 중입니다 (수 초 걸립니다)…")
    try {
      val loaded =
        withContext(Dispatchers.IO) {
          val created = OnDeviceAgent.createModel(modelFile, getApplication<Application>().cacheDir)
          // 첫 메시지가 아니라 지금 로드한다. 그래야 깨진 파일이 깨진 파일로 보고된다.
          created.engine.initialize()
          created
        }
      model = loaded
      runner =
        InMemoryRunner(
          agent = OnDeviceAgent.create(loaded, AndroidBatteryReader(getApplication<Application>())),
          appName = APP_NAME,
          sessionService = sessionService,
        )
      addSystem("준비됐습니다. \"배터리 몇 퍼센트야?\" 라고 물어보세요 — 툴을 호출해 답합니다.")
      uiState = AgentUiState.Ready
    } catch (e: Throwable) {
      // Throwable: 네이티브 바이너리가 없는 기기는 UnsatisfiedLinkError로 실패한다.
      val reason = e.message ?: e::class.simpleName ?: "알 수 없는 오류"
      addSystem("모델을 불러오지 못했습니다: $reason")
      uiState = AgentUiState.Failed(reason)
    }
  }

  /** 한 turn을 돌린다. 툴 호출 판단·실행·되먹임은 전부 Runner 안에서 일어난다. */
  fun send(text: String) {
    val activeRunner = runner ?: return
    if (!inputEnabled) return
    _messages.add(ChatMessage(ChatAuthor.USER, text))
    busy = true

    viewModelScope.launch {
      val partial = StringBuilder()
      var bubbleIndex = -1
      try {
        activeRunner
          .runAsync(
            userId = USER_ID,
            sessionId = SESSION_ID,
            newMessage = Content(role = Role.USER, parts = listOf(Part(text = text))),
            runConfig =
              RunConfig(
                streamingMode = StreamingMode.SSE,
                // 작은 모델은 툴 하나를 물고 늘어질 수 있다. turn을 끝나게 만드는 상한.
                maxLlmCalls = MAX_LLM_CALLS,
              ),
          )
          .flowOn(Dispatchers.IO)
          .collect { event ->
            if (event.author != OnDeviceAgent.NAME) return@collect
            val chunk = event.visibleText()
            if (event.partial) {
              // SSE: 조각이 올 때마다 같은 말풍선을 키운다.
              if (chunk.isNotEmpty()) {
                partial.append(chunk)
                bubbleIndex = showAgentText(bubbleIndex, partial.toString())
              }
            } else {
              // 집계된 이벤트가 turn을 끝낸다. 그쪽 텍스트가 정본이다.
              val finalText = chunk.ifBlank { partial.toString() }.trim()
              if (finalText.isNotEmpty()) bubbleIndex = showAgentText(bubbleIndex, finalText)
              partial.setLength(0)
              // 툴 호출은 turn을 둘로 쪼갠다. 다음 조각은 새 말풍선을 갖는다.
              bubbleIndex = -1
              reportActivity(event)
            }
          }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        addSystem("오류: ${e.message ?: e::class.simpleName}")
      } finally {
        busy = false
      }
    }
  }

  /**
   * 네이티브 엔진을 놓아준다. 별도 스레드인 것은 해제가 느리기 때문이고, 예외를 삼키는 것은
   * 여기서 터진 예외가 프로세스를 통째로 내리기 때문이다.
   */
  override fun onCleared() {
    val closing = model
    model = null
    runner = null
    if (closing != null) {
      thread(name = "litertlm-close") {
        try {
          closing.close()
        } catch (_: Throwable) {
          // 보고할 화면이 이미 사라진 뒤다.
        }
      }
    }
    super.onCleared()
  }

  /** 어떤 툴이 불렸는지, 모델이 답 대신 오류를 냈는지 화면에 남긴다. */
  private fun reportActivity(event: Event) {
    event.errorMessage?.let { addSystem("모델 오류: $it") }
    for (part in event.content?.parts.orEmpty()) {
      part.functionCall?.name?.let { addSystem("툴 호출: $it") }
    }
  }

  /** 진행 중인 말풍선을 갱신하거나 새로 만든다. 새 인덱스를 돌려준다. */
  private fun showAgentText(index: Int, text: String): Int =
    if (index < 0) {
      _messages.add(ChatMessage(ChatAuthor.AGENT, text))
      _messages.lastIndex
    } else {
      _messages[index] = _messages[index].copy(text = text)
      index
    }

  private fun addSystem(text: String) {
    _messages.add(ChatMessage(ChatAuthor.SYSTEM, text))
  }

  private companion object {
    const val APP_NAME = "OnDeviceAgent"
    const val USER_ID = "local-user"
    const val SESSION_ID = "local-session"
    const val MAX_LLM_CALLS = 8
  }
}

/**
 * 이벤트가 담은 눈에 보이는 응답 텍스트. thought 조각은 빼고, 구분자 없이 잇는다 — 조각들은
 * 한 문장의 파편이라 구분자를 넣으면 스트리밍 도중 단어 가운데에 공백이 끼어든다.
 */
private fun Event.visibleText(): String =
  content?.parts.orEmpty().filter { it.thought != true }.mapNotNull { it.text }.joinToString("")
