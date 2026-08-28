package dev.starryeye.agentidentity

import dev.starryeye.agentidentity.policy.PolicyProperties
import dev.starryeye.agentidentity.proof.JwsProofVerifier
import java.time.Clock
import java.time.Duration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
@EnableConfigurationProperties(PolicyProperties::class)
class ServerApplication {

  @Bean fun clock(): Clock = Clock.systemUTC()

  // 스프링이 Duration 을 주입하지 못하므로 여기서 직접 빈을 만든다.
  // JwsProofVerifier 에는 @Component 를 붙이지 않는다 — 붙이면 빈이 두 번 만들어진다.
  @Bean
  fun jwsProofVerifier(clock: Clock): JwsProofVerifier = JwsProofVerifier(clock, Duration.ofSeconds(60))
}

fun main(args: Array<String>) {
  runApplication<ServerApplication>(*args)
}
