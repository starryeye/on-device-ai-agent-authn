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
import org.springframework.dao.DataIntegrityViolationException
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

    return try {
      Outcome.accepted(repository.save(identity))
    } catch (e: DataIntegrityViolationException) {
      // 같은 키로 동시에 들어온 첫 등록끼리의 경쟁. `findByJwkThumbprint` 로 아직 아무도
      // 없다고 읽은 두 요청이 동시에 새 신원을 만들어 저장을 시도하면, 유니크 제약이 뒤늦게
      // 도착한 쪽의 삽입을 막는다 — 그 제약은 최후 방어선이지 조율 수단이 아니다. 진 쪽은
      // 예외로 실패하는 대신 이미 이긴 쪽이 만든 신원을 다시 찾아 그것을 결과로 돌려준다.
      // 그래야 멱등성이 "같은 키는 같은 신원" 이 아니라 "먼저 요청한 쪽만" 으로 깨지지 않는다.
      val winner =
          repository.findByJwkThumbprint(thumbprint)
              ?: throw IllegalStateException(
                  "유니크 제약 위반 후에도 신원을 찾지 못했다: thumbprint=$thumbprint", e)
      winner.markAttested(clock.instant())
      Outcome.accepted(repository.save(winner))
    }
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
