@file:Suppress("DEPRECATION")

package org.ethereumphone.andyclaw

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.ethereumphone.andyclaw.gateway.KeyValueStore
import org.ethereumphone.andyclaw.llm.LlmProvider
import java.util.UUID

class SecurePrefs(context: Context) : KeyValueStore {
  companion object {
    val defaultWakeWords: List<String> = listOf("openclaw", "claude")
    private const val displayNameKey = "node.displayName"
    private const val voiceWakeModeKey = "voiceWake.mode"
  }

  private val appContext = context.applicationContext
  private val json = Json { ignoreUnknownKeys = true }

  private val masterKey =
    MasterKey.Builder(context)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()

  private val prefs: SharedPreferences by lazy {
    createPrefs(appContext, "openclaw.node.secure")
  }

  private val _instanceId = MutableStateFlow(loadOrCreateInstanceId())
  val instanceId: StateFlow<String> = _instanceId

  private val _displayName =
    MutableStateFlow(loadOrMigrateDisplayName(context = context))
  val displayName: StateFlow<String> = _displayName

  private val _cameraEnabled = MutableStateFlow(prefs.getBoolean("camera.enabled", true))
  val cameraEnabled: StateFlow<Boolean> = _cameraEnabled

  private val _locationMode =
    MutableStateFlow(LocationMode.fromRawValue(prefs.getString("location.enabledMode", "off")))
  val locationMode: StateFlow<LocationMode> = _locationMode

  private val _locationPreciseEnabled =
    MutableStateFlow(prefs.getBoolean("location.preciseEnabled", true))
  val locationPreciseEnabled: StateFlow<Boolean> = _locationPreciseEnabled

  private val _preventSleep = MutableStateFlow(prefs.getBoolean("screen.preventSleep", true))
  val preventSleep: StateFlow<Boolean> = _preventSleep

  private val _manualEnabled =
    MutableStateFlow(prefs.getBoolean("gateway.manual.enabled", false))
  val manualEnabled: StateFlow<Boolean> = _manualEnabled

  private val _manualHost =
    MutableStateFlow(prefs.getString("gateway.manual.host", "") ?: "")
  val manualHost: StateFlow<String> = _manualHost

  private val _manualPort =
    MutableStateFlow(prefs.getInt("gateway.manual.port", 18789))
  val manualPort: StateFlow<Int> = _manualPort

  private val _manualTls =
    MutableStateFlow(prefs.getBoolean("gateway.manual.tls", true))
  val manualTls: StateFlow<Boolean> = _manualTls

  private val _lastDiscoveredStableId =
    MutableStateFlow(
      prefs.getString("gateway.lastDiscoveredStableID", "") ?: "",
    )
  val lastDiscoveredStableId: StateFlow<String> = _lastDiscoveredStableId

  private val _canvasDebugStatusEnabled =
    MutableStateFlow(prefs.getBoolean("canvas.debugStatusEnabled", false))
  val canvasDebugStatusEnabled: StateFlow<Boolean> = _canvasDebugStatusEnabled

  private val _wakeWords = MutableStateFlow(loadWakeWords())
  val wakeWords: StateFlow<List<String>> = _wakeWords

  private val _voiceWakeMode = MutableStateFlow(loadVoiceWakeMode())
  val voiceWakeMode: StateFlow<VoiceWakeMode> = _voiceWakeMode

  private val _talkEnabled = MutableStateFlow(prefs.getBoolean("talk.enabled", false))
  val talkEnabled: StateFlow<Boolean> = _talkEnabled

  private val _yoloMode = MutableStateFlow(prefs.getBoolean("agent.yoloMode", false))
  val yoloMode: StateFlow<Boolean> = _yoloMode

  private val _notificationReplyEnabled = MutableStateFlow(prefs.getBoolean("agent.notificationReplyEnabled", true))
  val notificationReplyEnabled: StateFlow<Boolean> = _notificationReplyEnabled

