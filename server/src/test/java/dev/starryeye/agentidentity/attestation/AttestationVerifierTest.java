package dev.starryeye.agentidentity.attestation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

/**
 * 실기기에서 뽑은 체인으로 검증기를 시험한다.
 *
 * 시각을 고정하는 것이 핵심이다. RKP 중간 인증서의 유효기간이 13일이라, 시스템 시각으로
 * 검증하면 이 테스트는 2주도 못 가 저절로 실패한다.
 */
class AttestationVerifierTest {

  /** 픽스처 체인이 유효했던 시점. 2026-08-21 ~ 09-03 창 안이다. */
  private static final Instant VALID_AT = Instant.parse("2026-08-28T12:00:00Z");

  /** 우리가 기기에 넣었던 challenge (AttestationProbeTest 가 0..31 을 넣는다). */
  private static byte[] probeChallenge() {
    byte[] challenge = new byte[32];
    for (int i = 0; i < 32; i++) challenge[i] = (byte) i;
    return challenge;
  }

  private static List<X509Certificate> fixtureChain() throws Exception {
    try (InputStream in =
        AttestationVerifierTest.class.getResourceAsStream(
            "/fixtures/attestation-chain-a36.pem")) {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      List<X509Certificate> chain = new ArrayList<>();
      for (var certificate : factory.generateCertificates(in)) {
        chain.add((X509Certificate) certificate);
      }
      return chain;
    }
  }

  /** 체인의 루트(자기서명)를 앵커로 쓴다. 루트가 공개 목록에 있는지는 TrustAnchorSource 의 몫이다. */
  private static TrustAnchorSource anchorsFromFixture() throws Exception {
    List<X509Certificate> chain = fixtureChain();
    TrustAnchor anchor = new TrustAnchor(chain.get(chain.size() - 1), null);
    return () -> Set.of(anchor);
  }

  private static AttestationVerifier verifierAt(Instant now, RevocationSource revocation)
      throws Exception {
    return new AttestationVerifier(anchorsFromFixture(), revocation, () -> now);
  }

