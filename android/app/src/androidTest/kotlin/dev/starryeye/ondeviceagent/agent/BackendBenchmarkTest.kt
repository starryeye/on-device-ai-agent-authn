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
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import dev.starryeye.ondeviceagent.model.ModelStore
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 백엔드(CPU / GPU / NPU)를 바꿔가며 같은 질문을 던져 속도를 잰다.
 *
 * 앱은 [OnDeviceAgent.createModel]에서 CPU로 고정돼 있다. 그게 에뮬레이터에서 유일하게 되는
 * 선택지였기 때문인데, 실기기에서는 다른 선택지가 있을 수 있어 실제로 재 본다.
 *
 * NPU는 벤더가 제공하는 디스패치 라이브러리를 요구한다([Backend.NPU]의 `nativeLibraryDir`).
 * LiteRT-LM AAR에는 그런 라이브러리가 없으므로 이 기기에서 실패할 가능성이 높다. 실패도
 * 결과이므로 예외를 삼켜 기록만 하고 다음 백엔드로 넘어간다.
 *
 * 생성 길이가 매번 달라 정밀한 벤치마크는 아니다. 크기 차수를 보는 용도다.
 */
@RunWith(AndroidJUnit4::class)
class BackendBenchmarkTest {

  private data class Measurement(
    val backend: String,
    val loadMillis: Long?,
    val turnMillis: List<Long>,
    val replyChars: List<Int>,
    val failure: String?,
  )

  private fun measure(label: String, backend: () -> Backend): Measurement {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val modelFile = ModelStore.find(context)!!
    var model: LiteRtLmModel? = null
    return try {
      val loadStart = System.currentTimeMillis()
      val created =
        LiteRtLmModel.create(
          EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = backend(),
            cacheDir = context.cacheDir.absolutePath,
          ),
          name = modelFile.name,
        )
      model = created
      created.engine.initialize()
      val loadMillis = System.currentTimeMillis() - loadStart

      val turnMillis = mutableListOf<Long>()
      val replyChars = mutableListOf<Int>()
      repeat(TURNS) { turn ->
        val runner =
          InMemoryRunner(
            agent = OnDeviceAgent.create(created, BatteryReader { BATTERY }),
            appName = "BackendBenchmark",
            sessionService = InMemorySessionService(),
          )
        val start = System.currentTimeMillis()
        val events = runBlocking {
          runner
            .runAsync(
              userId = "bench",
              sessionId = "$label-$turn",
              newMessage = Content(role = Role.USER, parts = listOf(Part(text = PROMPT))),
              runConfig = RunConfig(streamingMode = StreamingMode.NONE, maxLlmCalls = 8),
            )
            .toList()
        }
        turnMillis += System.currentTimeMillis() - start
        replyChars +=
          events
            .filter { !it.partial && it.author == OnDeviceAgent.NAME }
            .flatMap { it.content?.parts.orEmpty() }
            .mapNotNull { it.text }
            .sumOf { it.length }
      }
      Measurement(label, loadMillis, turnMillis, replyChars, failure = null)
    } catch (t: Throwable) {
      // 이 기기에서 못 쓰는 백엔드라는 사실 자체가 결과다.
      Measurement(label, null, emptyList(), emptyList(), "${t::class.simpleName}: ${t.message}")
    } finally {
      // 다음 백엔드가 메모리를 쓸 수 있도록 반드시 놓아준다.
      runCatching { model?.close() }
    }
  }

  private fun render(m: Measurement): String =
    if (m.failure != null) {
      "${m.backend}: 사용 불가 — ${m.failure}"
    } else {
      val avg = m.turnMillis.average() / 1000.0
      val chars = m.replyChars.sum()
      val seconds = m.turnMillis.sum() / 1000.0
      "${m.backend}: 로드 ${m.loadMillis!! / 1000.0}s | " +
        "turn 평균 ${"%.1f".format(avg)}s ${m.turnMillis.map { it / 1000 }}s | " +
        "${"%.1f".format(chars / seconds)} chars/s"
    }

  @Test
  fun 백엔드별_속도를_잰다() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assumeTrue("`.litertlm` 모델이 기기에 없어 건너뜁니다.", ModelStore.find(context) != null)

    val results =
      listOf(
        measure("CPU") { Backend.CPU() },
        measure("GPU") { Backend.GPU() },
        // 벤더 디스패치 라이브러리가 있다면 앱의 네이티브 라이브러리 디렉터리에 있다.
        measure("NPU") { Backend.NPU(context.applicationInfo.nativeLibraryDir) },
      )

    val report = results.joinToString("\n") { render(it) }
    println(report)
    File(context.getExternalFilesDir(null) ?: context.filesDir, "backend-benchmark.txt")
      .writeText(report + "\n")
  }

  private companion object {
    const val TURNS = 3
    const val BATTERY = 63
    const val PROMPT = "What is my battery percentage?"
  }
}
