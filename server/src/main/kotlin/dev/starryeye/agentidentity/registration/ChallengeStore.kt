package dev.starryeye.agentidentity.registration

import dev.starryeye.agentidentity.policy.PolicyProperties
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

/**
 * 등록 challenge 를 발급하고 **한 번만** 소비한다.
 *
 * 1회용이라는 성질이 오래된 attestation 체인의 재사용을 막는다. 재사용을 허용하면 한 번 유출된
 * 체인으로 언제든 등록할 수 있다.
 */
@Component
class ChallengeStore(
    private val properties: PolicyProperties,
    private val clock: Clock,
) {

  /** 등록 거래 하나. registrationId 는 거래 식별자이지 신원이 아니다. */
  data class Challenge(val registrationId: String, val value: ByteArray, val expiresAt: Instant)

  private val issued = ConcurrentHashMap<String, Challenge>()
  private val random = SecureRandom()

  fun issue(): Challenge {
    val now = clock.instant()
    // 만료됐지만 소비되지 않은 challenge 는 consume() 을 거치지 않으므로 여기서 쓸어낸다.
    // 이 엔드포인트는 인증 없이 누구나 호출할 수 있어, 청소가 없으면 반복 호출만으로 맵이
    // 무한히 자란다.
    issued.entries.removeIf { it.value.expiresAt.isBefore(now) }

    val value = ByteArray(32)
    random.nextBytes(value)
    val challenge = Challenge(UUID.randomUUID().toString(), value, now.plus(properties.challengeTtl))
    issued[challenge.registrationId] = challenge
    return challenge
  }

  /** 테스트가 맵 크기를 직접 관찰할 수 있게 한다. */
  internal fun issuedCount(): Int = issued.size

  /** 소비하면 사라진다. 만료된 것도 사라진다. */
  fun consume(registrationId: String): ByteArray? {
    val challenge = issued.remove(registrationId) ?: return null
    if (challenge.expiresAt.isBefore(clock.instant())) {
      return null
    }
    return challenge.value
  }

  companion object {
    fun encode(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)
  }
}