  /**
   * 픽스처 체인과 아무 관계 없는 자기서명 인증서. "신뢰 앵커가 비어 있다"(설정 오류, 예외 경로)와
   * "신뢰 앵커는 있지만 이 체인이 그리로 이어지지 않는다"(진짜 경로 검증 실패,
   * {@code VerificationResult} 경로)를 구분하려면 앵커 집합이 비어 있으면 안 된다 — 그래서
   * 이 인증서를 만들어 앵커로 쓴다.
   */
  private static X509Certificate unrelatedSelfSignedCertificate() throws Exception {
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
    keyPairGenerator.initialize(256);
    KeyPair keyPair = keyPairGenerator.generateKeyPair();

    X500Name subject = new X500Name("CN=unrelated-test-root");
    Instant now = Instant.now();
    JcaX509v3CertificateBuilder builder =
        new JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now.toEpochMilli()),
            Date.from(now.minus(Duration.ofDays(1))),
            Date.from(now.plus(Duration.ofDays(3650))),
            subject,
            keyPair.getPublic());
    ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.getPrivate());
    X509CertificateHolder holder = builder.build(signer);
    return new JcaX509CertificateConverter().getCertificate(holder);
  }

  @Test
  void 유효한_체인은_검증되고_기기_속성을_돌려준다() throws Exception {
    AttestationResult result =
        verifierAt(VALID_AT, Set::of).verify(fixtureChain(), probeChallenge());

    assertThat(result).isInstanceOf(AttestationResult.Verified.class);
    AttestationResult.Verified verified = (AttestationResult.Verified) result;
    assertThat(verified.securityLevel()).isEqualTo("TRUSTED_ENVIRONMENT");
    assertThat(verified.packageName()).isEqualTo("dev.starryeye.ondeviceagent");
    assertThat(verified.deviceLocked()).isTrue();
  }

  @Test
  void challenge_가_다르면_거절한다() throws Exception {
    AttestationResult result =
        verifierAt(VALID_AT, Set::of).verify(fixtureChain(), new byte[32]);

    // VerificationResult.ChallengeMismatch 를 그대로 detail 로 옮긴 것이다. 검증기 안쪽 구현이
    // 바뀌어 다른 이유로 거절하게 되면 이 값이 달라져서 테스트가 실패해야 한다 — 타입만 보면
    // 아무 예외나 던져도 통과해 버린다.
    assertThat(result).isInstanceOf(AttestationResult.Rejected.class);
    assertThat(((AttestationResult.Rejected) result).detail()).isEqualTo("ChallengeMismatch");
  }

  @Test
  void 유효기간이_지난_뒤에는_거절한다() throws Exception {
    // RKP 중간 인증서는 2026-09-03 에 만료된다. 그 뒤 시각으로 보면 통과하면 안 된다.
    AttestationResult result =
        verifierAt(Instant.parse("2026-10-01T00:00:00Z"), Set::of)
            .verify(fixtureChain(), probeChallenge());

    // 경로 검증(유효기간 포함)은 라이브러리 안에서 CertPathValidatorException 으로 잡혀
    // VerificationResult.PathValidationFailure 로 나온다 — 예외가 우리 쪽까지 튀지 않는다.
    assertThat(result).isInstanceOf(AttestationResult.Rejected.class);
    assertThat(((AttestationResult.Rejected) result).detail()).isEqualTo("PathValidationFailure");
  }

  @Test
  void 체인의_인증서가_폐기목록에_있으면_거절한다() throws Exception {
    List<X509Certificate> chain = fixtureChain();
    String revokedSerial = chain.get(1).getSerialNumber().toString(16);

    AttestationResult result =
        verifierAt(VALID_AT, () -> Set.of(revokedSerial)).verify(chain, probeChallenge());

    // RevocationChecker 도 CertPathValidatorException(BasicReason.REVOKED) 을 던지고, 이 역시
    // 경로 검증 단계에서 잡혀 PathValidationFailure 가 된다.
    assertThat(result).isInstanceOf(AttestationResult.Rejected.class);
    assertThat(((AttestationResult.Rejected) result).detail()).isEqualTo("PathValidationFailure");
  }

  @Test
  void 신뢰_앵커가_비어있으면_설정_오류로_거절한다() throws Exception {
    AttestationVerifier verifier =
        new AttestationVerifier(Set::of, Set::of, () -> VALID_AT); // 앵커 없음

    // 앵커 집합이 비면 PKIXParameters 생성자가 InvalidAlgorithmParameterException 을 던진다 —
    // 이건 체인 문제가 아니라 배포 설정 문제라서 "configuration error" 로 구분해 거절한다.
    AttestationResult result = verifier.verify(fixtureChain(), probeChallenge());
    assertThat(result).isInstanceOf(AttestationResult.Rejected.class);
    assertThat(((AttestationResult.Rejected) result).detail()).contains("configuration error");
  }

  @Test
  void 앵커와_무관한_체인은_경로_검증_실패로_거절한다() throws Exception {
    // 신뢰 앵커 집합 자체는 비어 있지 않다(그러니 PKIXParameters 는 정상적으로 만들어진다) —
    // 다만 그 앵커가 이 체인과는 아무 관계가 없다. 이러면 진짜 CertPathValidator 가 경로를
    // 못 찾아 PathValidationFailure 를 돌려주는, 위 "앵커가 비었다" 테스트와는 다른 코드
    // 경로를 탄다.
    TrustAnchor unrelated = new TrustAnchor(unrelatedSelfSignedCertificate(), null);
    AttestationVerifier verifier =
        new AttestationVerifier(() -> Set.of(unrelated), Set::of, () -> VALID_AT);

    AttestationResult result = verifier.verify(fixtureChain(), probeChallenge());
    assertThat(result).isInstanceOf(AttestationResult.Rejected.class);
    assertThat(((AttestationResult.Rejected) result).detail()).isEqualTo("PathValidationFailure");
  }
}
