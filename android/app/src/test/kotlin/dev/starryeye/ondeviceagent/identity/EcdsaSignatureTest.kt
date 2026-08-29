package dev.starryeye.ondeviceagent.identity

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EcdsaSignatureTest {

  @Test
  fun `DER 서명을 JOSE 64바이트로 바꾼다`() {
    val keyPair =
      KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()
    val der =
      Signature.getInstance("SHA256withECDSA").run {
        initSign(keyPair.private)
        update("payload".toByteArray())
        sign()
      }

    val jose = EcdsaSignature.derToJose(der)

    assertEquals(64, jose.size)
  }

  @Test
  fun `앞자리가 0인 값도 32바이트로 왼쪽 패딩한다`() {
    // DER 은 선행 0 을 생략하므로, 그대로 이어붙이면 64바이트가 안 되거나 자리가 밀린다.
    val r = BigInteger("1")
    val s = BigInteger("2")
    val der = EcdsaSignature.joseToDerForTest(r, s)

    val jose = EcdsaSignature.derToJose(der)

    assertEquals(64, jose.size)
    assertArrayEquals(ByteArray(31) + 1, jose.copyOfRange(0, 32))
    assertArrayEquals(ByteArray(31) + 2, jose.copyOfRange(32, 64))
  }
}
