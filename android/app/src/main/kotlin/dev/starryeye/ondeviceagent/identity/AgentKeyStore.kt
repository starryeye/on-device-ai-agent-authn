package dev.starryeye.ondeviceagent.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * 에이전트의 하드웨어 키. 개인키는 어떤 경로로도 앱에 노출되지 않는다.
 *
 * StrongBox 를 먼저 시도하고 없으면 TEE 로 내려간다. **어느 쪽인지 클라이언트가 주장하지
 * 않는다** — attestation 이 하드웨어 서명으로 알려 주고, 판단은 서버가 한다.
 */
class AgentKeyStore(private val alias: String = "agent-identity-key") {

  private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

  fun hasKey(): Boolean = keyStore.containsAlias(alias)

  /** [challenge] 를 attestation challenge 로 넣어 키를 만들고 체인을 돌려준다. */
  fun createKey(challenge: ByteArray): List<X509Certificate> {
    if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    for (strongBox in listOf(true, false)) {
      try {
        generate(challenge, strongBox)
        return chain()
      } catch (e: StrongBoxUnavailableException) {
        if (!strongBox) throw e
      }
    }
    error("키를 만들지 못했다")
  }

  fun chain(): List<X509Certificate> =
    keyStore.getCertificateChain(alias).map { it as X509Certificate }

  fun publicKey(): ECPublicKey = chain().first().publicKey as ECPublicKey

  /** JOSE 형식(R‖S)으로 서명한다. */
  fun sign(payload: ByteArray): ByteArray {
    val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
    val der =
      Signature.getInstance("SHA256withECDSA").run {
        initSign(entry.privateKey)
        update(payload)
        sign()
      }
    return EcdsaSignature.derToJose(der)
  }

  private fun generate(challenge: ByteArray, strongBox: Boolean) {
    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
      initialize(
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
          .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
          .setDigests(KeyProperties.DIGEST_SHA256)
          .setAttestationChallenge(challenge)
          .apply { if (strongBox) setIsStrongBoxBacked(true) }
          .build()
      )
      generateKeyPair()
    }
  }
}
