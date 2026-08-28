package dev.starryeye.agentidentity.policy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * `agent-registration.require-security-level` 오타는 요청 한가운데(`RegistrationPolicy.evaluate`)
 * 에서야 [IllegalArgumentException] 으로 터지면 안 된다 — 이 손잡이를 실험 도구로 신뢰할 수
 * 없게 된다. [RegistrationPolicy.validateConfiguration] 이 `@PostConstruct` 로 기동 시점에
 * 미리 값을 파싱해 컨텍스트 자체를 띄우지 못하게 만드는지 확인한다.
 */
class RegistrationPolicyConfigurationTest {

  @Test
  fun `보안_레벨_설정값이_잘못되면_컨텍스트_기동이_실패한다`() {
    val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(PolicyOnlyConfiguration::class.java)
            .withPropertyValues("agent-registration.require-security-level=ULTRA_SECURE")

    contextRunner.run { context: AssertableApplicationContext ->
      assertThat(context).hasFailed()
      assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
    }
  }

  @Configuration
  @EnableConfigurationProperties(PolicyProperties::class)
  private class PolicyOnlyConfiguration {
    @Bean
    fun registrationPolicy(properties: PolicyProperties): RegistrationPolicy =
        RegistrationPolicy(properties)
  }
}
