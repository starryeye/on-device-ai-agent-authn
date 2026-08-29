package dev.starryeye.ondeviceagent.identity

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * ECDSA 서명 형식 변환.
 *
 * JCA 는 DER(SEQUENCE{r,s})로 서명을 내주는데 JOSE(ES256)는 고정 길이 `R‖S` 를 요구한다.
 * DER 은 선행 0 을 생략하므로 단순히 이어붙이면 길이가 어긋난다. 여기서 틀리면 서버가 서명을
 * 거부하는데, 증상이 "서명 불일치"뿐이라 원인을 찾기 어렵다.
 */
object EcdsaSignature {

  private const val COORDINATE_BYTES = 32

  fun derToJose(der: ByteArray): ByteArray {
    var offset = 2 // SEQUENCE 태그와 길이
    if (der[1].toInt() and 0x80 != 0) offset += der[1].toInt() and 0x7f

    require(der[offset].toInt() == 0x02) { "DER 형식이 아니다" }
    val rLength = der[offset + 1].toInt()
    val r = BigInteger(der.copyOfRange(offset + 2, offset + 2 + rLength))

    val sOffset = offset + 2 + rLength
    require(der[sOffset].toInt() == 0x02) { "DER 형식이 아니다" }
    val sLength = der[sOffset + 1].toInt()
    val s = BigInteger(der.copyOfRange(sOffset + 2, sOffset + 2 + sLength))

    return toFixed(r) + toFixed(s)
  }

  private fun toFixed(value: BigInteger): ByteArray {
    val bytes = value.toByteArray()
    val trimmed = if (bytes.size > COORDINATE_BYTES) bytes.copyOfRange(bytes.size - COORDINATE_BYTES, bytes.size) else bytes
    return ByteArray(COORDINATE_BYTES - trimmed.size) + trimmed
  }

  /** 테스트가 알려진 r·s 로 DER 을 만들기 위한 도우미. */
  internal fun joseToDerForTest(r: BigInteger, s: BigInteger): ByteArray {
    fun integer(value: BigInteger): ByteArray {
      val bytes = value.toByteArray()
      return byteArrayOf(0x02, bytes.size.toByte()) + bytes
    }
    val body = integer(r) + integer(s)
    return ByteArrayOutputStream().apply {
      write(0x30); write(body.size); write(body)
    }.toByteArray()
  }
}
