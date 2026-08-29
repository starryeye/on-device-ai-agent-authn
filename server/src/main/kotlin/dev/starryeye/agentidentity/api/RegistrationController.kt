package dev.starryeye.agentidentity.api

import dev.starryeye.agentidentity.identity.CredentialIssuer
import dev.starryeye.agentidentity.policy.PolicyProperties
import dev.starryeye.agentidentity.policy.RejectionReason
import dev.starryeye.agentidentity.registration.ChallengeStore
import dev.starryeye.agentidentity.registration.RegistrationService
import jakarta.servlet.http.HttpServletRequest
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/agent/registration")
class RegistrationController(
    private val challenges: ChallengeStore,
    private val registration: RegistrationService,
    private val credentials: CredentialIssuer,
    private val properties: PolicyProperties,
) {

  data class RegistrationRequest(
      val registrationId: String,
      val attestationChain: List<String>,
      val pop: String?,
      val deviceBinding: String?,
      val playIntegrityToken: String?,
  )

  @PostMapping("/challenge")
  fun challenge(): ResponseEntity<Map<String, Any>> {
    val issued =
        try {
          challenges.issue()
        } catch (e: ChallengeStore.CapacityExceededException) {
          // 대기 중인 challenge 가 상한에 닿았다 — 살아있는 challenge 를 밀어내는 대신
          // 새 발급을 거절한다. 503 은 클라이언트에게 "지금은 안 되니 나중에 다시" 를
          // 전달하되, 이 실패가 요청 자체의 잘못이 아니라 서버 용량 문제임을 구분한다.
          return ResponseEntity.status(503).body(mapOf("reason" to "CHALLENGE_STORE_FULL"))
        }
    return ResponseEntity.ok(
        mapOf(
            "registrationId" to issued.registrationId,
            "challenge" to ChallengeStore.encode(issued.value),
            "expiresIn" to properties.challengeTtl.toSeconds()))
  }

  @PostMapping
  fun register(
      @RequestBody request: RegistrationRequest,
      httpRequest: HttpServletRequest,
  ): ResponseEntity<Map<String, Any>> {
    val chain =
        parseChain(request.attestationChain)
            ?: return ResponseEntity.status(403)
                .body(mapOf("reason" to RejectionReason.CHAIN_UNTRUSTED.name))

    val outcome =
        registration.register(
            request.registrationId,
            chain,
            request.pop,
            httpRequest.requestURL.toString(),
            request.deviceBinding,
            request.playIntegrityToken)

    return when (outcome) {
      is RegistrationService.Outcome.Rejected ->
          ResponseEntity.status(403).body(mapOf("reason" to outcome.reason.name))
      is RegistrationService.Outcome.Accepted ->
          ResponseEntity.ok(
              mapOf(
                  "agentId" to outcome.identity.id,
                  "credential" to credentials.issue(outcome.identity),
                  "expiresIn" to properties.credentialTtl.toSeconds()))
    }
  }

  companion object {
    /**
     * 잘못된 체인(base64 가 아니거나, base64 이지만 인증서가 아닌 것)이면 `null` 을
     * 돌려준다. 인증되지 않은 엔드포인트가 임의의 문자열을 그대로 파싱 라이브러리에
     * 넘기므로, 그 라이브러리가 던지는 예외를 여기서 붙잡지 않으면 500 으로 새어나가고
     * 매 악성 요청마다 서버 쪽에 전체 스택트레이스가 찍힌다. `CHAIN_UNTRUSTED` 로 돌려
     * 다른 거절과 같은 모양의 응답을 준다 — 파싱할 수 없는 체인은 신뢰할 수 없는 체인의
     * 특수한 경우일 뿐이다.
     */
    private fun parseChain(encoded: List<String>): List<X509Certificate>? {
      return try {
        val factory = CertificateFactory.getInstance("X.509")
        encoded.map { der ->
          factory.generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(der)))
              as X509Certificate
        }
      } catch (e: IllegalArgumentException) {
        // Base64.getDecoder().decode() 가 base64 가 아닌 입력에 던진다.
        null
      } catch (e: CertificateException) {
        // generateCertificate 가 base64 는 맞지만 인증서가 아닌 바이트열에 던진다.
        null
      }
    }
  }
}