  private val _heartbeatOnNotificationEnabled = MutableStateFlow(prefs.getBoolean("agent.heartbeatOnNotification", false))
  val heartbeatOnNotificationEnabled: StateFlow<Boolean> = _heartbeatOnNotificationEnabled

  private val _heartbeatOnXmtpMessageEnabled = MutableStateFlow(prefs.getBoolean("agent.heartbeatOnXmtpMessage", false))
  val heartbeatOnXmtpMessageEnabled: StateFlow<Boolean> = _heartbeatOnXmtpMessageEnabled

  private val _heartbeatIntervalMinutes = MutableStateFlow(prefs.getInt("agent.heartbeatIntervalMinutes", 30))
  val heartbeatIntervalMinutes: StateFlow<Int> = _heartbeatIntervalMinutes

  private val _walletAddress = MutableStateFlow(prefs.getString("auth.walletAddress", "") ?: "")
  val walletAddress: StateFlow<String> = _walletAddress

  private val _walletSignature = MutableStateFlow(prefs.getString("auth.walletSignature", "") ?: "")
  val walletSignature: StateFlow<String> = _walletSignature

  private val _apiKey = MutableStateFlow(prefs.getString("anthropic.apiKey", "") ?: "")
  val apiKey: StateFlow<String> = _apiKey

  private val _selectedProvider = MutableStateFlow(loadSelectedProvider())
  val selectedProvider: StateFlow<LlmProvider> = _selectedProvider

  private val _tinfoilApiKey = MutableStateFlow(prefs.getString("tinfoil.apiKey", "") ?: "")
  val tinfoilApiKey: StateFlow<String> = _tinfoilApiKey

  private val _selectedModel = MutableStateFlow(prefs.getString("anthropic.model", "minimax/minimax-m2.5") ?: "minimax/minimax-m2.5")
  val selectedModel: StateFlow<String> = _selectedModel

  private val _aiName = MutableStateFlow(prefs.getString("ai.name", "AndyClaw") ?: "AndyClaw")
  val aiName: StateFlow<String> = _aiName

  private val _enabledSkills = MutableStateFlow(loadEnabledSkills())
  val enabledSkills: StateFlow<Set<String>> = _enabledSkills

