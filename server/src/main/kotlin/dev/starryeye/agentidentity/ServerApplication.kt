package dev.starryeye.agentidentity

import dev.starryeye.agentidentity.policy.PolicyProperties
import java.time.Clock
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
@EnableConfigurationProperties(PolicyProperties::class)
class ServerApplication {

  @Bean fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
  runApplication<ServerApplication>(*args)
}
