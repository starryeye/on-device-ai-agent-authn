package dev.starryeye.agentidentity.api

import dev.starryeye.agentidentity.identity.CredentialIssuer
import dev.starryeye.agentidentity.registration.ChallengeStore
import dev.starryeye.agentidentity.registration.RegistrationService
import jakarta.servlet.http.HttpServletRequest
import java.io.ByteArrayInputStream
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
) {

  data class RegistrationRequest(
      val registrationId: String,
      val attestationChain: List<String>,
      val pop: String?,
      val deviceBinding: String?,
      val playIntegrityToken: String?,
  )

  @PostMapping("/challenge")
  fun challenge(): Map<String, Any> {
    val issued = challenges.issue()
    return mapOf(
        "registrationId" to issued.registrationId,
        "challenge" to ChallengeStore.encode(issued.value),
        "expiresIn" to 300)
  }

  @PostMapping
  fun register(
      @RequestBody request: RegistrationRequest,
      httpRequest: HttpServletRequest,
  ): ResponseEntity<Map<String, Any>> {
    val outcome =
        registration.register(
            request.registrationId,
            parseChain(request.attestationChain),
            request.pop,
            httpRequest.requestURL.toString(),
            request.deviceBinding,
            request.playIntegrityToken)

    if (!outcome.isAccepted) {
      return ResponseEntity.status(403).body(mapOf("reason" to outcome.reason!!.name))
    }
    val identity = outcome.identity!!
    return ResponseEntity.ok(
        mapOf(
            "agentId" to identity.id,
            "credential" to credentials.issue(identity),
            "expiresIn" to 900))
  }

  companion object {
    private fun parseChain(encoded: List<String>): List<X509Certificate> {
      val factory = CertificateFactory.getInstance("X.509")
      return encoded.map { der ->
        factory.generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(der)))
            as X509Certificate
      }
    }
  }
}
