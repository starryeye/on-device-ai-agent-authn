package dev.starryeye.agentidentity.registration

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import dev.starryeye.agentidentity.attestation.AttestationResult
import dev.starryeye.agentidentity.attestation.AttestationVerifier
import dev.starryeye.agentidentity.identity.AgentIdentifier
import dev.starryeye.agentidentity.identity.AgentIdentity
import dev.starryeye.agentidentity.identity.AgentIdentityRepository
import dev.starryeye.agentidentity.policy.PolicyProperties
import dev.starryeye.agentidentity.policy.RegistrationPolicy
import dev.starryeye.agentidentity.policy.RejectionReason
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service

/** 등록을 조립한다. 검증도 정책 판단도 직접 하지 않고 각각에 맡긴다. */
@Service
class RegistrationService(
    private val challenges: ChallengeStore,
    private val verifier: AttestationVerifier,
    private val policy: RegistrationPolicy,
    private val repository: AgentIdentityRepository,
    private val properties: PolicyProperties,
    private val clock: Clock,
) {

  /** 등록 결과. 거절이면 reason 이 채워진다. */
  data class Outcome(val identity: AgentIdentity?, val reason: RejectionReason?) {
    val isAccepted: Boolean
      get() = reason == null

    companion object {
      fun accepted(identity: AgentIdentity): Outcome = Outcome(identity, null)

      fun rejected(reason: RejectionReason): Outcome = Outcome(null, reason)
    }
  }

  fun register(
      registrationId: String,
      chain: List<X509Certificate>,
      deviceBinding: String?,
      integrityToken: String?,
  ): Outcome {

    val challenge =
        challenges.consume(registrationId) ?: return Outcome.rejected(RejectionReason.CHALLENGE_INVALID)

    val result = verifier.verify(chain, challenge)
    val verified =
        when (result) {
          is AttestationResult.Verified -> result
          is AttestationResult.Rejected -> {
            // 사유를 뭉개면 정책을 바꿔가며 관찰하는 이 연구가 성립하지 않는다.
            val reason =
                when (result.detail) {
                  "ChallengeMismatch" -> RejectionReason.CHALLENGE_INVALID
                  else -> RejectionReason.CHAIN_UNTRUSTED
                }
            return Outcome.rejected(reason)
          }
        }

    val rejected = policy.evaluate(verified, deviceBinding, integrityToken)
    if (rejected != null) {
      return Outcome.rejected(rejected)
    }

    val thumbprint = thumbprintOf(verified)

    // 멱등: 같은 키면 같은 신원. 새 신원은 키가 바뀔 때만 생긴다.
    val existing = repository.findByJwkThumbprint(thumbprint)
    if (existing != null) {
      existing.markAttested(clock.instant())
      return Outcome.accepted(repository.save(existing))
    }

    val identity =
        AgentIdentity(
            AgentIdentifier.create(
                properties.identifierNamespace,
                properties.agentProductId,
                UUID.randomUUID().toString()),
            thumbprint,
            properties.agentProductId,
            verified.packageName,
            verified.securityLevel,
            verified.verifiedBootState,
            verified.deviceLocked,
            clock.instant())
    identity.deviceBinding = deviceBinding
    return Outcome.accepted(repository.save(identity))
  }

  companion object {
    /**
     * RFC 7638 JWK 지문. 신원의 실질적 키다.
     *
     * `ECKey.Builder` 는 (java.security.interfaces.ECPublicKey) 단독 생성자를 제공하지 않고
     * `Curve` 를 함께 요구한다. 공개키의 EC 파라미터에서 곡선을 역으로 알아낸다.
     */
    fun thumbprintOf(verified: AttestationResult.Verified): String {
      return try {
        val publicKey = verified.publicKey as ECPublicKey
        val curve = Curve.forECParameterSpec(publicKey.params)
        ECKey.Builder(curve, publicKey).build().computeThumbprint().toString()
      } catch (e: Exception) {
        throw IllegalStateException("공개키 지문을 계산하지 못했다", e)
      }
    }
  }
}
