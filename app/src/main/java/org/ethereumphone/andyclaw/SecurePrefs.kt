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
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.LlmProvider
import org.ethereumphone.andyclaw.agent.BudgetPreset
import org.ethereumphone.andyclaw.skills.RoutingPreset
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities
import kotlinx.serialization.encodeToString
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

  private val _safetyEnabled = MutableStateFlow(prefs.getBoolean("agent.safetyEnabled", false))
  val safetyEnabled: StateFlow<Boolean> = _safetyEnabled

  private val _notificationReplyEnabled = MutableStateFlow(prefs.getBoolean("agent.notificationReplyEnabled", true))
  val notificationReplyEnabled: StateFlow<Boolean> = _notificationReplyEnabled

  private val _executiveSummaryEnabled = MutableStateFlow(prefs.getBoolean("agent.executiveSummaryEnabled", true))
  val executiveSummaryEnabled: StateFlow<Boolean> = _executiveSummaryEnabled

  private val _heartbeatOnNotificationEnabled = MutableStateFlow(prefs.getBoolean("agent.heartbeatOnNotification", false))
  val heartbeatOnNotificationEnabled: StateFlow<Boolean> = _heartbeatOnNotificationEnabled

  private val _heartbeatOnXmtpMessageEnabled = MutableStateFlow(prefs.getBoolean("agent.heartbeatOnXmtpMessage", false))
  val heartbeatOnXmtpMessageEnabled: StateFlow<Boolean> = _heartbeatOnXmtpMessageEnabled

  private val _heartbeatIntervalMinutes = MutableStateFlow(prefs.getInt("agent.heartbeatIntervalMinutes", 30))
  val heartbeatIntervalMinutes: StateFlow<Int> = _heartbeatIntervalMinutes

  private val _heartbeatUseSameModel = MutableStateFlow(prefs.getBoolean("agent.heartbeatUseSameModel", true))
  val heartbeatUseSameModel: StateFlow<Boolean> = _heartbeatUseSameModel

  private val _heartbeatProvider = MutableStateFlow(loadHeartbeatProvider())
  val heartbeatProvider: StateFlow<LlmProvider> = _heartbeatProvider

  private val _heartbeatModel = MutableStateFlow(prefs.getString("agent.heartbeatModel", null) ?: "")
  val heartbeatModel: StateFlow<String> = _heartbeatModel

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

  private val _claudeOauthRefreshToken = MutableStateFlow(prefs.getString("claude.oauth.refreshToken", "") ?: "")
  val claudeOauthRefreshToken: StateFlow<String> = _claudeOauthRefreshToken

  private val _claudeOauthAccessToken = MutableStateFlow(prefs.getString("claude.oauth.accessToken", "") ?: "")
  val claudeOauthAccessToken: StateFlow<String> = _claudeOauthAccessToken

  private val _claudeOauthExpiresAt = MutableStateFlow(prefs.getLong("claude.oauth.expiresAt", 0L))
  val claudeOauthExpiresAt: StateFlow<Long> = _claudeOauthExpiresAt

  private val _openaiApiKey = MutableStateFlow(prefs.getString("openai.apiKey", "") ?: "")
  val openaiApiKey: StateFlow<String> = _openaiApiKey

  private val _veniceApiKey = MutableStateFlow(prefs.getString("venice.apiKey", "") ?: "")
  val veniceApiKey: StateFlow<String> = _veniceApiKey

  private val _selectedModel = MutableStateFlow(prefs.getString("anthropic.model", "kimi-k2-5") ?: "kimi-k2-5")
  val selectedModel: StateFlow<String> = _selectedModel

  private val _aiName = MutableStateFlow(prefs.getString("ai.name", "AndyClaw") ?: "AndyClaw")
  val aiName: StateFlow<String> = _aiName

  private val _enabledSkills = MutableStateFlow(loadEnabledSkills())
  val enabledSkills: StateFlow<Set<String>> = _enabledSkills

  private val _budgetModeEnabled = MutableStateFlow(prefs.getBoolean("budget.enabled", true))
  val budgetModeEnabled: StateFlow<Boolean> = _budgetModeEnabled

  private val _selectedBudgetPresetId = MutableStateFlow(prefs.getString("budget.presetId", BudgetPreset.defaultPresetId) ?: BudgetPreset.defaultPresetId)
  val selectedBudgetPresetId: StateFlow<String> = _selectedBudgetPresetId

  private val _budgetPresets = MutableStateFlow(loadBudgetPresets())
  val budgetPresets: StateFlow<List<BudgetPreset>> = _budgetPresets

  private val _smartRoutingEnabled = MutableStateFlow(prefs.getBoolean("routing.enabled", true))
  val smartRoutingEnabled: StateFlow<Boolean> = _smartRoutingEnabled

  private val _selectedRoutingPresetId = MutableStateFlow(prefs.getString("routing.presetId", "stock_minimal") ?: "stock_minimal")
  val selectedRoutingPresetId: StateFlow<String> = _selectedRoutingPresetId

  private val _routingPresets = MutableStateFlow(loadRoutingPresets())
  val routingPresets: StateFlow<List<RoutingPreset>> = _routingPresets

  private val _routingMode = MutableStateFlow(
    org.ethereumphone.andyclaw.skills.RoutingMode.fromString(prefs.getString("routing.mode", "moderate"))
  )
  val routingMode: StateFlow<org.ethereumphone.andyclaw.skills.RoutingMode> = _routingMode

  private val _routingUseSameModel = MutableStateFlow(prefs.getBoolean("routing.useSameModel", false))
  val routingUseSameModel: StateFlow<Boolean> = _routingUseSameModel

  private val _routingProvider = MutableStateFlow(loadRoutingProvider())
  val routingProvider: StateFlow<LlmProvider> = _routingProvider

  private val _routingModel = MutableStateFlow(
    prefs.getString("routing.model", "")?.takeIf { it.isNotEmpty() }
      ?: (AnthropicModels.routingModelForProvider(_routingProvider.value)?.modelId ?: "")
  )
  val routingModel: StateFlow<String> = _routingModel

  // ── Model routing (difficulty-based model selection, part of smart router) ──

  /** When true, the smart router also switches models based on task difficulty. */
  private val _modelRoutingEnabled = MutableStateFlow(prefs.getBoolean("routing.modelRouting.enabled", false))
  val modelRoutingEnabled: StateFlow<Boolean> = _modelRoutingEnabled

  /** User-preferred model ID for LIGHT (easy) tasks. Empty = auto-select. */
  private val _modelRoutingLight = MutableStateFlow(prefs.getString("routing.modelRouting.light", "") ?: "")
  val modelRoutingLight: StateFlow<String> = _modelRoutingLight

  /** User-preferred model ID for STANDARD (medium) tasks. Empty = auto-select. */
  private val _modelRoutingStandard = MutableStateFlow(prefs.getString("routing.modelRouting.standard", "") ?: "")
  val modelRoutingStandard: StateFlow<String> = _modelRoutingStandard

  /** User-preferred model ID for POWERFUL (hard) tasks. Empty = auto-select. */
  private val _modelRoutingPowerful = MutableStateFlow(prefs.getString("routing.modelRouting.powerful", "") ?: "")
  val modelRoutingPowerful: StateFlow<String> = _modelRoutingPowerful

  private val _googleOauthClientId = MutableStateFlow(prefs.getString("google.oauth.clientId", "") ?: "")
  val googleOauthClientId: StateFlow<String> = _googleOauthClientId

  private val _googleOauthClientSecret = MutableStateFlow(prefs.getString("google.oauth.clientSecret", "") ?: "")
  val googleOauthClientSecret: StateFlow<String> = _googleOauthClientSecret

  private val _googleOauthRefreshToken = MutableStateFlow(prefs.getString("google.oauth.refreshToken", "") ?: "")
  val googleOauthRefreshToken: StateFlow<String> = _googleOauthRefreshToken

  private val _googleOauthAccessToken = MutableStateFlow(prefs.getString("google.oauth.accessToken", "") ?: "")
  val googleOauthAccessToken: StateFlow<String> = _googleOauthAccessToken

  private val _googleOauthExpiresAt = MutableStateFlow(prefs.getLong("google.oauth.expiresAt", 0L))
  val googleOauthExpiresAt: StateFlow<Long> = _googleOauthExpiresAt

  private val _telegramBotToken = MutableStateFlow(prefs.getString("telegram.botToken", "") ?: "")
  val telegramBotToken: StateFlow<String> = _telegramBotToken

  private val _telegramBotEnabled = MutableStateFlow(prefs.getBoolean("telegram.botEnabled", false))
  val telegramBotEnabled: StateFlow<Boolean> = _telegramBotEnabled

  private val _telegramOwnerChatId = MutableStateFlow(prefs.getLong("telegram.ownerChatId", 0L))
  val telegramOwnerChatId: StateFlow<Long> = _telegramOwnerChatId

  private val _ledMaxBrightness = MutableStateFlow(prefs.getInt("led.maxBrightness", 255))
  val ledMaxBrightness: StateFlow<Int> = _ledMaxBrightness

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

  fun setSafetyEnabled(value: Boolean) {
    prefs.edit { putBoolean("agent.safetyEnabled", value) }
    _safetyEnabled.value = value
  }

  fun setNotificationReplyEnabled(value: Boolean) {
    prefs.edit { putBoolean("agent.notificationReplyEnabled", value) }
    _notificationReplyEnabled.value = value
  }

  fun setExecutiveSummaryEnabled(value: Boolean) {
    prefs.edit { putBoolean("agent.executiveSummaryEnabled", value) }
    _executiveSummaryEnabled.value = value
    // Also write to Settings.Secure so SystemUI can read the enabled state
    try {
      android.provider.Settings.Secure.putInt(
        appContext.contentResolver, "executive_summary_enabled", if (value) 1 else 0
      )
    } catch (_: Exception) { }
  }

  fun setHeartbeatOnNotificationEnabled(value: Boolean) {
    prefs.edit { putBoolean("agent.heartbeatOnNotification", value) }
    _heartbeatOnNotificationEnabled.value = value
  }

  fun setHeartbeatOnXmtpMessageEnabled(value: Boolean) {
    prefs.edit { putBoolean("agent.heartbeatOnXmtpMessage", value) }
    _heartbeatOnXmtpMessageEnabled.value = value
  }

  fun setHeartbeatUseSameModel(value: Boolean) {
    prefs.edit { putBoolean("agent.heartbeatUseSameModel", value) }
    _heartbeatUseSameModel.value = value
  }

  fun setHeartbeatProvider(provider: LlmProvider) {
    prefs.edit { putString("agent.heartbeatProvider", provider.name) }
    _heartbeatProvider.value = provider
  }

  fun setHeartbeatModel(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("agent.heartbeatModel", trimmed) }
    _heartbeatModel.value = trimmed
  }

  fun setHeartbeatIntervalMinutes(value: Int) {
    val stored = if (value <= 0) -1 else value.coerceIn(5, 1440)
    prefs.edit { putInt("agent.heartbeatIntervalMinutes", stored) }
    _heartbeatIntervalMinutes.value = stored
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

  fun setClaudeOauthRefreshToken(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("claude.oauth.refreshToken", trimmed) }
    _claudeOauthRefreshToken.value = trimmed
  }

  fun setClaudeOauthAccessToken(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("claude.oauth.accessToken", trimmed) }
    _claudeOauthAccessToken.value = trimmed
  }

  fun setClaudeOauthExpiresAt(value: Long) {
    prefs.edit { putLong("claude.oauth.expiresAt", value) }
    _claudeOauthExpiresAt.value = value
  }

  fun setOpenaiApiKey(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("openai.apiKey", trimmed) }
    _openaiApiKey.value = trimmed
  }

  fun setVeniceApiKey(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("venice.apiKey", trimmed) }
    _veniceApiKey.value = trimmed
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

  fun setBudgetModeEnabled(enabled: Boolean) {
    prefs.edit { putBoolean("budget.enabled", enabled) }
    _budgetModeEnabled.value = enabled
  }

  fun setSelectedBudgetPresetId(id: String) {
    val trimmed = id.trim()
    prefs.edit { putString("budget.presetId", trimmed) }
    _selectedBudgetPresetId.value = trimmed
  }

  fun setBudgetPresets(presets: List<BudgetPreset>) {
    val encoded = json.encodeToString(presets)
    prefs.edit { putString("budget.presets", encoded) }
    _budgetPresets.value = presets
  }

  fun setSmartRoutingEnabled(enabled: Boolean) {
    prefs.edit { putBoolean("routing.enabled", enabled) }
    _smartRoutingEnabled.value = enabled
  }

  fun setSelectedRoutingPresetId(id: String) {
    val trimmed = id.trim()
    prefs.edit { putString("routing.presetId", trimmed) }
    _selectedRoutingPresetId.value = trimmed
  }

  fun setRoutingPresets(presets: List<RoutingPreset>) {
    val encoded = json.encodeToString(presets)
    prefs.edit { putString("routing.presets", encoded) }
    _routingPresets.value = presets
  }

  fun setRoutingMode(mode: org.ethereumphone.andyclaw.skills.RoutingMode) {
    prefs.edit { putString("routing.mode", mode.name.lowercase()) }
    _routingMode.value = mode
  }

  fun setRoutingUseSameModel(value: Boolean) {
    prefs.edit { putBoolean("routing.useSameModel", value) }
    _routingUseSameModel.value = value
  }

  fun setRoutingProvider(provider: LlmProvider) {
    prefs.edit { putString("routing.provider", provider.name) }
    _routingProvider.value = provider
  }

  fun setRoutingModel(modelId: String) {
    prefs.edit { putString("routing.model", modelId) }
    _routingModel.value = modelId
  }

  // ── Model routing setters ─────────────────────────────────────────

  fun setModelRoutingEnabled(enabled: Boolean) {
    prefs.edit { putBoolean("routing.modelRouting.enabled", enabled) }
    _modelRoutingEnabled.value = enabled
  }

  fun setModelRoutingLight(modelId: String) {
    val trimmed = modelId.trim()
    prefs.edit { putString("routing.modelRouting.light", trimmed) }
    _modelRoutingLight.value = trimmed
  }

  fun setModelRoutingStandard(modelId: String) {
    val trimmed = modelId.trim()
    prefs.edit { putString("routing.modelRouting.standard", trimmed) }
    _modelRoutingStandard.value = trimmed
  }

  fun setModelRoutingPowerful(modelId: String) {
    val trimmed = modelId.trim()
    prefs.edit { putString("routing.modelRouting.powerful", trimmed) }
    _modelRoutingPowerful.value = trimmed
  }

  fun setGoogleOauthClientId(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("google.oauth.clientId", trimmed) }
    _googleOauthClientId.value = trimmed
  }

  fun setGoogleOauthClientSecret(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("google.oauth.clientSecret", trimmed) }
    _googleOauthClientSecret.value = trimmed
  }

  fun setGoogleOauthRefreshToken(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("google.oauth.refreshToken", trimmed) }
    _googleOauthRefreshToken.value = trimmed
  }

  fun setGoogleOauthAccessToken(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("google.oauth.accessToken", trimmed) }
    _googleOauthAccessToken.value = trimmed
  }

  fun setGoogleOauthExpiresAt(value: Long) {
    prefs.edit { putLong("google.oauth.expiresAt", value) }
    _googleOauthExpiresAt.value = value
  }

  fun clearGoogleOauthSetup() {
    prefs.edit {
      putString("google.oauth.clientId", "")
      putString("google.oauth.clientSecret", "")
      putString("google.oauth.refreshToken", "")
      putString("google.oauth.accessToken", "")
      putLong("google.oauth.expiresAt", 0L)
    }
    _googleOauthClientId.value = ""
    _googleOauthClientSecret.value = ""
    _googleOauthRefreshToken.value = ""
    _googleOauthAccessToken.value = ""
    _googleOauthExpiresAt.value = 0L
  }

  fun setTelegramBotToken(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("telegram.botToken", trimmed) }
    _telegramBotToken.value = trimmed
  }

  fun setTelegramBotEnabled(value: Boolean) {
    prefs.edit { putBoolean("telegram.botEnabled", value) }
    _telegramBotEnabled.value = value
  }

  fun setTelegramOwnerChatId(value: Long) {
    prefs.edit { putLong("telegram.ownerChatId", value) }
    _telegramOwnerChatId.value = value
  }

  fun setLedMaxBrightness(value: Int) {
    val clamped = value.coerceIn(0, 255)
    prefs.edit { putInt("led.maxBrightness", clamped) }
    _ledMaxBrightness.value = clamped
  }

  fun clearTelegramSetup() {
    prefs.edit {
      putString("telegram.botToken", "")
      putLong("telegram.ownerChatId", 0L)
      putBoolean("telegram.botEnabled", false)
    }
    _telegramBotToken.value = ""
    _telegramOwnerChatId.value = 0L
    _telegramBotEnabled.value = false
  }

  private fun loadBudgetPresets(): List<BudgetPreset> {
    val raw = prefs.getString("budget.presets", null)?.trim()
    if (raw.isNullOrEmpty()) return BudgetPreset.defaults()
    return try {
      json.decodeFromString<List<BudgetPreset>>(raw)
    } catch (_: Throwable) {
      BudgetPreset.defaults()
    }
  }

  private fun loadRoutingPresets(): List<RoutingPreset> {
    val raw = prefs.getString("routing.presets", null)?.trim()
    if (raw.isNullOrEmpty()) return RoutingPreset.defaults()
    return try {
      json.decodeFromString<List<RoutingPreset>>(raw)
    } catch (_: Throwable) {
      RoutingPreset.defaults()
    }
  }

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
    return LlmProvider.fromName(raw ?: "")
      ?: if (OsCapabilities.hasPrivilegedAccess) LlmProvider.ETHOS_PREMIUM else LlmProvider.OPEN_ROUTER
  }

  private fun loadHeartbeatProvider(): LlmProvider {
    val raw = prefs.getString("agent.heartbeatProvider", null)
    return LlmProvider.fromName(raw ?: "") ?: loadSelectedProvider()
  }

  private fun loadRoutingProvider(): LlmProvider {
    val raw = prefs.getString("routing.provider", null)
    return LlmProvider.fromName(raw ?: "") ?: loadSelectedProvider()
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
