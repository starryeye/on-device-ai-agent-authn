package dev.starryeye.agentidentity.registration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class RegistrationServiceTest {

  @Autowired private lateinit var challengeStore: ChallengeStore

  @Test
  fun `challenge_는_한_번만_소비된다`() {
    val challenge = challengeStore.issue()

    assertThat(challengeStore.consume(challenge.registrationId)).isNotNull()
    assertThat(challengeStore.consume(challenge.registrationId)).isNull()
  }

  @Test
  fun `없는_registrationId_는_소비되지_않는다`() {
    assertThat(challengeStore.consume("모르는-값")).isNull()
  }
}
