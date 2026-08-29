package dev.starryeye.agentidentity.attestation

import java.security.PublicKey
import java.security.cert.CertPathValidatorException

/** 체인 검증의 결과. 정책 판단에 필요한 것만 담는다. */
sealed interface AttestationResult {

  /**
   * 검증을 통과했다.
   *
   * @param packageName 소프트웨어 강제 값이다. 장악된 시스템에서는 위조될 수 있으므로,
   *     이 값을 믿는 근거는 verifiedBootState 가 Verified 라는 것이다.
   */
  data class Verified(
      val publicKey: PublicKey,
      val challenge: ByteArray,
      val securityLevel: String,
      val verifiedBootState: String,
      val deviceLocked: Boolean,
      val packageName: String,
      val signingDigests: List<String>,
  ) : AttestationResult

  /**
   * 거절. detail 은 로그용이며 사용자에게 그대로 보이지 않는다.
   *
   * @param infrastructureFailure 체인/설정이 아니라 우리(또는 구글) 인프라 — 신뢰 앵커/폐기
   *     목록 조회 — 가 실패했다는 표시. 공격이 아니라 일시적 장애이므로, 이 값을 보는 호출자는
   *     "재시도 무의미"로 취급해서는 안 된다.
   * @param certPathReason 경로 검증 실패([com.android.keyattestation.verifier.VerificationResult.PathValidationFailure])의
   *     구체적 사유. `REVOKED`(CRL 등재)와 `EXPIRED`(RKP 인증서 만료)를 그 밖의 경로 검증
   *     실패와 구분하는 데 쓴다 — 이 값이 없으면 셋 다 같은 `CertPathValidatorException`
   *     껍데기 뒤에 숨어 사유를 알 수 없다.
   */
  data class Rejected(
      val detail: String,
      val infrastructureFailure: Boolean = false,
      val certPathReason: CertPathValidatorException.Reason? = null,
  ) : AttestationResult
}
