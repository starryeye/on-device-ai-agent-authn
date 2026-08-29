package dev.starryeye.ondeviceagent.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `AgentRegistrar.reregistrationOriginFor` 는 안드로이드 의존성이 전혀 없는 순수
 * `String -> RegistrationOrigin?` 매핑이라 여기서 직접 시험할 수 있다.
 *
 * 음의 절반(`DPOP_INVALID`·`AGENT_INACTIVE` 는 재등록을 트리거하지 않는다)이 핵심이다 —
 * 이전 라운드에서 폐기 우회를 막으려고 세운 불변식인데, `else -> null` 대신 `else ->
 * RegistrationOrigin.FIRST_RUN` 같은 관대한 분기로 회귀해도 지금까지는 어떤 테스트도 잡아내지
 * 못했다.
 */
class AgentRegistrarTest {

  @Test
  fun `REATTESTATION_REQUIRED_는_재등록을_트리거한다`() {
    assertEquals(
      RegistrationOrigin.REATTESTATION_REQUIRED,
      AgentRegistrar.reregistrationOriginFor("REATTESTATION_REQUIRED"),
    )
  }

  @Test
  fun `AGENT_NOT_FOUND_는_재등록을_트리거한다`() {
    assertEquals(
      RegistrationOrigin.AGENT_NOT_FOUND,
      AgentRegistrar.reregistrationOriginFor("AGENT_NOT_FOUND"),
    )
  }

  @Test
  fun `DPOP_INVALID_는_재등록을_트리거하지_않는다`() {
    assertNull(AgentRegistrar.reregistrationOriginFor("DPOP_INVALID"))
  }

  @Test
  fun `AGENT_INACTIVE_는_재등록을_트리거하지_않는다`() {
    assertNull(AgentRegistrar.reregistrationOriginFor("AGENT_INACTIVE"))
  }
}
