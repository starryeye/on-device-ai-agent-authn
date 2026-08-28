package dev.starryeye.agentidentity.attestation;

import com.android.keyattestation.verifier.GoogleRevocationListKt;
import java.io.InputStream;
import java.net.URI;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 구글 루트 목록과 폐기 목록을 받아 캐시하고, 검증기를 조립한다.
 *
 * <p>{@code Clock} 빈은 이 클래스가 아니라 Task 4 에서 등록된다({@code
 * java.time.Clock.systemUTC()}). 그 전까지는 이 설정을 실제로 로드하는 스프링 컨텍스트가 없으므로
 * (이 태스크에는 {@code @SpringBootTest} 가 없다) 문제가 되지 않는다.
 */
@Configuration
public class AttestationConfiguration {

  private static final String ROOTS_URL = "https://android.googleapis.com/attestation/root";
  private static final Duration CACHE_TTL = Duration.ofHours(6);

  @Bean
  public TrustAnchorSource trustAnchorSource(Clock clock) {
    Cache<Set<TrustAnchor>> cache = new Cache<>(clock, CACHE_TTL);
    return () -> cache.get(AttestationConfiguration::fetchAnchors);
  }

  @Bean
  public RevocationSource revocationSource(Clock clock) {
    Cache<Set<String>> cache = new Cache<>(clock, CACHE_TTL);
    return () -> cache.get(GoogleRevocationListKt::getGoogleRevocationStatusFromWeb);
  }

  @Bean
  public AttestationVerifier attestationVerifier(
      TrustAnchorSource anchors, RevocationSource revocation, Clock clock) {
    return new AttestationVerifier(anchors, revocation, clock::instant);
  }

  /** 목록은 공개된 JSON 배열(PEM 문자열들)이다. */
  private static Set<TrustAnchor> fetchAnchors() {
    try (InputStream in = URI.create(ROOTS_URL).toURL().openStream()) {
      String body = new String(in.readAllBytes());
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      Set<TrustAnchor> anchors = new HashSet<>();
      for (String pem : body.split("\"")) {
        String candidate = pem.replace("\\n", "\n");
        if (!candidate.contains("BEGIN CERTIFICATE")) continue;
        X509Certificate certificate =
            (X509Certificate)
                factory.generateCertificate(new java.io.ByteArrayInputStream(candidate.getBytes()));
        anchors.add(new TrustAnchor(certificate, null));
      }
      if (anchors.isEmpty()) throw new IllegalStateException("루트 목록이 비었다");
      return anchors;
    } catch (Exception e) {
      // 열어두면 CRL·루트 검사를 우회할 수 있다. 조회 실패는 등록 실패다.
      throw new IllegalStateException("attestation 루트 목록을 받지 못했다", e);
    }
  }

  /** 아주 단순한 TTL 캐시. 실패는 캐시하지 않는다. */
  private static final class Cache<T> {
    private final AtomicReference<T> value = new AtomicReference<>();
    private final AtomicReference<Instant> fetchedAt = new AtomicReference<>();
    private final Clock clock;
    private final Duration ttl;

    Cache(Clock clock, Duration ttl) {
      this.clock = clock;
      this.ttl = ttl;
    }

    T get(java.util.function.Supplier<T> loader) {
      Instant at = fetchedAt.get();
      T cached = value.get();
      if (cached != null && at != null && Duration.between(at, clock.instant()).compareTo(ttl) < 0) {
        return cached;
      }
      T loaded = loader.get();
      value.set(loaded);
      fetchedAt.set(clock.instant());
      return loaded;
    }
  }
}
