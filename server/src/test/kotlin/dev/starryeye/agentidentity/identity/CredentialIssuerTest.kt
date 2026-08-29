package dev.starryeye.agentidentity.identity

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import dev.starryeye.agentidentity.policy.PolicyProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `CredentialIssuer` 는 스프링 없이도 직접 만들 수 있다 — `PolicyProperties` 와 `Clock` 만
 * 생성자로 받는 평범한 클래스이기 때문이다. 서명 키가 클래스 내부에서만 생성돼 바깥으로
 * 나가지 않으므로, 서명을 검증하려면 [CredentialIssuer.signingPublicJwk] 라는 테스트
 * 전용 이음매(`internal`)로 공개키만 얻는다 — `ChallengeStore.issuedCount()` 가 이미 세운
 * 선례를 따른다. 개인키는 여전히 클래스 밖으로 나가지 않는다.
 */
class CredentialIssuerTest {

  companion object {
    private val NOW: Instant = Instant.parse("2026-08-28T12:00:00Z")

    private fun identityWith(thumbprint: String): AgentIdentity =
        AgentIdentity(
            "agent-1",
            thumbprint,
            "galaxy-personal-agent",
            "dev.starryeye.ondeviceagent",
            "TRUSTED_ENVIRONMENT",
            "VERIFIED",
            true,
            NOW)
  }

  @Test
  fun `발급된_자격증명의_cnf_jkt_는_에이전트_키_지문과_같다`() {
    val issuer = CredentialIssuer(PolicyProperties(), Clock.fixed(NOW, ZoneOffset.UTC))
    val identity = identityWith("thumbprint-abc")

    val claims = SignedJWT.parse(issuer.issue(identity)).jwtClaimsSet

    @Suppress("UNCHECKED_CAST") val cnf = claims.getClaim("cnf") as Map<String, Any>
    // cnf.jkt 가 신원의 지문과 다르면, 토큰을 탈취한 사람이 자기 키로도 그 토큰을 쓸 수
    // 있다는 뜻이 된다 — 이 바인딩이 이 클레임이 존재하는 유일한 이유다.
    assertThat(cnf["jkt"]).isEqualTo("thumbprint-abc")
  }

  @Test
  fun `발급된_자격증명은_iss_aud_sub_exp_를_담는다`() {
    val properties = PolicyProperties().apply { credentialTtl = Duration.ofMinutes(15) }
    val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    val issuer = CredentialIssuer(properties, clock)
    val identity = identityWith("thumbprint-abc")

    val claims = SignedJWT.parse(issuer.issue(identity)).jwtClaimsSet

    assertThat(claims.issuer).isEqualTo("https://agent-identity.local")
    assertThat(claims.audience).containsExactly("https://agent-identity.local/resource")
    assertThat(claims.subject).isEqualTo(identity.id)
    assertThat(claims.issueTime.toInstant()).isEqualTo(NOW)
    assertThat(claims.expirationTime.toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(15)))
  }

  @Test
  fun `발급된_자격증명은_서버_서명키로_서명돼_있다`() {
    val issuer = CredentialIssuer(PolicyProperties(), Clock.fixed(NOW, ZoneOffset.UTC))
    val jwt = SignedJWT.parse(issuer.issue(identityWith("thumbprint-abc")))

    assertThat(jwt.verify(ECDSAVerifier(issuer.signingPublicJwk()))).isTrue()
  }
}
