package dev.starryeye.agentidentity.registration

import dev.starryeye.agentidentity.attestation.AttestationResult
import java.security.KeyPairGenerator
import java.security.PublicKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `RegistrationService.thumbprintOf` 는 브리프의 `ECKey.Builder(ECPublicKey)` 가 실제
 * nimbus-jose-jwt 10.9.1 API 에는 없어서(`Curve` 를 함께 요구한다) `Curve.forECParameterSpec`
 * 로 곡선을 역산해 대체한, 이 태스크에서 유일하게 새로 짜야 했던 로직이다. 스프링도 실제
 * attestation 체인도 필요 없다 — `AttestationResult.Verified` 는 평범한 데이터 클래스이고,
 * 지문 계산은 그 안의 공개키만 본다.
 */
class RegistrationServiceThumbprintTest {

  companion object {
    private fun generateKey(): PublicKey =
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public

    private fun verifiedWith(publicKey: PublicKey): AttestationResult.Verified =
        AttestationResult.Verified(
            publicKey = publicKey,
            challenge = ByteArray(32),
            securityLevel = "TRUSTED_ENVIRONMENT",
            verifiedBootState = "VERIFIED",
            deviceLocked = true,
            packageName = "dev.starryeye.ondeviceagent",
            signingDigests = emptyList())
  }

  @Test
  fun `같은_공개키는_같은_지문을_낸다`() {
    val key = generateKey()

    val first = RegistrationService.thumbprintOf(verifiedWith(key))
    val second = RegistrationService.thumbprintOf(verifiedWith(key))

    assertThat(first).isEqualTo(second)
  }

  @Test
  fun `다른_공개키는_다른_지문을_낸다`() {
    val first = RegistrationService.thumbprintOf(verifiedWith(generateKey()))
    val second = RegistrationService.thumbprintOf(verifiedWith(generateKey()))

    assertThat(first).isNotEqualTo(second)
  }
}
