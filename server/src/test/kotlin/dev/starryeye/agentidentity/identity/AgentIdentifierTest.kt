package dev.starryeye.agentidentity.identity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AgentIdentifierTest {

  @Test
  fun `형식대로_조립한다`() {
    assertThat(AgentIdentifier.create("samsung", "galaxy-personal-agent", "3f2a"))
        .isEqualTo("urn:samsung:agent:galaxy-personal-agent:3f2a")
  }

  @Test
  fun `접두어가_같아도_다른_식별자다`() {
    // 접두어 비교로 구현하면 x 가 xyz 를 통과시키는 우회가 생긴다.
    val shorter = AgentIdentifier.create("samsung", "p", "x")
    val longer = AgentIdentifier.create("samsung", "p", "xyz")

    assertThat(AgentIdentifier.matches(shorter, longer)).isFalse()
    assertThat(AgentIdentifier.matches(shorter, shorter)).isTrue()
  }
}
