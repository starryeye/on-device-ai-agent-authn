package dev.starryeye.ondeviceagent.agent

import android.content.Context
import android.os.BatteryManager
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.tools.FunctionTool

/**
 * 배터리 잔량을 읽는 얇은 경계. `BatteryManager`를 이 뒤로 밀어내면 [DeviceTools]가
 * 안드로이드 없이 테스트된다.
 */
fun interface BatteryReader {
  /** 0..100의 잔량. 기기가 보고하지 못하면 그 범위 밖의 값. */
  fun batteryPercent(): Int
}

/** 실제 기기에서 값을 읽는 [BatteryReader]. */
class AndroidBatteryReader(context: Context) : BatteryReader {

  private val batteryManager = context.getSystemService(BatteryManager::class.java)

  override fun batteryPercent(): Int =
    batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: Int.MIN_VALUE
}

/**
 * 에이전트가 호출할 수 있는 툴. KSP `@Tool` 프로세서가 이 클래스에 대한 확장 함수
 * `generatedTools()`를 만들어 준다.
 *
 * 툴이 하나뿐인 이유는 이것이 최소 샘플이기 때문이다. 배터리 잔량은 모델이 결코 알 수 없는
 * 실제 기기 상태이므로, 응답에 실제 수치가 나오면 툴이 진짜 실행됐다는 증거가 된다.
 */
class DeviceTools(private val batteryReader: BatteryReader) {

  @Tool(
    name = "get_battery_level",
    description = "Returns this device's current battery charge, as a percentage.",
  )
  fun getBatteryLevel(): Map<String, Any> = batteryLevelResult(batteryReader.batteryPercent())
}

/**
 * 잔량을 툴 응답으로 바꾼다. 잔량을 보고하지 못하는 기기는 `Integer.MIN_VALUE`를 주므로,
 * 그런 값을 모델에 넘기지 않는다.
 *
 * 실패 시 이 맵에 담기는 `FunctionTool.ERROR_KEY`("error")는 ADK가 특별히 처리해 주는
 * 키가 아니라 단순한 관례다. 게다가 KSP가 생성한 툴 래퍼(`GetBatteryLevelTool`)가
 * [DeviceTools.getBatteryLevel]의 반환값을 `BaseTool.RESULT_KEY`("result")로 한 번 더
 * 감싸므로, 모델에 실제로 전달되는 JSON은 `{"error": "..."}`이 아니라
 * `{"result": {"error": "..."}}` 형태이고, 성공 시에도 `{"result": {"battery_percent": N}}`
 * 형태다.
 */
internal fun batteryLevelResult(percent: Int): Map<String, Any> =
  if (percent in 0..100) {
    mapOf("battery_percent" to percent)
  } else {
    mapOf(FunctionTool.ERROR_KEY to "This device does not report its battery level.")
  }
