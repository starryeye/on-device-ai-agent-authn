package dev.starryeye.ondeviceagent.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.litertlm.LiteRtLmModel
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import dev.starryeye.ondeviceagent.model.ModelStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 기기에서 실제 모델로 에이전트를 구동해, 어떤 조건에서 툴 호출이 일어나고 일어나지 않는지 잰다.
 *
 * UI와 입력기를 거치지 않는다. `adb shell input text`가 한글을 주입하지 못하는 것은 도구의
 * 한계일 뿐인데, 클립보드나 브라우저로 우회하면 관찰에 변수가 하나 더 붙는다. 에이전트를 직접
 * 부르면 남는 변수는 질문 자체뿐이다.
 *
 * 모델이 확률적이므로 한 번의 성공/실패로 결론내지 않고 [SAMPLES]회씩 반복한다. 단언이 실패하면
 * 표본별 답변 원문이 메시지에 그대로 실린다.
 */
@RunWith(AndroidJUnit4::class)
class ToolCallLanguageTest {

  /** 한 turn에서 관찰한 것. */
  private data class Turn(val toolCalled: Boolean, val reply: String)

  /** 여러 turn이 기억을 공유하는 한 번의 대화. 앱이 하는 것과 같은 구성이다. */
  private inner class Conversation(val battery: () -> Int) {
    private val runner =
      InMemoryRunner(
        agent = OnDeviceAgent.create(model, BatteryReader { battery() }),
        appName = "ToolCallLanguageTest",
        sessionService = InMemorySessionService(),
      )

    fun ask(prompt: String): Turn {
      val events = runBlocking {
        runner
          .runAsync(
            userId = "probe",
            sessionId = "probe-session",
            newMessage = Content(role = Role.USER, parts = listOf(Part(text = prompt))),
            runConfig = RunConfig(streamingMode = StreamingMode.NONE, maxLlmCalls = 8),
          )
          .toList()
      }
      val toolCalled =
        events.any { event ->
          event.content?.parts.orEmpty().any { it.functionCall?.name == "get_battery_level" }
        }
      val reply =
        events
          .filter { !it.partial && it.author == OnDeviceAgent.NAME }
          .flatMap { it.content?.parts.orEmpty() }
          .filter { it.thought != true }
          .mapNotNull { it.text }
          .joinToString("")
          .trim()
      return Turn(toolCalled, reply)
    }
  }

  /** 대화를 처음부터 새로 시작해 [prompt] 하나만 던진다. */
  private fun freshSession(label: String, prompt: String): List<Turn> =
    (1..SAMPLES).map { sample ->
      val turn = Conversation(battery = { FIRST_BATTERY }).ask(prompt)
      println("[$label $sample] tool=${turn.toolCalled} reply=\"${turn.reply}\"")
      turn
    }

  /**
   * 앞선 실패를 그대로 재현한다: 먼저 영어로 물어 답을 하나 받고, 배터리를 바꾼 뒤 [followUp]을
   * 같은 대화에서 던진다. 두 번째 답이 새 값을 말하는지, 직전 값을 되풀이하는지가 관건이다.
   */
  private fun followUpInSameSession(
    label: String,
    followUp: String,
    opening: String = "What is my battery percentage?",
  ): List<Turn> =
    (1..SAMPLES).map { sample ->
      var battery = FIRST_BATTERY
      val conversation = Conversation(battery = { battery })
      val first = conversation.ask(opening)
      battery = SECOND_BATTERY
      val second = conversation.ask(followUp)
      println(
        "[$label $sample] 1st tool=${first.toolCalled} reply=\"${first.reply}\" | " +
          "2nd tool=${second.toolCalled} reply=\"${second.reply}\""
      )
      second
    }

  private fun report(label: String, turns: List<Turn>): String =
    turns.joinToString(
      prefix = "$label — 툴 호출 ${turns.count { it.toolCalled }}/${turns.size}\n",
      separator = "\n",
    ) { "  tool=${it.toolCalled} reply=\"${it.reply}\"" }

  /**
   * 툴을 불렀는지와 **그 값이 답에 담겼는지**를 함께 본다. 툴만 부르고 직전 숫자를 되풀이하는
   * 실패가 앞선 관찰의 정확한 모습이었으므로, 호출 여부만으로는 부족하다.
   */
  private fun assertAnsweredFromTool(label: String, expected: Int, turns: List<Turn>) {
    assertTrue(report(label, turns), turns.all { it.toolCalled && it.reply.contains("$expected") })
  }

  @Test
  fun 새_대화에서_영어로_물으면_툴을_호출한다() {
    val turns = freshSession("fresh-en", "What is my battery percentage?")
    assertAnsweredFromTool("새 대화 / 영어", FIRST_BATTERY, turns)
  }

  @Test
  fun 새_대화에서_한국어로_물으면_툴을_호출한다() {
    val turns = freshSession("fresh-ko", "배터리 몇 퍼센트야?")
    assertAnsweredFromTool("새 대화 / 한국어", FIRST_BATTERY, turns)
  }

  @Test
  fun 같은_대화에서_영어로_다시_물으면_툴을_다시_호출한다() {
    val turns = followUpInSameSession("again-en", "What is my battery percentage now?")
    assertAnsweredFromTool("이어지는 대화 / 영어", SECOND_BATTERY, turns)
  }

  @Test
  fun 같은_대화에서_한국어로_다시_물으면_툴을_다시_호출한다() {
    val turns = followUpInSameSession("again-ko", "배터리 몇 퍼센트야?")
    assertAnsweredFromTool("이어지는 대화 / 한국어", SECOND_BATTERY, turns)
  }

  /** 반대 방향도 본다: 한국어로 답이 오간 뒤 영어로 다시 물었을 때. */
  @Test
  fun 한국어_대화에서_영어로_다시_물으면_툴을_다시_호출한다() {
    val turns =
      followUpInSameSession(
        "again-en-after-ko",
        followUp = "What is my battery percentage now?",
        opening = "배터리 몇 퍼센트야?",
      )
    assertAnsweredFromTool("이어지는 대화 / 한국어→영어", SECOND_BATTERY, turns)
  }

  private companion object {
    const val SAMPLES = 3

    /** 기기의 실제 잔량과 겹치지 않는 값들. 답에 이 숫자가 있으면 툴을 거친 것이다. */
    const val FIRST_BATTERY = 63
    const val SECOND_BATTERY = 88

    lateinit var model: LiteRtLmModel

    @BeforeClass
    @JvmStatic
    fun loadModel() {
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      val modelFile = ModelStore.find(context)
      // 모델이 없는 기기에서는 이 테스트가 의미를 갖지 못한다. 실패가 아니라 건너뛴다.
      assumeTrue("`.litertlm` 모델이 기기에 없어 건너뜁니다.", modelFile != null)
      model = OnDeviceAgent.createModel(modelFile!!, context.cacheDir)
      model.engine.initialize()
    }

    @AfterClass
    @JvmStatic
    fun releaseModel() {
      if (::model.isInitialized) model.close()
    }
  }
}
