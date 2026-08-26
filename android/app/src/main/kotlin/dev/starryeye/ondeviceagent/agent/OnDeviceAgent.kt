package dev.starryeye.ondeviceagent.agent

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.litertlm.LiteRtLmModel
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

/**
 * 온디바이스 모델과 그것을 쓰는 [LlmAgent]를 만든다.
 *
 * 모델을 따로 만드는 이유는 그것이 네이티브 엔진을 소유하기 때문이다. 엔진의 수명은
 * [dev.starryeye.ondeviceagent.AgentViewModel]이 자기 수명에 묶어 관리한다.
 */
object OnDeviceAgent {

  const val NAME: String = "on_device_agent"

  /**
   * [modelFile]을 CPU 백엔드로 연다. 돌려받은 모델은 네이티브 엔진을 소유하므로 반드시
   * 닫아야 한다. [cacheDir]에 컴파일된 모델 캐시를 두어 시스템이 회수할 수 있게 한다.
   */
  fun createModel(modelFile: File, cacheDir: File): LiteRtLmModel =
    LiteRtLmModel.create(
      EngineConfig(
        modelPath = modelFile.absolutePath,
        backend = Backend.CPU(),
        cacheDir = cacheDir.absolutePath,
      ),
      name = modelFile.name,
    )

  /**
   * 이미 만들어진 [model] 위에 에이전트를 세운다.
   *
   * instruction을 영어로 쓴 것은 의도적이다. 2B급 모델은 영어 지시에서 툴 호출 판단이
   * 눈에 띄게 안정적이다.
   *
   * 재호출 규칙을 이렇게까지 못박은 데는 근거가 있다. 이 문단이 없을 때, 이미 답이 오간 대화에
   * 질문이 다른 언어로 들어오면 모델이 툴을 다시 부르지 않고 직전 답을 번역해 되풀이했다
   * (영어로 답한 뒤 한국어로 다시 묻는 조건에서 0/3). 같은 조건이라도 언어가 그대로면 3/3으로
   * 정상 호출됐으니, 무너지는 지점은 언어 자체가 아니라 "이미 답이 있는데 언어가 바뀌는 것"이다.
   * ToolCallLanguageTest가 네 조건을 모두 재서 이 회귀를 막는다.
   */
  fun create(model: LiteRtLmModel, batteryReader: BatteryReader): LlmAgent =
    LlmAgent(
      name = NAME,
      model = model,
      instruction =
        Instruction(
          """
          You are a helpful assistant running entirely on this device. Keep replies to one or two
          short sentences, written in the same language the user used.

          Call get_battery_level every single time the user asks about the battery — in any
          language, and no matter how many times they ask. The battery level changes as the device
          runs, so any number earlier in this conversation is already stale. Never reuse an earlier
          number, and never translate an earlier answer instead of calling the tool. State the exact
          value the tool returned.
          """
            .trimIndent()
        ),
      tools = DeviceTools(batteryReader).generatedTools(),
    )
}
