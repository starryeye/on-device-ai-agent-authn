package dev.starryeye.agentidentity.attestation

/**
 * 폐기된 attestation 인증서의 시리얼 번호(소문자 16진).
 *
 * 확인은 선택이 아니다 — 폐기된 키로 만든 체인은 하드웨어 보증이 무효다. 조회기로 둔 것은
 * 테스트에서 네트워크를 타지 않기 위해서다.
 */
fun interface RevocationSource {
  fun revokedSerials(): Set<String>
}
