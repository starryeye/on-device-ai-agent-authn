package dev.starryeye.agentidentity.identity

/**
 * 에이전트 식별자 조립을 **전담**한다. 형식이 이 클래스 밖으로 새어 나가지 않게 한다 —
 * 나중에 표준 형식으로 옮길 이유가 생기면 교체가 여기 한 곳으로 끝나야 한다.
 *
 * `urn:` 문법을 따르지만 NID `samsung` 은 IANA 미등록 사설 네임스페이스다.
 */
object AgentIdentifier {

  fun create(namespace: String, productId: String, instanceId: String): String =
      "urn:$namespace:agent:$productId:$instanceId"

  /**
   * 인가 판단용 비교. **전체 문자열 일치만 허용한다.**
   * 접두어 비교는 `...:agent:x` 가 `...:agent:xyz` 를 통과시키는 우회가 된다.
   */
  fun matches(left: String?, right: String?): Boolean = left != null && left == right
}
