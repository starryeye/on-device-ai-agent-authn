package dev.starryeye.agentidentity.attestation

import com.android.keyattestation.verifier.AttestationApplicationId
import com.android.keyattestation.verifier.ChallengeChecker
import com.android.keyattestation.verifier.ConstraintConfig
import com.android.keyattestation.verifier.ExtensionParsingException
import com.android.keyattestation.verifier.InstantSource
import com.android.keyattestation.verifier.KeyDescription
import com.android.keyattestation.verifier.VerificationResult
import com.android.keyattestation.verifier.Verifier
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.protobuf.ByteString
import java.security.InvalidAlgorithmParameterException
import java.security.cert.CertPathValidatorException
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.HexFormat
import org.slf4j.LoggerFactory

/**
 * 구글 공식 검증기를 우리 도메인 타입 뒤로 감싼다.
 *
 * 직접 파싱하지 않는 이유는 체인 검증·루트 집합·RKP 유효기간·폐기 목록·확장 파싱이 모두
 * 놓치기 쉬운 영역이고, 우리가 새로 짜서 더 잘할 이유가 없기 때문이다.
 *
 * 이 클래스는 HTTP 도 JPA 도 모른다. 그래야 실기기 체인을 픽스처로 반복 검증할 수 있다.
 *
 * [Verifier] 는 첫 [verify] 호출 시점에 지연 생성한다. `Verifier` 의 `init` 블록은
 * `allowSoftwareRoot=false`(기본값)일 때 즉시 `trustAnchorsSource()` 를 호출해 순회하는데,
 * 생성자에서 미리 만들어 버리면 스프링이 기동 시점에 빈을 생성하면서 네트워크를 타게 되고,
 * 오프라인에서 도는 나중 단계의 `@SpringBootTest` 가 깨진다.
 */
