package dev.starryeye.ondeviceagent.identity

import java.math.BigInteger
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

/**
 * 등록 PoP 와 런타임 DPoP 를 만든다. **`typ` 이 다르다** — 같은 형태를 쓰되 서로의 자리에서
 * 통과하면 안 되기 때문이다(서버의 ProofType 과 짝을 이룬다).
 */
class JwsProofSigner(private val keys: AgentKeyStore) {

  fun registrationPop(url: String, challenge: String): String =
    sign("agent-reg-pop+jwt", "POST", url, mapOf("challenge" to challenge))

  fun dpop(method: String, url: String): String = sign("dpop+jwt", method, url, emptyMap())

  private fun sign(
    typ: String,
    method: String,
    url: String,
    extraClaims: Map<String, String>,
  ): String {
    val key = keys.publicKey()
    val header =
      JSONObject()
        .put("alg", "ES256")
        .put("typ", typ)
        .put("jwk", publicJwk(key))
        .toString()
    val payload =
      JSONObject()
        .put("htm", method)
        .put("htu", url)
        .put("jti", UUID.randomUUID().toString())
        .put("iat", System.currentTimeMillis() / 1000)
        .apply { extraClaims.forEach { (name, value) -> put(name, value) } }
        .toString()

    val signingInput = "${encode(header.toByteArray())}.${encode(payload.toByteArray())}"
    return "$signingInput.${encode(keys.sign(signingInput.toByteArray()))}"
  }

  private fun publicJwk(key: ECPublicKey): JSONObject =
    JSONObject()
      .put("kty", "EC")
      .put("crv", "P-256")
      .put("x", encode(coordinate(key.w.affineX)))
      .put("y", encode(coordinate(key.w.affineY)))

  private fun coordinate(value: BigInteger): ByteArray {
    val bytes = value.toByteArray()
    val trimmed = if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size) else bytes
    return ByteArray(32 - trimmed.size) + trimmed
  }

  private fun encode(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
