package dev.starryeye.agentidentity.api

import dev.starryeye.agentidentity.identity.AgentIdentity
import dev.starryeye.agentidentity.identity.AgentIdentityRepository
import dev.starryeye.agentidentity.identity.CredentialIssuer
import dev.starryeye.agentidentity.proof.JwsProofVerifier
import dev.starryeye.agentidentity.proof.ProofType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.anyString
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * `CredentialController` 의 거절 갈래를 웹 계층에서 시험한다. 이전까지는 이 컨트롤러가
 * 수동 curl 결과로만 검증돼 있었다 — 자동화된 회귀 방지가 없었다.
 *
 * `ChallengeStore`/`RegistrationService` 와 달리 `JwsProofVerifier`·`AgentIdentityRepository`·
 * `CredentialIssuer`·`Clock` 은 이 컨트롤러의 협력자 동작 자체를 스텁해야 하므로
 * `@MockitoBean` 으로 대체한다. `PolicyProperties` 는 여기서 목으로 만들지 않는다 —
 * `@WebMvcTest` 가 가장 가까운 `@SpringBootConfiguration`(`ServerApplication`)의
 * `@EnableConfigurationProperties(PolicyProperties::class)` 를 그대로 가져와 이미 실제
 * 인스턴스를 등록해 두므로, 직접 빈을 하나 더 정의하면 후보가 둘이 돼 애매해진다.
 * `maxAttestationAge` 는 그 클래스의 기본값(7일)을 그대로 쓴다.
 */
@WebMvcTest(CredentialController::class)
class CredentialControllerTest {

  @Autowired private lateinit var mockMvc: MockMvc

  @MockitoBean private lateinit var proofs: JwsProofVerifier
  @MockitoBean private lateinit var repository: AgentIdentityRepository
  @MockitoBean private lateinit var credentials: CredentialIssuer
  @MockitoBean private lateinit var clock: Clock

  companion object {
    private val NOW: Instant = Instant.parse("2026-08-28T12:00:00Z")

    // AgentIdentity 는 생성자에서 항상 status="ACTIVE" 로 시작하고, 그 뒤로 바꿀 뮤테이터가
    // 없다 — 그래서 `authenticate` 가 돌려주는 `AuthenticationOutcome.AgentInactive`(→
    // AGENT_INACTIVE) 갈래를 이 테스트로는 재현할 수 없다. 이것은 이전 라운드에서 고치지
    // 않기로 한(리뷰에서 "later triage" 로 분류된) 별개의 결함이고, 이번 라운드(등록되지
    // 않은 키 구분)에서도 범위 밖이다. 프로덕션 코드에 뮤테이터를 새로 추가해 이 갈래만을
    // 위한 시험 통로를 만들지 않는다 — 그건 범위를 넘는 프로덕션 변경이다. 그래서 이 테스트
    // 파일은 ACTIVE 필터의 반대쪽(비활성 신원)은 다루지 않고, DPoP 검증·지문 조회(등록됨/
    // 등록 안 됨)·max-attestation-age 갈래만 다룬다.
    private fun identity(lastAttestedAt: Instant): AgentIdentity =
        AgentIdentity(
            "agent-1",
            "thumb-1",
            "galaxy-personal-agent",
            "dev.starryeye.ondeviceagent",
            "TRUSTED_ENVIRONMENT",
            "VERIFIED",
            true,
            lastAttestedAt)

    /**
     * `Mockito.eq(T)` 를 코틀린 non-null 매개변수에 직접 넘기면 죽는다 — 제네릭 `eq(T)` 는
     * (원본 값이 아니라) `Primitives.defaultValue(value.getClass())` 를 돌려주는데,
     * `String`/`ProofType` 처럼 래퍼 타입이 아닌 참조 타입에는 그 기본값이 `null`이기
     * 때문이다. `RegistrationServicePopTest.anyKt()` 와 같은 우회를 쓴다: `eq()` 호출은
     * matcher 등록이라는 부수효과만 쓰고, 실제로 넘겨줄 값은 원래 값 그 자체를 그대로
     * 돌려준다.
     */
    private fun <T> eqKt(value: T): T {
      Mockito.eq(value)
      return value
    }
  }

  @Test
  fun `DPoP_헤더가_없으면_400을_돌려준다`() {
    mockMvc.perform(get("/agent/whoami")).andExpect(status().isBadRequest)
  }

  @Test
  fun `whoami_에_유효하지_않은_DPoP_이면_401_DPOP_INVALID_를_돌려준다`() {
    Mockito.`when`(proofs.verify(eqKt("garbage"), eqKt(ProofType.DPOP), anyString(), anyString()))
        .thenReturn(null)

    mockMvc
        .perform(get("/agent/whoami").header("DPoP", "garbage"))
        .andExpect(status().isUnauthorized)
        .andExpect(jsonPath("$.reason").value("DPOP_INVALID"))
  }

