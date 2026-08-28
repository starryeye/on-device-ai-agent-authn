package dev.starryeye.agentidentity.attestation;

import java.security.PublicKey;
import java.util.List;

/** 체인 검증의 결과. 정책 판단에 필요한 것만 담는다. */
public sealed interface AttestationResult {

  /**
   * 검증을 통과했다.
   *
   * @param packageName 소프트웨어 강제 값이다. 장악된 시스템에서는 위조될 수 있으므로,
   *     이 값을 믿는 근거는 verifiedBootState 가 Verified 라는 것이다.
   */
  record Verified(
      PublicKey publicKey,
      byte[] challenge,
      String securityLevel,
      String verifiedBootState,
      boolean deviceLocked,
      String packageName,
      List<String> signingDigests)
      implements AttestationResult {}

  /** 거절. detail 은 로그용이며 사용자에게 그대로 보이지 않는다. */
  record Rejected(String detail) implements AttestationResult {}
}