class AttestationVerifier(
    private val anchors: TrustAnchorSource,
    private val revocation: RevocationSource,
    private val clock: InstantSource,
) {

  // by lazy 는 기본이 스레드 안전(SYNCHRONIZED)이라 이중 검사 잠금을 직접 짤 필요가 없다.
  // 다만 by lazy 는 예외를 캐시하지 않으므로 최초 생성이 실패하면 다음 호출에서 다시
  // 시도한다 — Java 구현(이중 검사 잠금 + volatile)과 같은 성질이다.
  //
  // anchorsOrThrow/revokedSerialsOrThrow 를 넘긴다 — 원본 조회기가 던지는 예외를
  // InfrastructureFailure 로 표시해 두면, verify() 의 catch 블록이 "우리 인프라가
  // 죽었다"와 "체인/설정이 잘못됐다"를 구분할 수 있다.
  private val verifier: Verifier by lazy {
    Verifier(::anchorsOrThrow, ::revokedSerialsOrThrow, clock, ConstraintConfig())
  }

  private fun anchorsOrThrow(): Set<TrustAnchor> =
      try {
        anchors.anchors()
      } catch (e: Exception) {
        throw InfrastructureFailure(e)
      }

  private fun revokedSerialsOrThrow(): Set<String> =
      try {
        revocation.revokedSerials()
      } catch (e: Exception) {
        throw InfrastructureFailure(e)
      }

  /**
   * 신뢰 앵커/폐기 목록 조회기가 던진 예외임을 표시하는 마커. `trustAnchorsSource()` 와
   * `revokedSerialsSource()` 는 [Verifier] 가 검증마다(그리고 최초 생성 시) 직접 호출하므로,
   * 이 예외로 감싸 두지 않으면 체인 자체의 문제(경로 검증 실패 등)와 구분할 방법이 없다.
   */
  private class InfrastructureFailure(cause: Throwable) : RuntimeException(cause)

  /**
   * 체인을 검증하고 정책이 필요로 하는 값을 뽑는다.
   *
   * @param expectedChallenge 우리가 발급한 challenge. 체인 안에 이 값이 박혀 있어야 한다
   */
  fun verify(chain: List<X509Certificate>, expectedChallenge: ByteArray): AttestationResult {
    if (chain.isEmpty()) {
      return AttestationResult.Rejected("empty chain")
    }

    val expected = ByteString.copyFrom(expectedChallenge)
    val result: VerificationResult =
        try {
          // 이 try 는 Verifier 생성(지연 생성 시)과 라이브러리의 verify() 호출만 감싼다. 이
          // 범위 안에서 예외가 나오는 경우는 세 가지뿐이다: (1) 신뢰 앵커/폐기 목록 조회기가
          // 던진 것(anchorsOrThrow/revokedSerialsOrThrow 가 InfrastructureFailure 로 표시),
          // (2) 신뢰 앵커 집합이 비었거나(PKIXParameters) 소프트웨어 루트가 앵커로 들어온
          // 설정 오류, (3) 그 외 — 체인 자체가 이상해서 나는 것으로 본다. 이 아래
          // KeyDescription.parseFrom 재파싱은 **의도적으로 이 catch 밖에 둔다** — 여기서 나는
          // 예외는 이미 검증을 통과한 체인을 우리가 다시 읽다가 나는 것이므로, 진짜 버그라면
          // 거절로 위장하지 말고 그대로 튀어야 한다.
          verifier.verify(
              chain,
              object : ChallengeChecker {
                override fun checkChallenge(challenge: ByteString): ListenableFuture<Boolean> =
                    Futures.immediateFuture(challenge == expected)
              })
        } catch (e: Exception) {
          when (e) {
            is InfrastructureFailure -> {
              // 구글 루트/폐기 목록 조회 실패. 공격이 아니라 우리 쪽(또는 구글 쪽) 장애다 —
              // 반드시 시끄럽게 알려야 한다. fail-closed 는 맞는 동작이지만, 조용히 거절만
              // 하면 이게 공격 시도인지 장애인지 운영자가 구분할 수 없다.
              log.error("attestation 신뢰 앵커/폐기 목록 조회 실패 — 등록을 거절한다", e.cause)
              return AttestationResult.Rejected(
                  "infrastructure failure: ${e.cause}", infrastructureFailure = true)
            }
            is InvalidAlgorithmParameterException,
            is IllegalArgumentException -> {
              // 신뢰 앵커가 비었거나(PKIXParameters), 소프트웨어 루트가 앵커로 들어왔다
              // (Verifier 의 init 블록). 둘 다 배포 설정 문제이지 공격이 아니다.
              log.error("attestation 검증기 설정 오류 — 등록을 거절한다", e)
              return AttestationResult.Rejected("configuration error: $e")
            }
            else -> {
              // 그 외는 체인 자체가 이상해서 나는 것으로 본다. 공격/오작동 기기가 매일 만들어낼
              // 수 있는 잡음이므로 ERROR 가 아니라 DEBUG 로 남긴다.
              log.debug("attestation chain verification threw", e)
              return AttestationResult.Rejected("verification threw: $e")
            }
          }
        }

    if (result !is VerificationResult.Success) {
      // PathValidationFailure 는 CertPathValidatorException 을 그대로 담고 있다 — 그
      // getReason() 이 REVOKED(CRL 등재)/EXPIRED(RKP 만료)를 나머지 경로 검증 실패와
      // 구분할 수 있는 유일한 자리다. 여기서 옮겨두지 않으면 RegistrationService 는
      // "PathValidationFailure" 라는 클래스 이름 하나로 셋을 뭉뚱그릴 수밖에 없다.
      val certPathReason = (result as? VerificationResult.PathValidationFailure)?.cause?.reason
      return AttestationResult.Rejected(result.javaClass.simpleName, certPathReason = certPathReason)
    }

    // 앱 신원은 Success 에 담겨 오지 않는다. leaf 를 직접 파싱해 읽는다.
    // 이 값이 softwareEnforced 에 있다는 사실이 타입으로 드러난다.
    val description: KeyDescription =
        try {
          KeyDescription.parseFrom(chain[0])
        } catch (e: ExtensionParsingException) {
          return AttestationResult.Rejected("failed to parse key description: ${e.message}")
        } ?: return AttestationResult.Rejected("no key description extension")

    return when (
        val identity = resolvePackageIdentity(description.softwareEnforced.attestationApplicationId)) {
      is PackageIdentity.Rejected -> AttestationResult.Rejected(identity.detail)
      is PackageIdentity.Resolved ->
          AttestationResult.Verified(
              result.publicKey,
              result.challenge.toByteArray(),
              result.securityLevel.toString(),
              result.verifiedBootState.toString(),
              result.deviceLocked,
              identity.packageName,
              identity.signingDigests)
    }
  }

  /** [resolvePackageIdentity] 의 결과. */
  internal sealed interface PackageIdentity {
    data class Resolved(val packageName: String, val signingDigests: List<String>) : PackageIdentity

    data class Rejected(val detail: String) : PackageIdentity
  }

  companion object {
    private val log = LoggerFactory.getLogger(AttestationVerifier::class.java)

    /**
     * leaf 인증서의 attestationApplicationId 하나를 패키지 신원으로 확정하거나 거절한다.
     *
     * 공유 UID 앱은 패키지 여러 개를 하나의 attestationApplicationId 에 묶어 넣을 수 있다.
     * 하나만 골라서 넘기면, 정책이 그 이름 하나로 exact-match 할 때 실제로는 같이 설치된
     * 다른 패키지의 키로도 통과시켜 버리는 셈이 된다 — 조용히 고르지 않고 명시적으로
     * 거절한다.
     *
     * `verify()` 본문에서 순수 함수로 뽑아 둔 이유는 시험 때문이다. 이 분기(패키지 두 개)를
     * 실제 attestation 체인으로 재현하려면 실기기가 만들지 않는 확장을 통째로 새로
     * 인코딩해야 한다 — 반면 [AttestationApplicationId] 는 평범한 데이터 클래스라서, 체인도
     * 인증서도 없이 이 함수만 직접 시험할 수 있다.
     */
    internal fun resolvePackageIdentity(application: AttestationApplicationId?): PackageIdentity {
      if (application == null || application.packages.isEmpty()) {
        return PackageIdentity.Rejected("no attestationApplicationId")
      }
      if (application.packages.size > 1) {
        return PackageIdentity.Rejected("ambiguous attestationApplicationId")
      }
      val packageName = application.packages.iterator().next().name
      val signingDigests: List<String> =
          application.signatures.map { signature -> HexFormat.of().formatHex(signature.toByteArray()) }
      return PackageIdentity.Resolved(packageName, signingDigests)
    }
  }
}
