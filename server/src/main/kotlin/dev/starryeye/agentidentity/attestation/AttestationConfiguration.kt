package dev.starryeye.agentidentity.attestation

import com.android.keyattestation.verifier.getRevocationStatusFromWeb
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.UncheckedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// 등록 요청 스레드 위에서 도는 외부 호출이다. 타임아웃이 없으면 구글 엔드포인트가 멈췄을 때
// 그 스레드가 무한정 잡혀 있다가 서블릿 스레드 풀 고갈로 번진다. connect 5초/read 10초는
// 헬스체크성 API 치고 넉넉한 값으로 고른 것이다 — 필요하면 나중에 설정값으로 뺀다.
private val ROOTS_URI: URI = URI.create("https://android.googleapis.com/attestation/root")
private val REVOCATION_URI: URI = URI.create("https://android.googleapis.com/attestation/status")
private val CACHE_TTL: Duration = Duration.ofHours(6)
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
private val READ_TIMEOUT: Duration = Duration.ofSeconds(10)

/**
 * 구글 루트 목록과 폐기 목록을 받아 캐시하고, 검증기를 조립한다.
 *
 * `Clock` 빈은 이 클래스가 아니라 Task 4 에서 등록된다(`java.time.Clock.systemUTC()`). 그 전까지는
 * 이 설정을 실제로 로드하는 스프링 컨텍스트가 없으므로(이 태스크에는 `@SpringBootTest` 가 없다)
 * 문제가 되지 않는다.
 */
@Configuration
class AttestationConfiguration {

  @Bean
  fun trustAnchorSource(clock: Clock): TrustAnchorSource {
    val cache = Cache<Set<TrustAnchor>>(clock, CACHE_TTL)
    return TrustAnchorSource { cache.get(::fetchAnchors) }
  }

  @Bean
  fun revocationSource(clock: Clock): RevocationSource {
    val cache = Cache<Set<String>>(clock, CACHE_TTL)
    return RevocationSource { cache.get(::fetchRevokedSerials) }
  }

  @Bean
  fun attestationVerifier(
      anchors: TrustAnchorSource,
      revocation: RevocationSource,
      clock: Clock,
  ): AttestationVerifier = AttestationVerifier(anchors, revocation, clock::instant)

  /** 아주 단순한 TTL 캐시. 실패는 캐시하지 않는다. */
  private class Cache<T>(private val clock: Clock, private val ttl: Duration) {
    private val value = AtomicReference<T>()
    private val fetchedAt = AtomicReference<Instant>()

    fun get(loader: () -> T): T {
      val at = fetchedAt.get()
      val cached = value.get()
      if (cached != null && at != null && Duration.between(at, clock.instant()) < ttl) {
        return cached
      }
      val loaded = loader()
      value.set(loaded)
      fetchedAt.set(clock.instant())
      return loaded
    }
  }

  companion object {

    /** 커넥션에 타임아웃을 걸어 준다. 요청 스레드가 죽은 엔드포인트에 무한정 잡히지 않도록. */
    private fun timedConnection(url: URL): HttpURLConnection {
      try {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT.toMillis().toInt()
        connection.readTimeout = READ_TIMEOUT.toMillis().toInt()
        return connection
      } catch (e: IOException) {
        throw UncheckedIOException(e)
      }
    }

    /** 목록은 공개된 JSON 배열(PEM 문자열들)이다. */
    private fun fetchAnchors(): Set<TrustAnchor> {
      try {
        timedConnection(ROOTS_URI.toURL()).inputStream.use { input ->
          val anchors = parseRootsResponse(input)
          if (anchors.isEmpty()) throw IllegalStateException("루트 목록이 비었다")
          return anchors
        }
      } catch (e: Exception) {
        // 열어두면 CRL·루트 검사를 우회할 수 있다. 조회 실패는 등록 실패다.
        throw IllegalStateException("attestation 루트 목록을 받지 못했다", e)
      }
    }

    /**
     * 응답 본문(PEM 문자열들의 JSON 배열)을 신뢰 앵커 집합으로 파싱한다.
     *
     * 손으로 짠 `split("\"")` 문자열 스캔 대신 정식 JSON 파서(Gson)를 쓴다 — 이 목록이 전체
     * 신뢰 앵커 집합을 결정하므로, 파서가 일부만 조용히 놓치면 정상 기기가 거절당하는데
     * `anchors.isEmpty()` 가드는 "전부 실패"만 잡아낸다. 네트워크 없이 테스트할 수 있도록
     * [fetchAnchors] 에서 분리했다.
     */
    @Throws(Exception::class)
    internal fun parseRootsResponse(body: InputStream): Set<TrustAnchor> {
      val pems: Array<String>? =
          InputStreamReader(body, StandardCharsets.UTF_8).use { reader ->
            Gson().fromJson(reader, Array<String>::class.java)
          }
      if (pems == null) {
        throw IllegalStateException("루트 목록 응답을 파싱하지 못했다")
      }
      val factory = CertificateFactory.getInstance("X.509")
      val anchors = HashSet<TrustAnchor>()
      for (pem in pems) {
        val certificate =
            factory.generateCertificate(ByteArrayInputStream(pem.toByteArray(StandardCharsets.UTF_8)))
                as X509Certificate
        anchors.add(TrustAnchor(certificate, null))
      }
      return anchors
    }

    /**
     * 서브모듈의 `getGoogleRevocationStatusFromWeb()` 편의 함수는 타임아웃 없는 기본
     * connectionProvider 를 쓴다. 서브모듈을 고칠 수 없으니, 그 아래에 있는
     * `getRevocationStatusFromWeb(url, connectionProvider)` 를 직접 호출해 우리
     * connectionProvider(타임아웃 포함)를 넘긴다.
     */
    private fun fetchRevokedSerials(): Set<String> {
      try {
        return getRevocationStatusFromWeb(REVOCATION_URI.toURL(), ::timedConnection)
      } catch (e: Exception) {
        // 열어두면 CRL 우회가 된다. 조회 실패는 등록 실패다.
        throw IllegalStateException("attestation 폐기 목록을 받지 못했다", e)
      }
    }
  }
}
