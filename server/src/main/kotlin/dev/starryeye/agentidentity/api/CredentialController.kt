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
        when (val outcome = authenticate(proof, "POST", request)) {
          is AuthenticationOutcome.Authenticated -> outcome.identity
          AuthenticationOutcome.ProofInvalid -> return unauthorized(RejectionReason.DPOP_INVALID)
          AuthenticationOutcome.AgentNotFound -> return unauthorized(RejectionReason.AGENT_NOT_FOUND)
          AuthenticationOutcome.AgentInactive -> return unauthorized(RejectionReason.AGENT_INACTIVE)
        }

    val age = Duration.between(identity.lastAttestedAt, clock.instant())
    if (age > properties.maxAttestationAge) {
      return unauthorized(RejectionReason.REATTESTATION_REQUIRED)
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
        when (val outcome = authenticate(proof, "GET", request)) {
          is AuthenticationOutcome.Authenticated -> outcome.identity
          AuthenticationOutcome.ProofInvalid -> return unauthorized(RejectionReason.DPOP_INVALID)
          AuthenticationOutcome.AgentNotFound -> return unauthorized(RejectionReason.AGENT_NOT_FOUND)
          AuthenticationOutcome.AgentInactive -> return unauthorized(RejectionReason.AGENT_INACTIVE)
        }
    return ResponseEntity.ok(mapOf("agentId" to identity.id))
  }

  /**
   * DPoP 인증 결과. `AgentIdentity?` 하나로는 "proof 자체가 틀렸다"와 "proof 는 맞는데 이
   * 지문으로 등록된 신원이 없다"를 구분할 수 없었다 — 둘 다 `null` 이었다. 그 구분이 있어야
   * `/agent/credential`·`/agent/whoami` 가 서로 다른 사유 코드를 돌려줄 수 있고, 클라이언트는
   * "재등록해도 안전한 실패"(등록된 적 없음)와 "재등록하면 위험한 실패"(가짜/재생된 proof,
   * 혹은 비활성화된 신원)를 가를 수 있다.
   */
  private sealed interface AuthenticationOutcome {
    data class Authenticated(val identity: AgentIdentity) : AuthenticationOutcome

    /** 서명·`typ`·`htm`/`htu`·`iat` 오차·`jti` 재생 중 하나라도 걸렸다. */
    data object ProofInvalid : AuthenticationOutcome

    /**
     * proof 는 유효하지만 그 서명자 지문으로 등록된 신원이 없다. attested 키를 지금 막
     * 확인했을 뿐 그 키로 등록한 적이 없는 경우다 — 재등록해도 안전하다.
     */
    data object AgentNotFound : AuthenticationOutcome

    /**
     * 신원은 있지만 `ACTIVE` 가 아니다. 지금은 어떤 경로도 상태를 다른 값으로 두지 않으므로
     * 실제로는 도달하지 않지만, 도달했을 때 `AgentNotFound` 뒤에 숨어 재등록으로 비활성화를
     * 우회하는 일이 없도록 자기 갈래를 갖는다.
     */
    data object AgentInactive : AuthenticationOutcome
  }

  private fun authenticate(
      proof: String,
      method: String,
      request: HttpServletRequest,
  ): AuthenticationOutcome {
    val thumbprint =
        proofs.verify(proof, ProofType.DPOP, method, request.requestURL.toString())
            ?: return AuthenticationOutcome.ProofInvalid
    val identity =
        repository.findByJwkThumbprint(thumbprint) ?: return AuthenticationOutcome.AgentNotFound
    return if (identity.status == "ACTIVE") AuthenticationOutcome.Authenticated(identity)
    else AuthenticationOutcome.AgentInactive
  }

  private fun unauthorized(reason: RejectionReason): ResponseEntity<Map<String, Any>> =
      ResponseEntity.status(401).body(mapOf("reason" to reason.name))
}