  fun setLastDiscoveredStableId(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("gateway.lastDiscoveredStableID", trimmed) }
    _lastDiscoveredStableId.value = trimmed
  }

  fun setDisplayName(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString(displayNameKey, trimmed) }
    _displayName.value = trimmed
  }

  fun setCameraEnabled(value: Boolean) {
    prefs.edit { putBoolean("camera.enabled", value) }
    _cameraEnabled.value = value
  }

  fun setLocationMode(mode: LocationMode) {
    prefs.edit { putString("location.enabledMode", mode.rawValue) }
    _locationMode.value = mode
  }

  fun setLocationPreciseEnabled(value: Boolean) {
    prefs.edit { putBoolean("location.preciseEnabled", value) }
    _locationPreciseEnabled.value = value
  }

  fun setPreventSleep(value: Boolean) {
    prefs.edit { putBoolean("screen.preventSleep", value) }
    _preventSleep.value = value
  }

  fun setManualEnabled(value: Boolean) {
    prefs.edit { putBoolean("gateway.manual.enabled", value) }
    _manualEnabled.value = value
  }

  fun setManualHost(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("gateway.manual.host", trimmed) }
    _manualHost.value = trimmed
  }

  fun setManualPort(value: Int) {
    prefs.edit { putInt("gateway.manual.port", value) }
    _manualPort.value = value
  }

  fun setManualTls(value: Boolean) {
    prefs.edit { putBoolean("gateway.manual.tls", value) }
    _manualTls.value = value
  }

  fun setCanvasDebugStatusEnabled(value: Boolean) {
    prefs.edit { putBoolean("canvas.debugStatusEnabled", value) }
    _canvasDebugStatusEnabled.value = value
  }

  fun loadGatewayToken(): String? {
    val key = "gateway.token.${_instanceId.value}"
    val stored = prefs.getString(key, null)?.trim()
    return stored?.takeIf { it.isNotEmpty() }
  }

  fun saveGatewayToken(token: String) {
    val key = "gateway.token.${_instanceId.value}"
    prefs.edit { putString(key, token.trim()) }
  }

  fun loadGatewayPassword(): String? {
    val key = "gateway.password.${_instanceId.value}"
    val stored = prefs.getString(key, null)?.trim()
    return stored?.takeIf { it.isNotEmpty() }
  }

  fun saveGatewayPassword(password: String) {
    val key = "gateway.password.${_instanceId.value}"
    prefs.edit { putString(key, password.trim()) }
  }

  fun loadGatewayTlsFingerprint(stableId: String): String? {
    val key = "gateway.tls.$stableId"
    return prefs.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
  }

  fun saveGatewayTlsFingerprint(stableId: String, fingerprint: String) {
    val key = "gateway.tls.$stableId"
    prefs.edit { putString(key, fingerprint.trim()) }
  }

  override fun getString(key: String): String? {
    return prefs.getString(key, null)
  }

  override fun putString(key: String, value: String) {
    prefs.edit { putString(key, value) }
  }

  override fun remove(key: String) {
    prefs.edit { remove(key) }
  }

  private fun createPrefs(context: Context, name: String): SharedPreferences {
    return EncryptedSharedPreferences.create(
      context,
      name,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }

  private fun loadOrCreateInstanceId(): String {
    val existing = prefs.getString("node.instanceId", null)?.trim()
    if (!existing.isNullOrBlank()) return existing
    val fresh = UUID.randomUUID().toString()
    prefs.edit { putString("node.instanceId", fresh) }
    return fresh
  }

  private fun loadOrMigrateDisplayName(context: Context): String {
    val existing = prefs.getString(displayNameKey, null)?.trim().orEmpty()
    if (existing.isNotEmpty() && existing != "Android Node") return existing

    val candidate = DeviceNames.bestDefaultNodeName(context).trim()
    val resolved = candidate.ifEmpty { "Android Node" }

    prefs.edit { putString(displayNameKey, resolved) }
    return resolved
  }

  fun setWakeWords(words: List<String>) {
    val sanitized = WakeWords.sanitize(words, defaultWakeWords)
    val encoded =
      JsonArray(sanitized.map { JsonPrimitive(it) }).toString()
    prefs.edit { putString("voiceWake.triggerWords", encoded) }
    _wakeWords.value = sanitized
  }

  fun setVoiceWakeMode(mode: VoiceWakeMode) {
    prefs.edit { putString(voiceWakeModeKey, mode.rawValue) }
    _voiceWakeMode.value = mode
  }

  fun setTalkEnabled(value: Boolean) {
    prefs.edit { putBoolean("talk.enabled", value) }
    _talkEnabled.value = value
  }

  fun setYoloMode(value: Boolean) {
    prefs.edit { putBoolean("agent.yoloMode", value) }
    _yoloMode.value = value
  }

  fun setNotificationReplyEnabled(value: Boolean) {
    prefs.edit { putBoolean("agent.notificationReplyEnabled", value) }
    _notificationReplyEnabled.value = value
  }

  fun setHeartbeatOnNotificationEnabled(value: Boolean) {
    prefs.edit { putBoolean("agent.heartbeatOnNotification", value) }
    _heartbeatOnNotificationEnabled.value = value
  }

  fun setHeartbeatOnXmtpMessageEnabled(value: Boolean) {
    prefs.edit { putBoolean("agent.heartbeatOnXmtpMessage", value) }
    _heartbeatOnXmtpMessageEnabled.value = value
  }

  fun setHeartbeatIntervalMinutes(value: Int) {
    val clamped = value.coerceIn(5, 60)
    prefs.edit { putInt("agent.heartbeatIntervalMinutes", clamped) }
    _heartbeatIntervalMinutes.value = clamped
    // Notify OS heartbeat service to re-schedule at the new interval
    try {
      appContext.sendBroadcast(
        android.content.Intent("org.ethereumphone.andyclaw.HEARTBEAT_INTERVAL_CHANGED")
      )
    } catch (_: Exception) { }
  }

  fun setWalletAuth(address: String, signature: String) {
    prefs.edit {
      putString("auth.walletAddress", address.trim())
      putString("auth.walletSignature", signature.trim())
    }
    _walletAddress.value = address.trim()
    _walletSignature.value = signature.trim()
  }

  fun setApiKey(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("anthropic.apiKey", trimmed) }
    _apiKey.value = trimmed
  }

  fun setSelectedProvider(provider: LlmProvider) {
    prefs.edit { putString("llm.provider", provider.name) }
    _selectedProvider.value = provider
  }

  fun setTinfoilApiKey(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("tinfoil.apiKey", trimmed) }
    _tinfoilApiKey.value = trimmed
  }

  fun setSelectedModel(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("anthropic.model", trimmed) }
    _selectedModel.value = trimmed
  }

  fun setAiName(value: String) {
    val trimmed = value.trim().takeIf { it.isNotEmpty() } ?: "AndyClaw"
    prefs.edit { putString("ai.name", trimmed) }
    _aiName.value = trimmed
  }

  fun setSkillEnabled(skillId: String, enabled: Boolean) {
    val current = _enabledSkills.value.toMutableSet()
    if (enabled) current.add(skillId) else current.remove(skillId)
    val updated = current.toSet()
    val encoded = JsonArray(updated.map { JsonPrimitive(it) }).toString()
    prefs.edit { putString("agent.enabledSkills", encoded) }
    _enabledSkills.value = updated
  }

  fun setAllSkillsEnabled(skillIds: Set<String>) {
    val encoded = JsonArray(skillIds.map { JsonPrimitive(it) }).toString()
    prefs.edit { putString("agent.enabledSkills", encoded) }
    _enabledSkills.value = skillIds
  }

  fun isSkillEnabled(skillId: String): Boolean = skillId in _enabledSkills.value

  private fun loadEnabledSkills(): Set<String> {
    val raw = prefs.getString("agent.enabledSkills", null)?.trim()
    if (raw.isNullOrEmpty()) return emptySet()
    return try {
      val element = json.parseToJsonElement(raw)
      val array = element as? JsonArray ?: return emptySet()
      array.mapNotNull { item ->
        when (item) {
          is JsonNull -> null
          is JsonPrimitive -> item.content.trim().takeIf { it.isNotEmpty() }
          else -> null
        }
      }.toSet()
    } catch (_: Throwable) {
      emptySet()
    }
  }

  private fun loadSelectedProvider(): LlmProvider {
    val raw = prefs.getString("llm.provider", null)
    return LlmProvider.fromName(raw ?: "") ?: LlmProvider.OPEN_ROUTER
  }

  private fun loadVoiceWakeMode(): VoiceWakeMode {
    val raw = prefs.getString(voiceWakeModeKey, null)
    val resolved = VoiceWakeMode.fromRawValue(raw)

    if (raw.isNullOrBlank()) {
      prefs.edit { putString(voiceWakeModeKey, resolved.rawValue) }
    }

    return resolved
  }

  private fun loadWakeWords(): List<String> {
    val raw = prefs.getString("voiceWake.triggerWords", null)?.trim()
    if (raw.isNullOrEmpty()) return defaultWakeWords
    return try {
      val element = json.parseToJsonElement(raw)
      val array = element as? JsonArray ?: return defaultWakeWords
      val decoded =
        array.mapNotNull { item ->
          when (item) {
            is JsonNull -> null
            is JsonPrimitive -> item.content.trim().takeIf { it.isNotEmpty() }
            else -> null
          }
        }
      WakeWords.sanitize(decoded, defaultWakeWords)
    } catch (_: Throwable) {
      defaultWakeWords
    }
  }
}
