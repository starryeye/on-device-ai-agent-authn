package dev.starryeye.agentidentity.identity

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.starryeye.agentidentity.policy.PolicyProperties
import java.time.Clock
import java.util.Date
import org.springframework.stereotype.Component

/**
 * 자격증명(JWT)을 발급한다.
 *
 * `cnf.jkt` 로 에이전트 키에 묶으므로, 토큰만 탈취해도 키 없이는 쓸 수 없다.
 * `aud` 는 리소스 서버가 하나뿐인 지금도 넣는다 — 둘 이상이 되는 순간 A용 자격증명을 B에
 * 제시하는 혼동이 생기고, 그때 모양을 바꾸면 이미 발급된 토큰과 호환이 깨진다.
 *
 * 서명 키는 기동 시 생성한다. 키 관리는 이 사이클의 범위 밖이며, 서버를 재시작하면 이전
 * 자격증명은 무효가 된다.
 */
@Component
class CredentialIssuer(
    private val properties: PolicyProperties,
    private val clock: Clock,
) {

  private val signingKey: ECKey = ECKeyGenerator(Curve.P_256).keyID("server").generate()

  fun issue(identity: AgentIdentity): String {
    try {
      val now = clock.instant()
      val jwt =
          SignedJWT(
              JWSHeader.Builder(JWSAlgorithm.ES256).keyID(signingKey.keyID).build(),
              JWTClaimsSet.Builder()
                  .issuer("https://agent-identity.local")
                  .audience("https://agent-identity.local/resource")
                  .subject(identity.id)
                  .issueTime(Date.from(now))
                  .expirationTime(Date.from(now.plus(properties.credentialTtl)))
                  .claim("cnf", mapOf("jkt" to identity.jwkThumbprint))
                  .build())
      jwt.sign(ECDSASigner(signingKey))
      return jwt.serialize()
    } catch (e: Exception) {
      throw IllegalStateException("자격증명을 발급하지 못했다", e)
    }
  }
}
