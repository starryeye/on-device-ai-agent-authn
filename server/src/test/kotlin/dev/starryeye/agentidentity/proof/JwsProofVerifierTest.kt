package dev.starryeye.agentidentity.proof

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JwsProofVerifierTest {

  companion object {
    private val NOW: Instant = Instant.parse("2026-08-28T12:00:00Z")
    private const val URL = "https://example.test/agent/credential"

    private fun key(): ECKey =
        ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).generate()

    private fun proof(key: ECKey, typ: String, method: String, url: String, iat: Instant): String {
      val jwt =
          SignedJWT(
              JWSHeader.Builder(JWSAlgorithm.ES256)
                  .type(JOSEObjectType(typ))
                  .jwk(key.toPublicJWK())
                  .build(),
              JWTClaimsSet.Builder()
                  .claim("htm", method)
                  .claim("htu", url)
                  .jwtID(UUID.randomUUID().toString())
                  .issueTime(Date.from(iat))
                  .build())
      jwt.sign(ECDSASigner(key))
      return jwt.serialize()
    }

    private fun verifier(): JwsProofVerifier =
        JwsProofVerifier(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60))
  }

  @Test
  fun `올바른_DPoP_proof_는_지문을_돌려준다`() {
    val key = key()
    val jws = proof(key, "dpop+jwt", "POST", URL, NOW)

    assertThat(verifier().verify(jws, ProofType.DPOP, "POST", URL))
        .isEqualTo(key.computeThumbprint().toString())
  }

  @Test
  fun `등록_PoP_를_DPoP_자리에_내면_거절한다`() {
    val jws = proof(key(), "agent-reg-pop+jwt", "POST", URL, NOW)

    assertThat(verifier().verify(jws, ProofType.DPOP, "POST", URL)).isNull()
  }

  @Test
  fun `DPoP_를_등록_PoP_자리에_내면_거절한다`() {
    val jws = proof(key(), "dpop+jwt", "POST", URL, NOW)

    assertThat(verifier().verify(jws, ProofType.REGISTRATION, "POST", URL)).isNull()
  }

  @Test
  fun `같은_proof_를_두_번_쓰면_거절한다`() {
    val jws = proof(key(), "dpop+jwt", "POST", URL, NOW)
    val verifier = verifier()

    assertThat(verifier.verify(jws, ProofType.DPOP, "POST", URL)).isNotNull()
    assertThat(verifier.verify(jws, ProofType.DPOP, "POST", URL)).isNull()
  }

  @Test
  fun `시계_오차를_벗어난_proof_는_거절한다`() {
    val jws = proof(key(), "dpop+jwt", "POST", URL, NOW.minus(Duration.ofMinutes(10)))

    assertThat(verifier().verify(jws, ProofType.DPOP, "POST", URL)).isNull()
  }

  @Test
  fun `다른_URL_로_만든_proof_는_거절한다`() {
    val jws = proof(key(), "dpop+jwt", "POST", "https://example.test/other", NOW)

    assertThat(verifier().verify(jws, ProofType.DPOP, "POST", URL)).isNull()
  }
}
