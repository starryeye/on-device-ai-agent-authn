package dev.starryeye.ondeviceagent.identity

import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 등록 흐름. challenge → 키 생성 → 체인 제출 → 신원 수령.
 *
 * 사용자와 무관하게 앱 최초 실행 시 자동으로 일어난다. 대화도 모델도 관여하지 않는다.
 */
class AgentRegistrar(
  private val baseUrl: String,
  private val keys: AgentKeyStore,
  private val proofs: JwsProofSigner,
  private val deviceBinding: DeviceBindingProvider = NoDeviceBinding,
  private val integrity: IntegrityTokenProvider = NoIntegrityToken,
) {

  suspend fun register(): AgentIdentityState = withContext(Dispatchers.IO) {
    try {
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
      AgentIdentityState.Registered(response.getString("agentId"))
    } catch (e: RegistrationRejected) {
      AgentIdentityState.Failed(e.reason)
    } catch (e: Exception) {
      AgentIdentityState.Failed(e.message ?: e::class.simpleName ?: "unknown")
    }
  }

  /** 발급받은 자격증명이 실제로 통하는지 확인한다. 완료 기준 3. */
  suspend fun whoami(): String = withContext(Dispatchers.IO) {
    val url = "$baseUrl/agent/whoami"
    JSONObject(send("GET", url, null, proofs.dpop("GET", url))).getString("agentId")
  }

  /** attestation 없이 자격증명을 새로 받는다. 완료 기준 5. */
  suspend fun refreshCredential(): String = withContext(Dispatchers.IO) {
    val url = "$baseUrl/agent/credential"
    JSONObject(send("POST", url, "", proofs.dpop("POST", url))).getString("credential")
  }

  private class RegistrationRejected(val reason: String) : Exception(reason)

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
