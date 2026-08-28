package dev.starryeye.agentidentity.identity

import org.springframework.data.jpa.repository.JpaRepository

interface AgentIdentityRepository : JpaRepository<AgentIdentity, String> {
  fun findByJwkThumbprint(jwkThumbprint: String): AgentIdentity?
}
