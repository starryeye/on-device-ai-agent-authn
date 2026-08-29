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
import dev.starryeye.ondeviceagent.identity.AgentIdentityState
import dev.starryeye.ondeviceagent.identity.AgentKeyStore
import dev.starryeye.ondeviceagent.identity.AgentRegistrar
import dev.starryeye.ondeviceagent.identity.JwsProofSigner
import dev.starryeye.ondeviceagent.model.ModelStore
import dev.starryeye.ondeviceagent.ui.ChatAuthor
import dev.starryeye.ondeviceagent.ui.ChatMessage
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

  /**
   * 지금 돌고 있는(또는 마지막으로 돌았던) [viewModelScope] 코루틴. [onCleared]가 네이티브
   * 엔진을 닫기 전에 이걸 join해서, 취소해도 멈추지 않는 블로킹 네이티브 작업이 끝날 때까지
   * 기다린다.
   */
  private var activeJob: Job? = null

  private val _messages = mutableStateListOf<ChatMessage>()
  val messages: List<ChatMessage> = _messages

  var uiState: AgentUiState by mutableStateOf(AgentUiState.Loading)
    private set

  /**
   * 에이전트 신원 확립의 현재 상태. 채팅 흐름과 독립적이다 — [uiState]는 이 값을 기다리지
   * 않고, 이 값도 [uiState]를 막지 않는다.
   */
  var identityState: AgentIdentityState by mutableStateOf(AgentIdentityState.Registering)
    private set

  /** turn이 도는 동안 입력을 잠근다. 엔진은 한 번에 하나의 대화만 다룬다. */
  private var busy by mutableStateOf(false)

  val inputEnabled: Boolean
    get() = uiState is AgentUiState.Ready && !busy

  /** 모델을 직접 밀어 넣고 싶은 개발자에게 보여줄 `adb push` 목적지. */
  private val pushHint: String
    get() = ModelStore.pushDirectory(getApplication<Application>())

  init {
    activeJob = viewModelScope.launch {
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

    // 모델 로드와 나란히, 대화와 무관하게 시작한다. 등록 실패가 채팅을 막지 않는다 —
    // 이번 사이클에는 자격증명을 쓰는 툴이 없기 때문이다.
    viewModelScope.launch {
      val keyStore = AgentKeyStore()
      val registrar =
        AgentRegistrar(
          baseUrl = "http://127.0.0.1:8080",
          keys = keyStore,
          proofs = JwsProofSigner(keyStore),
        )
      val state = registrar.ensureIdentity()
      identityState = state
      when (state) {
        is AgentIdentityState.Registered -> {
          addSystem(
            if (state.reused) "기존 에이전트 신원 재사용: ${state.agentId}"
            else "새 에이전트 신원 등록: ${state.agentId}"
          )
          // 발급만으로는 자격증명이 통하는지 모른다. 한 번씩 실제로 써 본다.
          runCatching { registrar.whoami() }
            .onSuccess { addSystem("서버가 확인한 신원: $it") }
            .onFailure { addSystem("신원 확인 실패: ${it.message}") }
          runCatching { registrar.refreshCredential() }
            .onSuccess { addSystem("자격증명 갱신 성공 (attestation 없이)") }
            .onFailure { addSystem("자격증명 갱신 실패: ${it.message}") }
        }
        is AgentIdentityState.Failed -> addSystem("신원 등록 실패: ${state.reason}")
        AgentIdentityState.Registering -> Unit
      }
    }
  }

  /** 가중치를 받고 이어서 로드한다. 이 앱이 네트워크를 쓰는 유일한 경로다. */
  fun downloadModel() {
    if (uiState !is AgentUiState.NeedsModel) return
    activeJob = viewModelScope.launch {
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
          // 이 블록 안에서 바로 필드에 반영한다. viewModelScope가 취소된 채로 이 블록이
          // 끝나면 withContext는 결과 대신 CancellationException으로 재개하므로, 바깥에서
          // "model = ..."를 했다가는 이미 만들어진 네이티브 엔진이 필드 어디에도 걸리지 않고
          // 새어나간다.
          model = created
          created
        }
      runner =
        InMemoryRunner(
          agent = OnDeviceAgent.create(loaded, AndroidBatteryReader(getApplication<Application>())),
          appName = APP_NAME,
          sessionService = sessionService,
        )
      addSystem("준비됐습니다. \"배터리 몇 퍼센트야?\" 라고 물어보세요 — 툴을 호출해 답합니다.")
      uiState = AgentUiState.Ready
    } catch (e: CancellationException) {
      // 취소는 정상적인 종료다. 여기서 화면에 실패를 보고하면 이미 사라지는 중인 화면의
      // Compose 상태를 건드리게 되고, 사용자에게는 거짓 오류로 보인다.
      throw e
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

    activeJob = viewModelScope.launch {
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
   *
   * lifecycle 2.8.7에서 `ViewModel.clear()`는 [onCleared]를 부르기 전에 [viewModelScope]의
   * Job을 먼저 취소한다. 하지만 그 순간 `Dispatchers.IO`에서 돌고 있는 블로킹 네이티브 호출
   * (모델 로드 중 `engine.initialize()`, 또는 turn 도중의 생성)은 협조적 취소를 관찰하지
   * 못하고 그대로 계속 실행된다. 그 작업이 실제로 끝나기 전에 여기서 바로 엔진을 닫으면 그
   * 네이티브 호출은 이미 해제된 핸들을 계속 쓰게 된다 — JNI 쪽 use-after-free라 Throwable로
   * 잡히지도 않는다. 그래서 [activeJob]이 실제로 끝날 때까지 기다린 다음에야 엔진을 닫는다.
   */
  override fun onCleared() {
    val job = activeJob
    thread(name = "litertlm-close") {
      if (job != null) {
        // join은 그 코루틴이 실제로 멈춘 뒤에야 돌아온다 — 협조적 취소가 아니라 블로킹
        // 네이티브 호출이 자연히 리턴하는 시점까지 기다리는 것이다.
        runBlocking { job.join() }
      }
      // model은 로드 중 취소되더라도 loadModel이 IO 블록 안에서 미리 필드에 반영해두므로,
      // join이 끝난 지금 시점에는 "만들어졌다면 반드시 여기 담겨 있다"가 보장된다.
      val closing = model
      model = null
      runner = null
      if (closing != null) {
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
