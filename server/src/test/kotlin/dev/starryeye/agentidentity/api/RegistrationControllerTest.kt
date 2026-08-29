package dev.starryeye.agentidentity.api

import dev.starryeye.agentidentity.identity.CredentialIssuer
import dev.starryeye.agentidentity.registration.ChallengeStore
import dev.starryeye.agentidentity.registration.RegistrationService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 리뷰에서 지적된 대로, 인증되지 않은 `/agent/registration` 이 파싱할 수 없는 체인을 받으면
 * 500 을 냈다 — base64 가 아닌 값은 `Base64.getDecoder().decode()` 가, base64 이지만
 * 인증서가 아닌 값은 `generateCertificate` 가 던지는 예외를 아무도 붙잡지 않았기 때문이다.
 * 이 테스트는 그 회귀를 막는다: 두 경우 모두 500 이 아니라 다른 거절과 같은 모양의 403
 * `CHAIN_UNTRUSTED` 여야 하고, 체인을 만들 수조차 없었으니 `RegistrationService` 까지
 * 가지 않아야 한다.
 *
 * `/agent/registration/challenge` 의 상한 처리(새 finding)도 같은 파일에서 함께 다룬다 —
 * 두 finding 모두 "인증 없는 엔드포인트가 예외를 어떻게 다루는가" 라는 같은 결의 문제다.
 */
@WebMvcTest(RegistrationController::class)
class RegistrationControllerTest {

  @Autowired private lateinit var mockMvc: MockMvc

  @MockitoBean private lateinit var challenges: ChallengeStore
  @MockitoBean private lateinit var registration: RegistrationService
  @MockitoBean private lateinit var credentials: CredentialIssuer

  @Test
  fun `base64가_아닌_attestationChain_은_500이_아니라_403_CHAIN_UNTRUSTED_를_돌려준다`() {
    val body =
        """{"registrationId":"r1","attestationChain":["not-base64!!"],"pop":null,"deviceBinding":null,"playIntegrityToken":null}"""

    mockMvc
        .perform(post("/agent/registration").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden)
        .andExpect(jsonPath("$.reason").value("CHAIN_UNTRUSTED"))

    // 체인조차 만들 수 없었다 — registrationId 를 확인하러 RegistrationService 까지
    // 갈 이유가 없다.
    Mockito.verifyNoInteractions(registration)
  }

  @Test
  fun `base64이지만_인증서가_아닌_attestationChain_은_500이_아니라_403_CHAIN_UNTRUSTED_를_돌려준다`() {
    // "not a certificate" 를 base64 로 인코딩한 값 — 디코딩은 되지만 X.509 인증서로
    // 파싱되지는 않는다.
    val notACertificateBase64 = "bm90IGEgY2VydGlmaWNhdGU="
    val body =
        """{"registrationId":"r1","attestationChain":["$notACertificateBase64"],"pop":null,"deviceBinding":null,"playIntegrityToken":null}"""

    mockMvc
        .perform(post("/agent/registration").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden)
        .andExpect(jsonPath("$.reason").value("CHAIN_UNTRUSTED"))

    Mockito.verifyNoInteractions(registration)
  }

  @Test
  fun `challenge_저장소가_상한이면_500이_아니라_503을_돌려준다`() {
    Mockito.`when`(challenges.issue()).thenThrow(ChallengeStore.CapacityExceededException())

    mockMvc.perform(post("/agent/registration/challenge")).andExpect(status().isServiceUnavailable)
  }
}
