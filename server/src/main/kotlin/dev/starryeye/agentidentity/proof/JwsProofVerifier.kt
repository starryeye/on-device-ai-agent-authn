package dev.starryeye.agentidentity.proof

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.SignedJWT
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * JWS 형태의 소유 증명을 검증한다. 등록 PoP 와 런타임 DPoP 가 **파싱·서명검증을 공유하고
 * 수용 조건은 분리**한다.
 *
 * `jti` 를 기억해 재생을 막는다. 허용 창의 두 배 동안 보관하며, 그 뒤에는 `iat` 검사가
 * 재생을 막으므로 지워도 된다.
 *
 * 스프링이 `Duration` 을 주입하지 못하므로 빈 정의는 `ServerApplication` 에서 한다
 * (`@Component` 를 붙이면 빈이 두 번 만들어진다).
 */
class JwsProofVerifier(
    private val clock: Clock,
    private val skew: Duration = Duration.ofSeconds(60),
) {

  private val seenJti = ConcurrentHashMap<String, Instant>()

  /** 통과하면 서명한 키의 RFC 7638 지문을 돌려준다. */
  fun verify(jws: String, expected: ProofType, method: String, url: String): String? {
    return try {
      val jwt = SignedJWT.parse(jws)

      val type = jwt.header.type
      if (type == null || expected.typ != type.type) {
        return null
      }

      val jwk = jwt.header.jwk as? ECKey ?: return null
      if (!jwt.verify(ECDSAVerifier(jwk))) {
        return null
      }

      val claims = jwt.getJWTClaimsSet()
      if (method != claims.getStringClaim("htm") || url != claims.getStringClaim("htu")) {
        return null
      }

      val issuedAt = claims.issueTime?.toInstant() ?: return null
      val now = clock.instant()
      if (Duration.between(issuedAt, now).abs() > skew) {
        return null
      }

      val jti = claims.getJWTID() ?: return null
      evictExpired(now)
      if (seenJti.putIfAbsent(jti, now) != null) {
        return null
      }

      jwk.computeThumbprint().toString()
    } catch (e: Exception) {
      null
    }
  }

  private fun evictExpired(now: Instant) {
    val cutoff = now.minus(skew.multipliedBy(2))
    seenJti.entries.removeIf { it.value.isBefore(cutoff) }
  }
}
