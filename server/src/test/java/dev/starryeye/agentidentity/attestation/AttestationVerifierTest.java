package dev.starryeye.agentidentity.attestation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    assertThat(result).isInstanceOf(AttestationResult.Rejected.class);
  }

  @Test
  void 유효기간이_지난_뒤에는_거절한다() throws Exception {
    // RKP 중간 인증서는 2026-09-03 에 만료된다. 그 뒤 시각으로 보면 통과하면 안 된다.
    AttestationResult result =
        verifierAt(Instant.parse("2026-10-01T00:00:00Z"), Set::of)
            .verify(fixtureChain(), probeChallenge());

    assertThat(result).isInstanceOf(AttestationResult.Rejected.class);
  }

  @Test
  void 체인의_인증서가_폐기목록에_있으면_거절한다() throws Exception {
    List<X509Certificate> chain = fixtureChain();
    String revokedSerial = chain.get(1).getSerialNumber().toString(16);

    AttestationResult result =
        verifierAt(VALID_AT, () -> Set.of(revokedSerial)).verify(chain, probeChallenge());

    assertThat(result).isInstanceOf(AttestationResult.Rejected.class);
  }

  @Test
  void 알려지지_않은_루트는_거절한다() throws Exception {
    AttestationVerifier verifier =
        new AttestationVerifier(Set::of, Set::of, () -> VALID_AT); // 앵커 없음

    assertThat(verifier.verify(fixtureChain(), probeChallenge()))
        .isInstanceOf(AttestationResult.Rejected.class);
  }
}
