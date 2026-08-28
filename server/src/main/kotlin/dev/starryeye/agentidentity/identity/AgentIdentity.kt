package dev.starryeye.agentidentity.identity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import java.time.Instant

/** 발급된 에이전트 신원. `jwkThumbprint` 가 실질적 키이며 등록은 이 값에 대해 멱등이다. */
@Entity
open class AgentIdentity protected constructor() {

  @Id
  lateinit var id: String
    private set

  @Column(nullable = false, unique = true)
  lateinit var jwkThumbprint: String
    private set

  var agentProductId: String? = null
    private set

  var packageName: String? = null
    private set

  var securityLevel: String? = null
    private set

  var verifiedBootState: String? = null
    private set

  var deviceLocked: Boolean = false
    private set

  var integrityVerdict: String? = null

  var deviceBinding: String? = null

  var subject: String? = null

  lateinit var createdAt: Instant
    private set

  lateinit var lastAttestedAt: Instant
    private set

  var lastAuthenticatedAt: Instant? = null
    private set

  lateinit var status: String
    private set

  constructor(
      id: String,
      jwkThumbprint: String,
      agentProductId: String,
      packageName: String,
      securityLevel: String,
      verifiedBootState: String,
      deviceLocked: Boolean,
      now: Instant,
  ) : this() {
    this.id = id
    this.jwkThumbprint = jwkThumbprint
    this.agentProductId = agentProductId
    this.packageName = packageName
    this.securityLevel = securityLevel
    this.verifiedBootState = verifiedBootState
    this.deviceLocked = deviceLocked
    this.createdAt = now
    this.lastAttestedAt = now
    this.status = "ACTIVE"
  }

  fun markAttested(now: Instant) {
    lastAttestedAt = now
  }

  fun markAuthenticated(now: Instant) {
    lastAuthenticatedAt = now
  }
}
