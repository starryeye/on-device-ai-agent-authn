package dev.starryeye.ondeviceagent.agent

import com.google.adk.kt.tools.FunctionTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceToolsTest {

  @Test
  fun `정상 범위의 배터리 값은 그대로 전달한다`() {
    assertEquals(mapOf("battery_percent" to 42), batteryLevelResult(42))
  }

  @Test
  fun `경계값 0과 100도 정상으로 본다`() {
    assertEquals(mapOf("battery_percent" to 0), batteryLevelResult(0))
    assertEquals(mapOf("battery_percent" to 100), batteryLevelResult(100))
  }

  @Test
  fun `범위를 벗어난 값은 모델에 넘기지 않고 오류로 바꾼다`() {
    val result = batteryLevelResult(Int.MIN_VALUE)

    assertTrue(result.containsKey(FunctionTool.ERROR_KEY))
    assertTrue(!result.containsKey("battery_percent"))
  }

  @Test
  fun `툴은 주입된 리더가 준 값을 사용한다`() {
    val tools = DeviceTools(BatteryReader { 77 })

    assertEquals(mapOf("battery_percent" to 77), tools.getBatteryLevel())
  }
}
