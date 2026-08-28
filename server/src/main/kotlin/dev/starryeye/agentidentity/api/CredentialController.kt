package dev.starryeye.agentidentity.api

import dev.starryeye.agentidentity.identity.AgentIdentity
import dev.starryeye.agentidentity.identity.AgentIdentityRepository
import dev.starryeye.agentidentity.identity.CredentialIssuer
import dev.starryeye.agentidentity.policy.PolicyProperties
import dev.starryeye.agentidentity.policy.RejectionReason
import dev.starryeye.agentidentity.proof.JwsProofVerifier
import dev.starryeye.agentidentity.proof.ProofType
import jakarta.servlet.http.HttpServletRequest
import java.time.Clock
import java.time.Duration
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * 자격증명 갱신과 신원 확인.
 *
 * 갱신은 attestation 을 다시 하지 않는다 — 등록 때 검증했고, 그 키를 지금 쥐고 있다는 사실이
 * proof 로 증명된다. 다만 무기한은 아니다(`max-attestation-age`).
 */
@RestController
class CredentialController(
    private val proofs: JwsProofVerifier,
    private val repository: AgentIdentityRepository,
    private val credentials: CredentialIssuer,
    private val properties: PolicyProperties,
    private val clock: Clock,
) {

  @PostMapping("/agent/credential")
  fun refresh(
      @RequestHeader("DPoP") proof: String,
      request: HttpServletRequest,
  ): ResponseEntity<Map<String, Any>> {
    val identity =
        authenticate(proof, "POST", request)
            ?: return ResponseEntity.status(401)
                .body(mapOf("reason" to RejectionReason.DPOP_INVALID.name))

    val age = Duration.between(identity.lastAttestedAt, clock.instant())
    if (age > properties.maxAttestationAge) {
      return ResponseEntity.status(401)
          .body(mapOf("reason" to RejectionReason.REATTESTATION_REQUIRED.name))
    }

    identity.markAuthenticated(clock.instant())
    repository.save(identity)
    return ResponseEntity.ok(
        mapOf(
            "agentId" to identity.id,
            "credential" to credentials.issue(identity),
            "expiresIn" to 900))
  }

  @GetMapping("/agent/whoami")
  fun whoami(
      @RequestHeader("DPoP") proof: String,
      request: HttpServletRequest,
  ): ResponseEntity<Map<String, Any>> {
    val identity =
        authenticate(proof, "GET", request)
            ?: return ResponseEntity.status(401)
                .body(mapOf("reason" to RejectionReason.DPOP_INVALID.name))
    return ResponseEntity.ok(mapOf("agentId" to identity.id))
  }

  private fun authenticate(
      proof: String,
      method: String,
      request: HttpServletRequest,
  ): AgentIdentity? {
    val thumbprint =
        proofs.verify(proof, ProofType.DPOP, method, request.requestURL.toString()) ?: return null
    val identity = repository.findByJwkThumbprint(thumbprint) ?: return null
    return identity.takeIf { it.status == "ACTIVE" }
  }
}
