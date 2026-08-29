package dev.starryeye.ondeviceagent.identity

/** 실행 환경 무결성 증거. 신원이 아니라 정책의 보조 재료다. */
fun interface IntegrityTokenProvider {
  suspend fun integrityToken(): String?
}

/** 지금 유일한 구현. Play Integrity 연동은 이 사이클의 범위 밖이다. */
object NoIntegrityToken : IntegrityTokenProvider {
  override suspend fun integrityToken(): String? = null
}
