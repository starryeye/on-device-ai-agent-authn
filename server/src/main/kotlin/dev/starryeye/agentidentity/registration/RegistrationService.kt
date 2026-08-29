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
import dev.starryeye.agentidentity.proof.JwsProofVerifier
import dev.starryeye.agentidentity.proof.ProofType
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
    private val proofs: JwsProofVerifier,
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
      pop: String?,
      url: String,
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

    val thumbprint = thumbprintOf(verified)

    // 체인은 "TEE 가 이 키를 이 challenge 와 함께 만들었다"만 증명한다. 그 개인키를 지금
    // 이 순간 쥐고 있다는 것은 별개의 사실이며, 그것을 증명하는 것이 PoP 다. attested 키가
    // 있어야 서명자를 비교할 수 있으므로 체인 검증보다 먼저 올 수 없다.
    if (!proofOfPossessionValid(pop, thumbprint, challenge, url)) {
      return Outcome.rejected(RejectionReason.POP_INVALID)
    }

    val rejected = policy.evaluate(verified, deviceBinding, integrityToken)
    if (rejected != null) {
      return Outcome.rejected(rejected)
    }

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

  /**
   * 등록 PoP 가 지금 등록 중인 attested 키로 서명됐고, 이 등록 거래의 challenge 를
   * 가리키는지 확인한다.
   *
   * 세 가지가 모두 맞아야 통과한다: (1) `JwsProofVerifier` 가 `typ`·서명·`htm`/`htu`·`iat`
   * 오차·`jti` 재생을 확인하고, (2) 서명자의 지문이 방금 [thumbprintOf] 로 뽑은 attested
   * 키의 지문과 같아야 하고 — 다르면 체인은 훔쳤지만 다른 키로 서명한 경우다 — (3) proof
   * 안의 `challenge` 클레임이 이 거래에서 소비한 challenge 와 같아야 한다. (3)이 없으면
   * 한 등록 거래용으로 만든 유효한 proof 를 challenge 만 다른 별개의 거래에 들이밀 수
   * 있다 — `jti` 재생 방지는 "같은 토큰 재사용"만 막지 "다른 거래에 들이밀기"는 막지 않는다.
   */
  private fun proofOfPossessionValid(
      pop: String?,
      attestedThumbprint: String,
      challenge: ByteArray,
      url: String,
  ): Boolean {
    if (pop == null) {
      return false
    }
    val signerThumbprint = proofs.verify(pop, ProofType.REGISTRATION, "POST", url) ?: return false
    if (signerThumbprint != attestedThumbprint) {
      return false
    }
    val claimedChallenge = proofs.claim(pop, "challenge") ?: return false
    return claimedChallenge == ChallengeStore.encode(challenge)
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
