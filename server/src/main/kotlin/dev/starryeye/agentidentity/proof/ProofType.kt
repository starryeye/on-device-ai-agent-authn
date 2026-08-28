package dev.starryeye.agentidentity.proof

/**
 * proof 의 용도. **`typ` 이 다른 것이 핵심이다.**
 *
 * RFC 9449 는 DPoP proof 의 `typ` 을 `dpop+jwt` 로 강제한다. 등록 PoP 가 같은 `typ` 을 쓰면
 * 한쪽에서 얻은 proof 를 다른 쪽에 재생할 수 있다. 등록 PoP 는 신원이 없는 상태에서,
 * 런타임 DPoP 는 신원이 확정된 뒤에 오므로 서로 통과시키면 안 된다.
 */
enum class ProofType(val typ: String) {
  REGISTRATION("agent-reg-pop+jwt"),
  DPOP("dpop+jwt"),
}
