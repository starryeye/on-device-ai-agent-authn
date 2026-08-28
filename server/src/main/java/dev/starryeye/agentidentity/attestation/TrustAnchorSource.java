package dev.starryeye.agentidentity.attestation;

import java.security.cert.TrustAnchor;
import java.util.Set;

/**
 * 신뢰할 구글 attestation 루트. 하나가 아니고 목록이 갱신되므로 조회기로 둔다.
 * 테스트는 고정 집합을, 운영은 공개 목록을 캐시해 돌려준다.
 */
@FunctionalInterface
public interface TrustAnchorSource {
  Set<TrustAnchor> anchors();
}