  @Test
  fun `whoami_에_유효한_DPoP_이면_agentId_를_돌려준다`() {
    Mockito.`when`(proofs.verify(eqKt("valid-proof"), eqKt(ProofType.DPOP), anyString(), anyString()))
        .thenReturn("thumb-1")
    Mockito.`when`(repository.findByJwkThumbprint("thumb-1"))
        .thenReturn(identity(NOW))

    mockMvc
        .perform(get("/agent/whoami").header("DPoP", "valid-proof"))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.agentId").value("agent-1"))
  }

  @Test
  fun `refresh_는_max_attestation_age_를_넘기면_REATTESTATION_REQUIRED_를_돌려준다`() {
    // lastAttestedAt 이 8일 전이고 maxAttestationAge(설정, 7일)를 넘었다 — 그 개인키를
    // 지금 쥐고 있다는 것(DPoP)은 여전히 맞지만, TEE 증명 자체가 너무 오래됐다.
    val staleIdentity = identity(NOW.minus(Duration.ofDays(8)))
    Mockito.`when`(proofs.verify(eqKt("valid-proof"), eqKt(ProofType.DPOP), anyString(), anyString()))
        .thenReturn("thumb-1")
    Mockito.`when`(repository.findByJwkThumbprint("thumb-1")).thenReturn(staleIdentity)
    Mockito.`when`(clock.instant()).thenReturn(NOW)

    mockMvc
        .perform(post("/agent/credential").header("DPoP", "valid-proof"))
        .andExpect(status().isUnauthorized)
        .andExpect(jsonPath("$.reason").value("REATTESTATION_REQUIRED"))
  }

  @Test
  fun `refresh_는_max_attestation_age_이내이면_새_자격증명을_돌려준다`() {
    val freshIdentity = identity(NOW.minus(Duration.ofDays(6)))
    Mockito.`when`(proofs.verify(eqKt("valid-proof"), eqKt(ProofType.DPOP), anyString(), anyString()))
        .thenReturn("thumb-1")
    Mockito.`when`(repository.findByJwkThumbprint("thumb-1")).thenReturn(freshIdentity)
    Mockito.`when`(clock.instant()).thenReturn(NOW)
    Mockito.`when`(repository.save(freshIdentity)).thenReturn(freshIdentity)
    Mockito.`when`(credentials.issue(freshIdentity)).thenReturn("signed-jwt")

    mockMvc
        .perform(post("/agent/credential").header("DPoP", "valid-proof"))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.agentId").value("agent-1"))
        .andExpect(jsonPath("$.credential").value("signed-jwt"))
  }

  @Test
  fun `refresh_에_유효하지_않은_DPoP_이면_401_DPOP_INVALID_를_돌려준다`() {
    Mockito.`when`(proofs.verify(eqKt("garbage"), eqKt(ProofType.DPOP), anyString(), anyString()))
        .thenReturn(null)

    mockMvc
        .perform(post("/agent/credential").header("DPoP", "garbage"))
        .andExpect(status().isUnauthorized)
        .andExpect(jsonPath("$.reason").value("DPOP_INVALID"))
  }

  @Test
  fun `whoami_에_등록되지_않은_키의_유효한_DPoP_이면_401_AGENT_NOT_FOUND_를_돌려준다`() {
    // proof 서명 자체는 진짜다 — 다만 그 지문으로 등록된 신원이 없다. DPOP_INVALID 로
    // 뭉뚱그리면 클라이언트가 "재등록해도 되는 실패"인지 구분할 수 없다.
    Mockito.`when`(proofs.verify(eqKt("valid-proof"), eqKt(ProofType.DPOP), anyString(), anyString()))
        .thenReturn("thumb-never-registered")
    Mockito.`when`(repository.findByJwkThumbprint("thumb-never-registered")).thenReturn(null)

    mockMvc
        .perform(get("/agent/whoami").header("DPoP", "valid-proof"))
        .andExpect(status().isUnauthorized)
        .andExpect(jsonPath("$.reason").value("AGENT_NOT_FOUND"))
  }

  @Test
  fun `refresh_에_등록되지_않은_키의_유효한_DPoP_이면_401_AGENT_NOT_FOUND_를_돌려준다`() {
    Mockito.`when`(proofs.verify(eqKt("valid-proof"), eqKt(ProofType.DPOP), anyString(), anyString()))
        .thenReturn("thumb-never-registered")
    Mockito.`when`(repository.findByJwkThumbprint("thumb-never-registered")).thenReturn(null)

    mockMvc
        .perform(post("/agent/credential").header("DPoP", "valid-proof"))
        .andExpect(status().isUnauthorized)
        .andExpect(jsonPath("$.reason").value("AGENT_NOT_FOUND"))
  }
}
