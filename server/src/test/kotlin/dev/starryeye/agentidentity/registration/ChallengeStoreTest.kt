package dev.starryeye.agentidentity.registration

import dev.starryeye.agentidentity.policy.PolicyProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `ChallengeStore` 는 스프링 없이도 직접 만들 수 있다 — `Clock` 을 생성자로 주입받는 이유가
 * 정확히 이것이다. 만료 검사를 시각을 흘려보내며 시험한다.
 */
class ChallengeStoreTest {

  /** 테스트가 흐르는 시각을 직접 밀 수 있게 하는 가짜 시계. */
  private class MutableClock(private var current: Instant) : Clock() {
    override fun instant(): Instant = current

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    fun advanceTo(instant: Instant) {
      current = instant
    }
  }

  @Test
  fun `TTL이_지난_challenge는_소비되지_않는다`() {
    val properties = PolicyProperties().apply { challengeTtl = Duration.ofMinutes(5) }
    val clock = MutableClock(Instant.parse("2026-08-28T00:00:00Z"))
    val store = ChallengeStore(properties, clock)

    val challenge = store.issue()
    // TTL(5분) 을 넘겨서 시계를 민다.
    clock.advanceTo(Instant.parse("2026-08-28T00:05:01Z"))

    assertThat(store.consume(challenge.registrationId)).isNull()
  }

  @Test
  fun `TTL_이내의_challenge는_소비된다`() {
    val properties = PolicyProperties().apply { challengeTtl = Duration.ofMinutes(5) }
    val clock = MutableClock(Instant.parse("2026-08-28T00:00:00Z"))
    val store = ChallengeStore(properties, clock)

    val challenge = store.issue()
    // TTL(5분) 이내로 시계를 민다 — 이 값 자체가 소비되어야 한다는 것을 함께 확인한다.
    clock.advanceTo(Instant.parse("2026-08-28T00:04:59Z"))

    assertThat(store.consume(challenge.registrationId)).isEqualTo(challenge.value)
  }
}
