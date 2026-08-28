package dev.starryeye.ondeviceagent.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 설계를 확정하기 전에 **실기기의 attestation 체인이 실제로 어떻게 생겼는지** 뽑아 본다.
 *
 * 서버의 체인 검증을 어떤 가정 위에 짤지가 여기서 갈린다 — 루트가 무엇인지, 인증서가 몇 장인지,
 * 유효기간이 어떤지, 그리고 어떤 필드가 하드웨어 강제이고 어떤 것이 소프트웨어 강제인지.
 * 문서로 추측하지 않고 재서 정한다.
 *
 * 체인을 PEM으로 떨궈 호스트에서 openssl로 분석한다. ASN.1 파싱을 여기서 하지 않는 이유는,
 * 그 파싱이야말로 서버가 할 일이고 여기서 두 벌로 만들 이유가 없기 때문이다.
 */
@RunWith(AndroidJUnit4::class)
class AttestationProbeTest {

  @Test
  fun attestation_체인을_덤프한다() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val outDir = context.getExternalFilesDir(null) ?: context.filesDir
    val report = StringBuilder()

    // 서버가 줄 nonce 를 흉내 낸다. 실제 등록에서는 서버가 발급한다.
    val challenge = ByteArray(32) { it.toByte() }

    for (strongBox in listOf(true, false)) {
      val alias = "probe-${if (strongBox) "strongbox" else "tee"}"
      report.appendLine("=== StrongBox 요청=$strongBox alias=$alias ===")
      try {
        val generator =
          KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
          KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        )
        generator.generateKeyPair()

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val chain = keyStore.getCertificateChain(alias)
        report.appendLine("체인 길이: ${chain.size}")

        val pem = StringBuilder()
        chain.forEachIndexed { index, certificate ->
          val x509 = certificate as X509Certificate
          report.appendLine("[$index] subject=${x509.subjectX500Principal}")
          report.appendLine("     issuer =${x509.issuerX500Principal}")
          report.appendLine("     serial =${x509.serialNumber.toString(16)}")
          report.appendLine("     유효   =${x509.notBefore} ~ ${x509.notAfter}")
          report.appendLine("     확장   =${x509.criticalExtensionOIDs.orEmpty() + x509.nonCriticalExtensionOIDs.orEmpty()}")
          pem.appendLine("-----BEGIN CERTIFICATE-----")
          pem.appendLine(
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(x509.encoded)
          )
          pem.appendLine("-----END CERTIFICATE-----")
        }
        File(outDir, "attestation-$alias.pem").writeText(pem.toString())
        report.appendLine("PEM 기록: attestation-$alias.pem")
      } catch (e: StrongBoxUnavailableException) {
        report.appendLine("StrongBox 없음: ${e.message}")
      } catch (e: Throwable) {
        report.appendLine("실패: ${e::class.simpleName}: ${e.message}")
      }
      report.appendLine()
    }

    File(outDir, "attestation-report.txt").writeText(report.toString())
    println(report.toString())
  }
}
