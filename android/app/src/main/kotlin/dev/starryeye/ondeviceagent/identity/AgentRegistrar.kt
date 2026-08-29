package dev.starryeye.ondeviceagent.identity

import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 신원 확립 흐름.
 *
 * 앱 최초 실행 시 대화와 무관하게 자동으로 일어난다. **키가 없으면 등록하고, 있으면
 * 자격증명만 갱신한다** — 그래야 재시작해도 같은 신원이 유지된다. 기존 키를 새 challenge 로
 * 다시 attest 할 방법은 없다([AgentKeyStore.createKey] 참고). 그래서 재등록은 늘 새 키·새
 * 신원을 뜻하고, 서버가 재등록을 허가하는 사유(아래 [ensureIdentity] 참고)로 갱신을 거절할
 * 때만 그 값을 치른다.
 */
class AgentRegistrar(
  private val baseUrl: String,
  private val keys: AgentKeyStore,
  private val proofs: JwsProofSigner,
  private val deviceBinding: DeviceBindingProvider = NoDeviceBinding,
  private val integrity: IntegrityTokenProvider = NoIntegrityToken,
) {

  /**
   * 신원을 확립한다. challenge → 키 생성 → 체인 제출 → 신원 수령(최초 실행), 또는
   * DPoP 로 자격증명만 갱신(재실행).
   *
   * - 키가 없다 → 새로 등록한다([RegistrationOrigin.FIRST_RUN]).
   * - 키가 있다 → attestation 을 다시 하지 않고 자격증명만 갱신한다([RegistrationOrigin.REUSED]).
   * - 갱신이 `REATTESTATION_REQUIRED` 로 거절된다 → attestation 이 너무 오래돼 더 이상
   *   연장할 수 없다는 서버의 판단이다. 새 키로 다시 등록한다(새 신원이 된다).
   * - 갱신이 `AGENT_NOT_FOUND` 로 거절된다 → proof 자체는 유효한데(이 키를 지금 쥐고 있다는
   *   것은 맞다) 서버에 이 키로 등록된 신원이 없다. 등록 도중(체인 제출 전후) 프로세스가
   *   죽어 키만 만들어지고 서버에는 남지 않은 경우가 전형적이다. 새 키로 다시 등록해
   *   복구한다.
   * - 그 외의 거절 사유(`DPOP_INVALID`, `AGENT_INACTIVE` 등)는 그대로 실패로 보고한다 —
   *   proof 가 위조·재생·만료됐거나 신원이 관리자에 의해 비활성화된 경우까지 재등록의
   *   신호로 받아들이면, 가짜 proof 로 새 신원을 얻거나 비활성화를 그냥 우회하게 된다.
   */
  suspend fun ensureIdentity(): AgentIdentityState = withContext(Dispatchers.IO) {
    try {
      if (keys.hasKey()) {
        try {
          val refreshed = doRefreshCredential()
          return@withContext AgentIdentityState.Registered(refreshed.agentId, RegistrationOrigin.REUSED)
        } catch (e: RegistrationRejected) {
          val origin = reregistrationOriginFor(e.reason) ?: return@withContext AgentIdentityState.Failed(e.reason)
          return@withContext registerWithNewKey(origin)
        }
      }
      registerWithNewKey(RegistrationOrigin.FIRST_RUN)
    } catch (e: RegistrationRejected) {
      AgentIdentityState.Failed(e.reason)
    } catch (e: Exception) {
      AgentIdentityState.Failed(e.message ?: e::class.simpleName ?: "unknown")
    }
  }

  private suspend fun registerWithNewKey(origin: RegistrationOrigin): AgentIdentityState {
    val challengeResponse = JSONObject(send("POST", "$baseUrl/agent/registration/challenge", null))
    val registrationId = challengeResponse.getString("registrationId")
    val challenge = challengeResponse.getString("challenge")

    val chain = keys.createKey(Base64.getUrlDecoder().decode(challenge))

    val body =
      JSONObject()
        .put("registrationId", registrationId)
        .put(
          "attestationChain",
          JSONArray(chain.map { Base64.getEncoder().encodeToString(it.encoded) }),
        )
        .put("pop", proofs.registrationPop("$baseUrl/agent/registration", challenge))
        .put("deviceBinding", deviceBinding.deviceBinding())
        .put("playIntegrityToken", integrity.integrityToken())

    val response = JSONObject(send("POST", "$baseUrl/agent/registration", body.toString()))
    return AgentIdentityState.Registered(response.getString("agentId"), origin)
  }

  /** 발급받은 자격증명이 실제로 통하는지 확인한다. 완료 기준 3. */
  suspend fun whoami(): String = withContext(Dispatchers.IO) {
    val url = "$baseUrl/agent/whoami"
    JSONObject(send("GET", url, null, proofs.dpop("GET", url))).getString("agentId")
  }

  /** attestation 없이 자격증명을 새로 받는다. 완료 기준 5. */
  suspend fun refreshCredential(): CredentialRefresh = withContext(Dispatchers.IO) { doRefreshCredential() }

  private fun doRefreshCredential(): CredentialRefresh {
    val url = "$baseUrl/agent/credential"
    val response = JSONObject(send("POST", url, "", proofs.dpop("POST", url)))
    return CredentialRefresh(response.getString("agentId"), response.getString("credential"))
  }

  /** [refreshCredential] 의 결과. */
  data class CredentialRefresh(val agentId: String, val credential: String)

  private class RegistrationRejected(val reason: String) : Exception(reason)

  companion object {
    /**
     * 갱신 거절 사유 중 "새 키로 다시 등록해도 안전하다"에 해당하는 것만 [RegistrationOrigin]
     * 으로 옮긴다.
     *
     * `internal` 로 열어 둔 이유는 시험 때문이다 — 이 매핑은 순수한 `String -> RegistrationOrigin?`
     * 함수라 안드로이드 의존성이 전혀 없는데, 이 함수를 호출하는 [ensureIdentity] 는 실제
     * 하드웨어 Keystore([AgentKeyStore])를 요구해 JVM 단위 테스트에서 인스턴스를 만들 수
     * 없다. 특히 음의 절반(`DPOP_INVALID`·`AGENT_INACTIVE` 는 재등록을 트리거하면 안 된다)이
     * 예전 라운드에서 재생 공격/폐기 우회를 막으려고 세운 불변식이다 — 여기 `else` 분기를
     * 허용형으로 바꾸는 회귀가 조용히 그 구멍을 다시 연다.
     */
    internal fun reregistrationOriginFor(reason: String): RegistrationOrigin? =
      when (reason) {
        "REATTESTATION_REQUIRED" -> RegistrationOrigin.REATTESTATION_REQUIRED
        "AGENT_NOT_FOUND" -> RegistrationOrigin.AGENT_NOT_FOUND
        else -> null
      }
  }

  private fun send(method: String, url: String, body: String?, dpop: String? = null): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = method
      connection.connectTimeout = 10_000
      connection.readTimeout = 20_000
      dpop?.let { connection.setRequestProperty("DPoP", it) }
      if (body != null) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray()) }
      }
      if (connection.responseCode !in 200..299) {
        val error = connection.errorStream?.bufferedReader()?.readText().orEmpty()
        val reason =
          runCatching { JSONObject(error).getString("reason") }
            .getOrDefault("HTTP ${connection.responseCode}")
        throw RegistrationRejected(reason)
      }
      return connection.inputStream.bufferedReader().readText()
    } finally {
      connection.disconnect()
    }
  }
}
