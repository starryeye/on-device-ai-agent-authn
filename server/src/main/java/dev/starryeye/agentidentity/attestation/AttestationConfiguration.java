package dev.starryeye.agentidentity.attestation;

import com.android.keyattestation.verifier.GoogleRevocationListKt;
import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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

/** 구글 루트 목록과 폐기 목록을 받아 캐시하고, 검증기를 조립한다.
 *
 * <p>{@code Clock} 빈은 이 클래스가 아니라 Task 4 에서 등록된다({@code
 * java.time.Clock.systemUTC()}). 그 전까지는 이 설정을 실제로 로드하는 스프링 컨텍스트가 없으므로
 * (이 태스크에는 {@code @SpringBootTest} 가 없다) 문제가 되지 않는다.
 */
@Configuration
public class AttestationConfiguration {

  private static final URI ROOTS_URI =
      URI.create("https://android.googleapis.com/attestation/root");
  private static final URI REVOCATION_URI =
      URI.create("https://android.googleapis.com/attestation/status");
  private static final Duration CACHE_TTL = Duration.ofHours(6);

  // 등록 요청 스레드 위에서 도는 외부 호출이다. 타임아웃이 없으면 구글 엔드포인트가 멈췄을 때
  // 그 스레드가 무한정 잡혀 있다가 서블릿 스레드 풀 고갈로 번진다. connect 5초/read 10초는
  // 헬스체크성 API 치고 넉넉한 값으로 고른 것이다 — 필요하면 나중에 설정값으로 뺀다.
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

  @Bean
  public TrustAnchorSource trustAnchorSource(Clock clock) {
    Cache<Set<TrustAnchor>> cache = new Cache<>(clock, CACHE_TTL);
    return () -> cache.get(AttestationConfiguration::fetchAnchors);
  }

  @Bean
  public RevocationSource revocationSource(Clock clock) {
    Cache<Set<String>> cache = new Cache<>(clock, CACHE_TTL);
    return () -> cache.get(AttestationConfiguration::fetchRevokedSerials);
  }

  @Bean
  public AttestationVerifier attestationVerifier(
      TrustAnchorSource anchors, RevocationSource revocation, Clock clock) {
    return new AttestationVerifier(anchors, revocation, clock::instant);
  }

  /** 커넥션에 타임아웃을 걸어 준다. 요청 스레드가 죽은 엔드포인트에 무한정 잡히지 않도록. */
  private static HttpURLConnection timedConnection(URL url) {
    try {
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
      connection.setReadTimeout((int) READ_TIMEOUT.toMillis());
      return connection;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 목록은 공개된 JSON 배열(PEM 문자열들)이다. */
  private static Set<TrustAnchor> fetchAnchors() {
    try (InputStream in = timedConnection(ROOTS_URI.toURL()).getInputStream()) {
      Set<TrustAnchor> anchors = parseRootsResponse(in);
      if (anchors.isEmpty()) throw new IllegalStateException("루트 목록이 비었다");
      return anchors;
    } catch (Exception e) {
      // 열어두면 CRL·루트 검사를 우회할 수 있다. 조회 실패는 등록 실패다.
      throw new IllegalStateException("attestation 루트 목록을 받지 못했다", e);
    }
  }

  /**
   * 응답 본문(PEM 문자열들의 JSON 배열)을 신뢰 앵커 집합으로 파싱한다.
   *
   * <p>손으로 짠 {@code split("\"")} 문자열 스캔 대신 정식 JSON 파서(Gson)를 쓴다 — 이 목록이
   * 전체 신뢰 앵커 집합을 결정하므로, 파서가 일부만 조용히 놓치면 정상 기기가 거절당하는데
   * {@code anchors.isEmpty()} 가드는 "전부 실패"만 잡아낸다. 네트워크 없이 테스트할 수 있도록
   * {@link #fetchAnchors} 에서 분리했다.
   */
  static Set<TrustAnchor> parseRootsResponse(InputStream body) throws Exception {
    String[] pems;
    try (Reader reader = new InputStreamReader(body, StandardCharsets.UTF_8)) {
      pems = new Gson().fromJson(reader, String[].class);
    }
    if (pems == null) {
      throw new IllegalStateException("루트 목록 응답을 파싱하지 못했다");
    }
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    Set<TrustAnchor> anchors = new HashSet<>();
    for (String pem : pems) {
      X509Certificate certificate =
          (X509Certificate)
              factory.generateCertificate(
                  new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
      anchors.add(new TrustAnchor(certificate, null));
    }
    return anchors;
  }

  /**
   * 서브모듈의 {@code getGoogleRevocationStatusFromWeb()} 편의 함수는 타임아웃 없는 기본
   * connectionProvider 를 쓴다. 서브모듈을 고칠 수 없으니, 그 아래에 있는
   * {@code getRevocationStatusFromWeb(url, connectionProvider)} 를 직접 호출해 우리
   * connectionProvider(타임아웃 포함)를 넘긴다.
   */
  private static Set<String> fetchRevokedSerials() {
    try {
      return GoogleRevocationListKt.getRevocationStatusFromWeb(
          REVOCATION_URI.toURL(), AttestationConfiguration::timedConnection);
    } catch (Exception e) {
      // 열어두면 CRL 우회가 된다. 조회 실패는 등록 실패다.
      throw new IllegalStateException("attestation 폐기 목록을 받지 못했다", e);
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
