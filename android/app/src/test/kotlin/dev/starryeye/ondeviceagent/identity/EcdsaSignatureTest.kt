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

  @Test
  fun `최상위 비트가 선 값은 DER 부호 바이트를 벗겨내고 오른쪽 정렬한다`() {
    // R·S 의 최상위 비트가 1이면 BigInteger#toByteArray 는 음수로 오인되지 않도록 앞에
    // 부호용 0x00 을 붙여 33바이트를 낸다. derToJose 는 그 0x00 을 걷어내고 남은 32바이트를
    // "뒤에서부터" 오른쪽 정렬해야 한다 — 앞의 32바이트를 취하거나(0x00이 그대로 섞여
    // 들어옴) 아예 자르지 않는(64바이트가 안 됨) 잘못된 구현은 이 값에서 걸린다.
    val rBytes = ByteArray(32).also { it[0] = 0x80.toByte() }
    val sBytes = ByteArray(32) { 0xff.toByte() }
    val r = BigInteger(1, rBytes)
    val s = BigInteger(1, sBytes)
    val der = EcdsaSignature.joseToDerForTest(r, s)

    val jose = EcdsaSignature.derToJose(der)

    assertEquals(64, jose.size)
    assertArrayEquals(rBytes, jose.copyOfRange(0, 32))
    assertArrayEquals(sBytes, jose.copyOfRange(32, 64))
  }
}
