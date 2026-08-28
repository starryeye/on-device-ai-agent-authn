package dev.starryeye.agentidentity.attestation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.stream.Collectors;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.junit.jupiter.api.Test;

/**
 * {@link AttestationConfiguration#parseRootsResponse} 가 구글 루트 목록 응답을 손실 없이
 * 파싱하는지 검증한다.
 *
 * <p>{@code fixtures/attestation-roots-response.json} 은 2026-08-28 에
 * {@code https://android.googleapis.com/attestation/root} 에서 실제로 받은 응답 본문 그대로다.
 * 이 목록이 전체 신뢰 앵커 집합을 결정하므로, 파서가 일부만 조용히 놓치면 정상 기기가
 * 거절당한다 — 그런데 {@code anchors.isEmpty()} 가드는 "전부 실패"만 잡아낸다. 그래서 이
 * 테스트는 실제 응답에 들어 있는 인증서 개수와 신원을 정확히 못박는다.
 */
class AttestationConfigurationTest {

  @Test
  void 구글_루트_응답의_인증서_두_개를_모두_파싱한다() throws Exception {
    Set<TrustAnchor> anchors;
    try (InputStream in =
        getClass().getResourceAsStream("/fixtures/attestation-roots-response.json")) {
      anchors = AttestationConfiguration.parseRootsResponse(in);
    }

    assertThat(anchors).hasSize(2);

    // JDK 의 X500Principal.getName() 은 serialNumber(2.5.4.5) 처럼 흔치 않은 OID 를 사람이
    // 읽을 수 있는 이름으로 바꿔주지 않고 "2.5.4.5=#<hex>" 로 내보낸다. 그래서 실제 값이
    // 맞는지 보려면 BouncyCastle 로 해당 RDN 을 직접 뽑아 디코딩해야 한다.
    Set<String> serialNumberAttributes =
        anchors.stream()
            .map(anchor -> subjectAttribute(anchor.getTrustedCert(), BCStyle.SERIALNUMBER))
            .collect(Collectors.toSet());
    Set<String> commonNames =
        anchors.stream()
            .map(anchor -> subjectAttribute(anchor.getTrustedCert(), BCStyle.CN))
            .collect(Collectors.toSet());

    // 레거시 루트(subject serialNumber=f92009e853b6b045)와 CN=Key Attestation CA1, 둘 다
    // 있어야 한다.
    assertThat(serialNumberAttributes).contains("f92009e853b6b045");
    assertThat(commonNames).contains("Key Attestation CA1");
  }

  private static String subjectAttribute(
      X509Certificate certificate, org.bouncycastle.asn1.ASN1ObjectIdentifier attributeType) {
    try {
      X500Name subject = new JcaX509CertificateHolder(certificate).getSubject();
      RDN[] rdns = subject.getRDNs(attributeType);
      if (rdns.length == 0) {
        return null;
      }
      return IETFUtils.valueToString(rdns[0].getFirst().getValue());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
