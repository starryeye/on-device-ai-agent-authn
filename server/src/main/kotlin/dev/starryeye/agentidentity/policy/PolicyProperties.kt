package dev.starryeye.agentidentity.policy

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/** `agent-registration.*` 설정. 이 값들이 실험 손잡이다. */
@ConfigurationProperties(prefix = "agent-registration")
class PolicyProperties {
  var requireSecurityLevel: String = "TRUSTED_ENVIRONMENT"
  var requireVerifiedBoot: Boolean = true
  var requireDeviceLocked: Boolean = true
  var allowedPackages: List<String> = emptyList()
  var requireDeviceBinding: Boolean = false
  var requirePlayIntegrity: Boolean = false
  var agentProductId: String = "galaxy-personal-agent"
  var identifierNamespace: String = "samsung"
  var challengeTtl: Duration = Duration.ofMinutes(5)
  var credentialTtl: Duration = Duration.ofMinutes(15)
  var maxAttestationAge: Duration = Duration.ofDays(7)

  // TTL 청소는 시간이 지나야 작동한다. TTL 안에서 /challenge 를 반복 호출하는 홍수는
  // 시간 청소로 막히지 않으므로 별도의 크기 상한이 필요하다.
  var maxPendingChallenges: Int = 10_000
}
