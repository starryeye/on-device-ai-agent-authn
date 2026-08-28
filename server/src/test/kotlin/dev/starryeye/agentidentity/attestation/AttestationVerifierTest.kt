package dev.starryeye.agentidentity.attestation

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Date
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.jupiter.api.Test

/**
 * 실기기에서 뽑은 체인으로 검증기를 시험한다.
 *
 * 시각을 고정하는 것이 핵심이다. RKP 중간 인증서의 유효기간이 13일이라, 시스템 시각으로
 * 검증하면 이 테스트는 2주도 못 가 저절로 실패한다.
 */
class AttestationVerifierTest {

  companion object {
    /** 픽스처 체인이 유효했던 시점. 2026-08-21 ~ 09-03 창 안이다. */
    private val VALID_AT: Instant = Instant.parse("2026-08-28T12:00:00Z")

    /** 우리가 기기에 넣었던 challenge (AttestationProbeTest 가 0..31 을 넣는다). */
    private fun probeChallenge(): ByteArray = ByteArray(32) { i -> i.toByte() }

    private fun fixtureChain(): List<X509Certificate> {
      AttestationVerifierTest::class.java.getResourceAsStream("/fixtures/attestation-chain-a36.pem")
          .use { input ->
            val factory = CertificateFactory.getInstance("X.509")
            return factory.generateCertificates(input).map { it as X509Certificate }
          }
    }

    /** 체인의 루트(자기서명)를 앵커로 쓴다. 루트가 공개 목록에 있는지는 TrustAnchorSource 의 몫이다. */
    private fun anchorsFromFixture(): TrustAnchorSource {
      val chain = fixtureChain()
      val anchor = TrustAnchor(chain[chain.size - 1], null)
      return TrustAnchorSource { setOf(anchor) }
    }

    private fun verifierAt(now: Instant, revocation: RevocationSource): AttestationVerifier =
        AttestationVerifier(anchorsFromFixture(), revocation) { now }

    /**
     * 픽스처 체인과 아무 관계 없는 자기서명 인증서. "신뢰 앵커가 비어 있다"(설정 오류, 예외 경로)와
     * "신뢰 앵커는 있지만 이 체인이 그리로 이어지지 않는다"(진짜 경로 검증 실패,
     * `VerificationResult` 경로)를 구분하려면 앵커 집합이 비어 있으면 안 된다 — 그래서
     * 이 인증서를 만들어 앵커로 쓴다.
     */
    private fun unrelatedSelfSignedCertificate(): X509Certificate {
      val keyPairGenerator = KeyPairGenerator.getInstance("EC")
      keyPairGenerator.initialize(256)
      val keyPair = keyPairGenerator.generateKeyPair()

      val subject = X500Name("CN=unrelated-test-root")
      val now = Instant.now()
      val builder =
          JcaX509v3CertificateBuilder(
              subject,
              BigInteger.valueOf(now.toEpochMilli()),
              Date.from(now.minus(Duration.ofDays(1))),
              Date.from(now.plus(Duration.ofDays(3650))),
              subject,
              keyPair.public)
      val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
      val holder = builder.build(signer)
      return JcaX509CertificateConverter().getCertificate(holder)
    }
  }

  @Test
  fun `유효한_체인은_검증되고_기기_속성을_돌려준다`() {
    val result = verifierAt(VALID_AT) { emptySet() }.verify(fixtureChain(), probeChallenge())

    assertThat(result).isInstanceOf(AttestationResult.Verified::class.java)
    val verified = result as AttestationResult.Verified
    assertThat(verified.securityLevel).isEqualTo("TRUSTED_ENVIRONMENT")
    assertThat(verified.packageName).isEqualTo("dev.starryeye.ondeviceagent")
    assertThat(verified.deviceLocked).isTrue()
  }

  @Test
  fun `challenge_가_다르면_거절한다`() {
    val result = verifierAt(VALID_AT) { emptySet() }.verify(fixtureChain(), ByteArray(32))

    // VerificationResult.ChallengeMismatch 를 그대로 detail 로 옮긴 것이다. 검증기 안쪽 구현이
    // 바뀌어 다른 이유로 거절하게 되면 이 값이 달라져서 테스트가 실패해야 한다 — 타입만 보면
    // 아무 예외나 던져도 통과해 버린다.
    assertThat(result).isInstanceOf(AttestationResult.Rejected::class.java)
    assertThat((result as AttestationResult.Rejected).detail).isEqualTo("ChallengeMismatch")
  }

  @Test
  fun `유효기간이_지난_뒤에는_거절한다`() {
    // RKP 중간 인증서는 2026-09-03 에 만료된다. 그 뒤 시각으로 보면 통과하면 안 된다.
    val result =
        verifierAt(Instant.parse("2026-10-01T00:00:00Z")) { emptySet() }
            .verify(fixtureChain(), probeChallenge())

    // 경로 검증(유효기간 포함)은 라이브러리 안에서 CertPathValidatorException 으로 잡혀
    // VerificationResult.PathValidationFailure 로 나온다 — 예외가 우리 쪽까지 튀지 않는다.
    assertThat(result).isInstanceOf(AttestationResult.Rejected::class.java)
    assertThat((result as AttestationResult.Rejected).detail).isEqualTo("PathValidationFailure")
  }

  @Test
  fun `체인의_인증서가_폐기목록에_있으면_거절한다`() {
    val chain = fixtureChain()
    val revokedSerial = chain[1].serialNumber.toString(16)

    val result = verifierAt(VALID_AT) { setOf(revokedSerial) }.verify(chain, probeChallenge())

    // RevocationChecker 도 CertPathValidatorException(BasicReason.REVOKED) 을 던지고, 이 역시
    // 경로 검증 단계에서 잡혀 PathValidationFailure 가 된다.
    assertThat(result).isInstanceOf(AttestationResult.Rejected::class.java)
    assertThat((result as AttestationResult.Rejected).detail).isEqualTo("PathValidationFailure")
  }

  @Test
  fun `신뢰_앵커가_비어있으면_설정_오류로_거절한다`() {
    val verifier =
        AttestationVerifier({ emptySet() }, { emptySet() }) { VALID_AT } // 앵커 없음

    // 앵커 집합이 비면 PKIXParameters 생성자가 InvalidAlgorithmParameterException 을 던진다 —
    // 이건 체인 문제가 아니라 배포 설정 문제라서 "configuration error" 로 구분해 거절한다.
    val result = verifier.verify(fixtureChain(), probeChallenge())
    assertThat(result).isInstanceOf(AttestationResult.Rejected::class.java)
    assertThat((result as AttestationResult.Rejected).detail).contains("configuration error")
  }

  @Test
  fun `앵커와_무관한_체인은_경로_검증_실패로_거절한다`() {
    // 신뢰 앵커 집합 자체는 비어 있지 않다(그러니 PKIXParameters 는 정상적으로 만들어진다) —
    // 다만 그 앵커가 이 체인과는 아무 관계가 없다. 이러면 진짜 CertPathValidator 가 경로를
    // 못 찾아 PathValidationFailure 를 돌려주는, 위 "앵커가 비었다" 테스트와는 다른 코드
    // 경로를 탄다.
    val unrelated = TrustAnchor(unrelatedSelfSignedCertificate(), null)
    val verifier = AttestationVerifier({ setOf(unrelated) }, { emptySet() }) { VALID_AT }

    val result = verifier.verify(fixtureChain(), probeChallenge())
    assertThat(result).isInstanceOf(AttestationResult.Rejected::class.java)
    assertThat((result as AttestationResult.Rejected).detail).isEqualTo("PathValidationFailure")
  }
}
