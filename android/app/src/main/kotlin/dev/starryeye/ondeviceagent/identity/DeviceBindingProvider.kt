package dev.starryeye.ondeviceagent.identity

/**
 * 기기 자체를 가리키는 증거. 1st-party 경로(Device ID / Knox attestation)가 열리면 구현이
 * 들어온다. 소매 기기에서는 얻을 수 없다.
 */
fun interface DeviceBindingProvider {
  suspend fun deviceBinding(): String?
}

/** 지금 유일한 구현. */
object NoDeviceBinding : DeviceBindingProvider {
  override suspend fun deviceBinding(): String? = null
}
