package org.ethereumphone.andyclaw.gateway

interface KeyValueStore {
  fun getString(key: String): String?
  fun putString(key: String, value: String)
  fun remove(key: String)
}

class DeviceAuthStore(private val store: KeyValueStore) {
  fun loadToken(deviceId: String, role: String): String? {
    val key = tokenKey(deviceId, role)
    return store.getString(key)?.trim()?.takeIf { it.isNotEmpty() }
  }

  fun saveToken(deviceId: String, role: String, token: String) {
    val key = tokenKey(deviceId, role)
    store.putString(key, token.trim())
  }

  fun clearToken(deviceId: String, role: String) {
    val key = tokenKey(deviceId, role)
    store.remove(key)
  }

  private fun tokenKey(deviceId: String, role: String): String {
    val normalizedDevice = deviceId.trim().lowercase()
    val normalizedRole = role.trim().lowercase()
    return "gateway.deviceToken.$normalizedDevice.$normalizedRole"
  }
}
