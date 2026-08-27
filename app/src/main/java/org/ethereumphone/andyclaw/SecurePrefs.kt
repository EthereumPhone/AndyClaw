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

  // The provenance gate — what a run triggered by somebody else's content may do.
  // Defaults to ENFORCING: this is the boundary that keeps a stranger's XMTP or
  // Telegram message away from the promptless agent sub-account. Turning it off
  // makes the gate log-only, which is the shape to run a build in while watching
  // real traffic for over-blocking, and is not a state to leave a device in.
  private val _provenanceEnforcementEnabled =
    MutableStateFlow(prefs.getBoolean("agent.provenanceEnforcement", true))
  val provenanceEnforcementEnabled: StateFlow<Boolean> = _provenanceEnforcementEnabled

  private val _notificationReplyEnabled = MutableStateFlow(prefs.getBoolean("agent.notificationReplyEnabled", false))
  val notificationReplyEnabled: StateFlow<Boolean> = _notificationReplyEnabled

  private val _executiveSummaryEnabled = MutableStateFlow(prefs.getBoolean("agent.executiveSummaryEnabled", false))
  val executiveSummaryEnabled: StateFlow<Boolean> = _executiveSummaryEnabled

  private val _heartbeatOnNotificationEnabled = MutableStateFlow(prefs.getBoolean("agent.heartbeatOnNotification", false))
  val heartbeatOnNotificationEnabled: StateFlow<Boolean> = _heartbeatOnNotificationEnabled

  private val _heartbeatOnXmtpMessageEnabled = MutableStateFlow(prefs.getBoolean("agent.heartbeatOnXmtpMessage", false))
  val heartbeatOnXmtpMessageEnabled: StateFlow<Boolean> = _heartbeatOnXmtpMessageEnabled

  // Default is -1 (disabled). The UI treats `interval > 0` as enabled and
  // calls setHeartbeatIntervalMinutes(-1) to disable. Default-off so that fresh
  // installs do not silently consume LLM tokens on a periodic schedule the
  // user never opted into — opt-in via Settings → Heartbeat.
  private val _heartbeatIntervalMinutes = MutableStateFlow(prefs.getInt("agent.heartbeatIntervalMinutes", -1))
  val heartbeatIntervalMinutes: StateFlow<Int> = _heartbeatIntervalMinutes

  private val _heartbeatUseSameModel = MutableStateFlow(prefs.getBoolean("agent.heartbeatUseSameModel", true))
  val heartbeatUseSameModel: StateFlow<Boolean> = _heartbeatUseSameModel

  private val _heartbeatProvider = MutableStateFlow(loadHeartbeatProvider())
  val heartbeatProvider: StateFlow<LlmProvider> = _heartbeatProvider

  private val _heartbeatModel = MutableStateFlow(prefs.getString("agent.heartbeatModel", null) ?: "")
  val heartbeatModel: StateFlow<String> = _heartbeatModel

  private val _compactionConfig = MutableStateFlow(loadCompactionConfig())
  val compactionConfig: StateFlow<org.ethereumphone.andyclaw.agent.CompactionConfig> = _compactionConfig

  private val _compactionUseSameModel = MutableStateFlow(prefs.getBoolean("compaction.useSameModel", true))
  val compactionUseSameModel: StateFlow<Boolean> = _compactionUseSameModel

  private val _compactionProvider = MutableStateFlow(loadCompactionProvider())
  val compactionProvider: StateFlow<LlmProvider> = _compactionProvider

  private val _compactionModel = MutableStateFlow(prefs.getString("compaction.model", null) ?: "")
  val compactionModel: StateFlow<String> = _compactionModel

  // Memory AI (shared by smart extraction + AI reranking)
  private val _memoryAiUseSameModel = MutableStateFlow(prefs.getBoolean("memory.ai.useSameModel", true))
  val memoryAiUseSameModel: StateFlow<Boolean> = _memoryAiUseSameModel

  private val _memoryAiProvider = MutableStateFlow(loadMemoryAiProvider())
  val memoryAiProvider: StateFlow<LlmProvider> = _memoryAiProvider

  private val _memoryAiModel = MutableStateFlow(prefs.getString("memory.ai.model", null) ?: "")
  val memoryAiModel: StateFlow<String> = _memoryAiModel

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

  // ── ChatGPT OAuth (Codex backend) ─────────────────────────────────────
  private val _chatgptOauthRefreshToken = MutableStateFlow(prefs.getString("chatgpt.oauth.refreshToken", "") ?: "")
  val chatgptOauthRefreshToken: StateFlow<String> = _chatgptOauthRefreshToken
  private val _chatgptOauthAccessToken = MutableStateFlow(prefs.getString("chatgpt.oauth.accessToken", "") ?: "")
  val chatgptOauthAccessToken: StateFlow<String> = _chatgptOauthAccessToken
  private val _chatgptOauthExpiresAt = MutableStateFlow(prefs.getLong("chatgpt.oauth.expiresAt", 0L))
  val chatgptOauthExpiresAt: StateFlow<Long> = _chatgptOauthExpiresAt
  /** ChatGPT account ID extracted from the id_token (required header on every request). */
  private val _chatgptOauthAccountId = MutableStateFlow(prefs.getString("chatgpt.oauth.accountId", "") ?: "")
  val chatgptOauthAccountId: StateFlow<String> = _chatgptOauthAccountId

  private val _claudeOauthAccessToken = MutableStateFlow(prefs.getString("claude.oauth.accessToken", "") ?: "")
  val claudeOauthAccessToken: StateFlow<String> = _claudeOauthAccessToken

  private val _claudeOauthExpiresAt = MutableStateFlow(prefs.getLong("claude.oauth.expiresAt", 0L))
  val claudeOauthExpiresAt: StateFlow<Long> = _claudeOauthExpiresAt

  private val _openaiApiKey = MutableStateFlow(prefs.getString("openai.apiKey", "") ?: "")
  val openaiApiKey: StateFlow<String> = _openaiApiKey

  private val _veniceApiKey = MutableStateFlow(
    (prefs.getString("venice.apiKey", "") ?: "").ifBlank {
      // Dev convenience: when no key has been set in-app, fall back to the
      // VENICE_API value from local.properties (wired via BuildConfig in
      // app/build.gradle.kts). Debug builds only — never bake a key into release.
      if (BuildConfig.DEBUG) BuildConfig.VENICE_API else ""
    }
  )
  val veniceApiKey: StateFlow<String> = _veniceApiKey

  // ── Custom (self-hosted, OpenAI-compatible) endpoint ────────────────────
  // For Ollama / LM Studio / vLLM / llama.cpp-server / LocalAI etc. The user
  // provides the full chat completions URL, an optional API key (many self-
  // hosted servers don't require auth on the LAN), and the model id their
  // backend serves.
  private val _customBaseUrl = MutableStateFlow(prefs.getString("custom.baseUrl", "") ?: "")
  val customBaseUrl: StateFlow<String> = _customBaseUrl
  private val _customApiKey = MutableStateFlow(prefs.getString("custom.apiKey", "") ?: "")
  val customApiKey: StateFlow<String> = _customApiKey
  private val _customModelId = MutableStateFlow(prefs.getString("custom.modelId", "") ?: "")
  val customModelId: StateFlow<String> = _customModelId

  // ── Local LLM (on-device, llama.cpp via Llamatik) ───────────────────────
  // Active GGUF — must be the filename of an entry in filesDir/models/ (see GgufRegistry).
  // Default = the model ModelDownloadManager downloads.
  private val _selectedGgufFilename = MutableStateFlow(prefs.getString("local.selectedGguf", org.ethereumphone.andyclaw.llm.DEFAULT_BUILTIN_GGUF) ?: org.ethereumphone.andyclaw.llm.DEFAULT_BUILTIN_GGUF)
  val selectedGgufFilename: StateFlow<String> = _selectedGgufFilename

  // Sampling params (applied per-generation via LlamaBridge.updateGenerateParams)
  private val _localLlmTemperature = MutableStateFlow(prefs.getFloat("local.temperature", 0.3f))
  val localLlmTemperature: StateFlow<Float> = _localLlmTemperature
  private val _localLlmTopP = MutableStateFlow(prefs.getFloat("local.topP", 0.9f))
  val localLlmTopP: StateFlow<Float> = _localLlmTopP
  private val _localLlmTopK = MutableStateFlow(prefs.getInt("local.topK", 40))
  val localLlmTopK: StateFlow<Int> = _localLlmTopK
  private val _localLlmMaxTokens = MutableStateFlow(prefs.getInt("local.maxTokens", 256))
  val localLlmMaxTokens: StateFlow<Int> = _localLlmMaxTokens
  private val _localLlmRepeatPenalty = MutableStateFlow(prefs.getFloat("local.repeatPenalty", 1.0f))
  val localLlmRepeatPenalty: StateFlow<Float> = _localLlmRepeatPenalty

  // Hardware params (applied at model init via LlamaBridge.initGenerateModelWithConfig)
  private val _localLlmNCtx = MutableStateFlow(prefs.getInt("local.nCtx", 4096))
  val localLlmNCtx: StateFlow<Int> = _localLlmNCtx
  private val _localLlmNBatch = MutableStateFlow(prefs.getInt("local.nBatch", 2048))
  val localLlmNBatch: StateFlow<Int> = _localLlmNBatch
  private val _localLlmNThreads = MutableStateFlow(prefs.getInt("local.nThreads", 0)) // 0 = auto
  val localLlmNThreads: StateFlow<Int> = _localLlmNThreads
  private val _localLlmNGpuLayers = MutableStateFlow(prefs.getInt("local.nGpuLayers", 0)) // 0 = CPU
  val localLlmNGpuLayers: StateFlow<Int> = _localLlmNGpuLayers
  private val _localLlmUseMmap = MutableStateFlow(prefs.getBoolean("local.useMmap", true))
  val localLlmUseMmap: StateFlow<Boolean> = _localLlmUseMmap

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

  private val _smartRoutingEnabled = MutableStateFlow(prefs.getBoolean("routing.enabled", false))
  val smartRoutingEnabled: StateFlow<Boolean> = _smartRoutingEnabled

  private val _toolSearchEnabled = MutableStateFlow(prefs.getBoolean("routing.toolSearchEnabled", true))
  val toolSearchEnabled: StateFlow<Boolean> = _toolSearchEnabled

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

  private val _syncProviderToAll = MutableStateFlow(prefs.getBoolean("sync.providerToAll", false))
  val syncProviderToAll: StateFlow<Boolean> = _syncProviderToAll

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
    val stored = value && _heartbeatIntervalMinutes.value > 0
    prefs.edit { putBoolean("agent.executiveSummaryEnabled", stored) }
    _executiveSummaryEnabled.value = stored
    // Also write to Settings.Secure so SystemUI can read the enabled state
    try {
      android.provider.Settings.Secure.putInt(
        appContext.contentResolver, "executive_summary_enabled", if (stored) 1 else 0
      )
    } catch (_: Exception) { }
  }

  fun setHeartbeatOnNotificationEnabled(value: Boolean) {
    val stored = value && _heartbeatIntervalMinutes.value > 0
    prefs.edit { putBoolean("agent.heartbeatOnNotification", stored) }
    _heartbeatOnNotificationEnabled.value = stored
  }

  fun setHeartbeatOnXmtpMessageEnabled(value: Boolean) {
    val stored = value && _heartbeatIntervalMinutes.value > 0
    prefs.edit { putBoolean("agent.heartbeatOnXmtpMessage", stored) }
    _heartbeatOnXmtpMessageEnabled.value = stored
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

  fun setCompactionConfig(config: org.ethereumphone.andyclaw.agent.CompactionConfig) {
    prefs.edit {
      putBoolean("compaction.enabled", config.enabled)
      putFloat("compaction.threshold", config.threshold)
      putInt("compaction.interval", config.interval)
      putInt("compaction.overlap", config.overlap)
      putInt("compaction.keepRecent", config.keepRecent)
      putBoolean("compaction.microcompact", config.microcompactEnabled)
      putBoolean("compaction.llmSummary", config.llmSummaryEnabled)
    }
    _compactionConfig.value = config
  }

  fun setCompactionUseSameModel(value: Boolean) {
    prefs.edit { putBoolean("compaction.useSameModel", value) }
    _compactionUseSameModel.value = value
  }

  fun setCompactionProvider(provider: LlmProvider) {
    prefs.edit { putString("compaction.provider", provider.name) }
    _compactionProvider.value = provider
  }

  fun setCompactionModel(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("compaction.model", trimmed) }
    _compactionModel.value = trimmed
  }

  // Memory AI model settings
  fun setMemoryAiUseSameModel(value: Boolean) {
    prefs.edit { putBoolean("memory.ai.useSameModel", value) }
    _memoryAiUseSameModel.value = value
  }

  fun setMemoryAiProvider(provider: LlmProvider) {
    prefs.edit { putString("memory.ai.provider", provider.name) }
    _memoryAiProvider.value = provider
  }

  fun setMemoryAiModel(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("memory.ai.model", trimmed) }
    _memoryAiModel.value = trimmed
  }

  fun setMemoryAiUserModelForProvider(provider: LlmProvider, modelId: String) {
    val trimmed = modelId.trim()
    prefs.edit { putString("memory.ai.model.${provider.name}", trimmed) }
    if (provider == _memoryAiProvider.value) {
      _memoryAiModel.value = trimmed
    }
  }

  /**
   * Enforce the provenance gate, or run it in log-only mode.
   *
   * Log-only still evaluates and logs every verdict (`adb logcat -s ExecEngineFactory`),
   * it just does not apply them — the way to see what a real device's traffic would
   * hit before the gate bites.
   */
  fun setProvenanceEnforcementEnabled(value: Boolean) {
    prefs.edit { putBoolean("agent.provenanceEnforcement", value) }
    _provenanceEnforcementEnabled.value = value
  }

  fun setHeartbeatIntervalMinutes(value: Int) {
    val stored = if (value <= 0) -1 else value.coerceIn(5, 1440)
    prefs.edit { putInt("agent.heartbeatIntervalMinutes", stored) }
    _heartbeatIntervalMinutes.value = stored
    // The master switch is a kill switch for every automatic child trigger.
    // Clear their persisted state as well as gating the call sites, so turning
    // heartbeat back on never silently restores background inference.
    if (stored < 0) {
      setHeartbeatOnNotificationEnabled(false)
      setHeartbeatOnXmtpMessageEnabled(false)
      setExecutiveSummaryEnabled(false)
    }
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
    // When switching INTO the CUSTOM provider, mirror the user's configured
    // customModelId into selectedModel so outgoing MessagesRequest.model
    // matches what their self-hosted backend serves. Without this, switching
    // to CUSTOM from another provider would leave a stale model id (e.g.
    // an OpenRouter "minimax/minimax-m2.5") and the backend would 404.
    if (provider == LlmProvider.CUSTOM) {
      val custom = _customModelId.value
      if (custom.isNotBlank()) setSelectedModel(custom)
    }
  }

  fun setTinfoilApiKey(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("tinfoil.apiKey", trimmed) }
    _tinfoilApiKey.value = trimmed
  }

  /** User-paste entry point: stores the new refresh token AND clears any cached
   *  access_token / expiry / accountId so they get re-derived on the next call.
   *  Use [silentlyUpdateChatGptOauthRefreshToken] from the refresh manager
   *  when the server rotates the refresh token (cache stays warm). */
  fun setChatGptOauthRefreshToken(value: String) {
    silentlyUpdateChatGptOauthRefreshToken(value)
    prefs.edit {
      putString("chatgpt.oauth.accessToken", "")
      putLong("chatgpt.oauth.expiresAt", 0L)
      putString("chatgpt.oauth.accountId", "")
    }
    _chatgptOauthAccessToken.value = ""
    _chatgptOauthExpiresAt.value = 0L
    _chatgptOauthAccountId.value = ""
  }
  /** Persist a server-rotated refresh token without clearing the access-token cache. */
  fun silentlyUpdateChatGptOauthRefreshToken(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("chatgpt.oauth.refreshToken", trimmed) }
    _chatgptOauthRefreshToken.value = trimmed
  }
  fun setChatGptOauthAccessToken(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("chatgpt.oauth.accessToken", trimmed) }
    _chatgptOauthAccessToken.value = trimmed
  }
  fun setChatGptOauthExpiresAt(value: Long) {
    prefs.edit { putLong("chatgpt.oauth.expiresAt", value) }
    _chatgptOauthExpiresAt.value = value
  }
  fun setChatGptOauthAccountId(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("chatgpt.oauth.accountId", trimmed) }
    _chatgptOauthAccountId.value = trimmed
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

  // ── Custom endpoint setters ────────────────────────────────────────────
  fun setCustomBaseUrl(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("custom.baseUrl", trimmed) }
    _customBaseUrl.value = trimmed
  }
  fun setCustomApiKey(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("custom.apiKey", trimmed) }
    _customApiKey.value = trimmed
  }
  /** Sets the model id served by the custom backend. When CUSTOM is the
   *  active provider, also mirrors the value into `selectedModel` so that
   *  the rest of the system (agent loop, routing, etc.) reads the same id
   *  when building outgoing MessagesRequest.model fields. */
  fun setCustomModelId(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("custom.modelId", trimmed) }
    _customModelId.value = trimmed
    if (_selectedProvider.value == LlmProvider.CUSTOM && trimmed.isNotBlank()) {
      setSelectedModel(trimmed)
    }
  }

  // ── Local LLM setters ──────────────────────────────────────────────────
  fun setSelectedGgufFilename(value: String) {
    prefs.edit { putString("local.selectedGguf", value) }
    _selectedGgufFilename.value = value
  }
  fun setLocalLlmTemperature(value: Float) {
    prefs.edit { putFloat("local.temperature", value) }
    _localLlmTemperature.value = value
  }
  fun setLocalLlmTopP(value: Float) {
    prefs.edit { putFloat("local.topP", value) }
    _localLlmTopP.value = value
  }
  fun setLocalLlmTopK(value: Int) {
    prefs.edit { putInt("local.topK", value) }
    _localLlmTopK.value = value
  }
  fun setLocalLlmMaxTokens(value: Int) {
    prefs.edit { putInt("local.maxTokens", value) }
    _localLlmMaxTokens.value = value
  }
  fun setLocalLlmRepeatPenalty(value: Float) {
    prefs.edit { putFloat("local.repeatPenalty", value) }
    _localLlmRepeatPenalty.value = value
  }
  fun setLocalLlmNCtx(value: Int) {
    prefs.edit { putInt("local.nCtx", value) }
    _localLlmNCtx.value = value
  }
  fun setLocalLlmNBatch(value: Int) {
    prefs.edit { putInt("local.nBatch", value) }
    _localLlmNBatch.value = value
  }
  fun setLocalLlmNThreads(value: Int) {
    prefs.edit { putInt("local.nThreads", value) }
    _localLlmNThreads.value = value
  }
  fun setLocalLlmNGpuLayers(value: Int) {
    prefs.edit { putInt("local.nGpuLayers", value) }
    _localLlmNGpuLayers.value = value
  }
  fun setLocalLlmUseMmap(value: Boolean) {
    prefs.edit { putBoolean("local.useMmap", value) }
    _localLlmUseMmap.value = value
  }

  /** Snapshot of the current local-LLM runtime config. */
  fun currentLocalLlmConfig(): org.ethereumphone.andyclaw.llm.LocalLlmRuntimeConfig =
    org.ethereumphone.andyclaw.llm.LocalLlmRuntimeConfig(
      temperature   = _localLlmTemperature.value,
      topP          = _localLlmTopP.value,
      topK          = _localLlmTopK.value,
      maxTokens     = _localLlmMaxTokens.value,
      repeatPenalty = _localLlmRepeatPenalty.value,
      nCtx          = _localLlmNCtx.value,
      nBatch        = _localLlmNBatch.value,
      nThreads      = _localLlmNThreads.value,
      nGpuLayers    = _localLlmNGpuLayers.value,
      useMmap       = _localLlmUseMmap.value,
    )

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

  fun setToolSearchEnabled(enabled: Boolean) {
    prefs.edit { putBoolean("routing.toolSearchEnabled", enabled) }
    _toolSearchEnabled.value = enabled
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

  fun setSyncProviderToAll(value: Boolean) {
    prefs.edit { putBoolean("sync.providerToAll", value) }
    _syncProviderToAll.value = value
  }

  /** Store the user's explicit heartbeat model choice for a specific provider. */
  fun setHeartbeatUserModelForProvider(provider: LlmProvider, modelId: String) {
    prefs.edit { putString("sync.heartbeat.userModel.${provider.name}", modelId.trim()) }
  }

  /** Retrieve the user's explicit heartbeat model choice for a provider, or null if none set. */
  fun getHeartbeatUserModelForProvider(provider: LlmProvider): String? {
    return prefs.getString("sync.heartbeat.userModel.${provider.name}", null)
      ?.trim()?.takeIf { it.isNotEmpty() }
  }

  /** Store the user's explicit routing model choice for a specific provider. */
  fun setRoutingUserModelForProvider(provider: LlmProvider, modelId: String) {
    prefs.edit { putString("sync.routing.userModel.${provider.name}", modelId.trim()) }
  }

  /** Retrieve the user's explicit routing model choice for a provider, or null if none set. */
  fun getRoutingUserModelForProvider(provider: LlmProvider): String? {
    return prefs.getString("sync.routing.userModel.${provider.name}", null)
      ?.trim()?.takeIf { it.isNotEmpty() }
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

  private fun loadCompactionConfig(): org.ethereumphone.andyclaw.agent.CompactionConfig {
    return org.ethereumphone.andyclaw.agent.CompactionConfig(
      enabled = prefs.getBoolean("compaction.enabled", false),
      threshold = prefs.getFloat("compaction.threshold", 0.85f),
      interval = prefs.getInt("compaction.interval", 0),
      overlap = prefs.getInt("compaction.overlap", 1),
      keepRecent = prefs.getInt("compaction.keepRecent", 6),
      microcompactEnabled = prefs.getBoolean("compaction.microcompact", true),
      llmSummaryEnabled = prefs.getBoolean("compaction.llmSummary", true),
    )
  }

  private fun loadCompactionProvider(): LlmProvider {
    val raw = prefs.getString("compaction.provider", null)
    return LlmProvider.fromName(raw ?: "") ?: loadSelectedProvider()
  }

  private fun loadMemoryAiProvider(): LlmProvider {
    val raw = prefs.getString("memory.ai.provider", null)
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

  /**
   * Export all preference values as a typed map for backup.
   * Returns pairs of (key, TypedValue) where type is preserved.
   */
  fun exportAllValues(): Map<String, Any?> {
    return prefs.all.toMap()
  }

  /**
   * Import preference values from a backup, replacing current values.
   * Skips the instanceId so the device keeps its unique identity.
   */
  fun importAllValues(values: Map<String, Any?>) {
    prefs.edit {
      // Clear everything except instanceId
      val currentInstanceId = prefs.getString("node.instanceId", null)
      clear()
      if (currentInstanceId != null) {
        putString("node.instanceId", currentInstanceId)
      }

      for ((key, value) in values) {
        if (key == "node.instanceId") continue // preserve device identity
        when (value) {
          is String -> putString(key, value)
          is Boolean -> putBoolean(key, value)
          is Int -> putInt(key, value)
          is Long -> putLong(key, value)
          is Float -> putFloat(key, value)
          is Set<*> -> {
            @Suppress("UNCHECKED_CAST")
            putStringSet(key, value as Set<String>)
          }
        }
      }
    }
    // Reload all in-memory StateFlows
    reloadAllFlows()
  }

  /** Refresh all MutableStateFlow fields from the current SharedPreferences values. */
  private fun reloadAllFlows() {
    _displayName.value = prefs.getString(displayNameKey, "Android Node") ?: "Android Node"
    _cameraEnabled.value = prefs.getBoolean("camera.enabled", true)
    _locationMode.value = LocationMode.fromRawValue(prefs.getString("location.enabledMode", "off"))
    _locationPreciseEnabled.value = prefs.getBoolean("location.preciseEnabled", true)
    _preventSleep.value = prefs.getBoolean("screen.preventSleep", true)
    _manualEnabled.value = prefs.getBoolean("gateway.manual.enabled", false)
    _manualHost.value = prefs.getString("gateway.manual.host", "") ?: ""
    _manualPort.value = prefs.getInt("gateway.manual.port", 18789)
    _manualTls.value = prefs.getBoolean("gateway.manual.tls", true)
    _lastDiscoveredStableId.value = prefs.getString("gateway.lastDiscoveredStableID", "") ?: ""
    _canvasDebugStatusEnabled.value = prefs.getBoolean("canvas.debugStatusEnabled", false)
    _wakeWords.value = loadWakeWords()
    _voiceWakeMode.value = loadVoiceWakeMode()
    _talkEnabled.value = prefs.getBoolean("talk.enabled", false)
    _yoloMode.value = prefs.getBoolean("agent.yoloMode", false)
    _safetyEnabled.value = prefs.getBoolean("agent.safetyEnabled", false)
    _provenanceEnforcementEnabled.value = prefs.getBoolean("agent.provenanceEnforcement", true)
    _notificationReplyEnabled.value = prefs.getBoolean("agent.notificationReplyEnabled", false)
    _executiveSummaryEnabled.value = prefs.getBoolean("agent.executiveSummaryEnabled", false)
    _heartbeatOnNotificationEnabled.value = prefs.getBoolean("agent.heartbeatOnNotification", false)
    _heartbeatOnXmtpMessageEnabled.value = prefs.getBoolean("agent.heartbeatOnXmtpMessage", false)
    _heartbeatIntervalMinutes.value = prefs.getInt("agent.heartbeatIntervalMinutes", -1)
    _heartbeatUseSameModel.value = prefs.getBoolean("agent.heartbeatUseSameModel", true)
    _heartbeatProvider.value = loadHeartbeatProvider()
    _heartbeatModel.value = prefs.getString("agent.heartbeatModel", null) ?: ""
    _compactionConfig.value = loadCompactionConfig()
    _compactionUseSameModel.value = prefs.getBoolean("compaction.useSameModel", true)
    _compactionProvider.value = loadCompactionProvider()
    _compactionModel.value = prefs.getString("compaction.model", null) ?: ""
    _memoryAiUseSameModel.value = prefs.getBoolean("memory.ai.useSameModel", true)
    _memoryAiProvider.value = loadMemoryAiProvider()
    _memoryAiModel.value = prefs.getString("memory.ai.model", null) ?: ""
    _walletAddress.value = prefs.getString("auth.walletAddress", "") ?: ""
    _walletSignature.value = prefs.getString("auth.walletSignature", "") ?: ""
    _apiKey.value = prefs.getString("anthropic.apiKey", "") ?: ""
    _selectedProvider.value = loadSelectedProvider()
    _tinfoilApiKey.value = prefs.getString("tinfoil.apiKey", "") ?: ""
    _claudeOauthRefreshToken.value = prefs.getString("claude.oauth.refreshToken", "") ?: ""
    _claudeOauthAccessToken.value = prefs.getString("claude.oauth.accessToken", "") ?: ""
    _claudeOauthExpiresAt.value = prefs.getLong("claude.oauth.expiresAt", 0L)
    _chatgptOauthRefreshToken.value = prefs.getString("chatgpt.oauth.refreshToken", "") ?: ""
    _chatgptOauthAccessToken.value = prefs.getString("chatgpt.oauth.accessToken", "") ?: ""
    _chatgptOauthExpiresAt.value = prefs.getLong("chatgpt.oauth.expiresAt", 0L)
    _chatgptOauthAccountId.value = prefs.getString("chatgpt.oauth.accountId", "") ?: ""
    _openaiApiKey.value = prefs.getString("openai.apiKey", "") ?: ""
    _veniceApiKey.value = (prefs.getString("venice.apiKey", "") ?: "").ifBlank {
      if (BuildConfig.DEBUG) BuildConfig.VENICE_API else ""
    }
    _customBaseUrl.value = prefs.getString("custom.baseUrl", "") ?: ""
    _customApiKey.value = prefs.getString("custom.apiKey", "") ?: ""
    _customModelId.value = prefs.getString("custom.modelId", "") ?: ""
    _selectedGgufFilename.value = prefs.getString("local.selectedGguf", org.ethereumphone.andyclaw.llm.DEFAULT_BUILTIN_GGUF) ?: org.ethereumphone.andyclaw.llm.DEFAULT_BUILTIN_GGUF
    _localLlmTemperature.value = prefs.getFloat("local.temperature", 0.3f)
    _localLlmTopP.value = prefs.getFloat("local.topP", 0.9f)
    _localLlmTopK.value = prefs.getInt("local.topK", 40)
    _localLlmMaxTokens.value = prefs.getInt("local.maxTokens", 256)
    _localLlmRepeatPenalty.value = prefs.getFloat("local.repeatPenalty", 1.0f)
    _localLlmNCtx.value = prefs.getInt("local.nCtx", 4096)
    _localLlmNBatch.value = prefs.getInt("local.nBatch", 2048)
    _localLlmNThreads.value = prefs.getInt("local.nThreads", 0)
    _localLlmNGpuLayers.value = prefs.getInt("local.nGpuLayers", 0)
    _localLlmUseMmap.value = prefs.getBoolean("local.useMmap", true)
    _selectedModel.value = prefs.getString("anthropic.model", "kimi-k2-5") ?: "kimi-k2-5"
    _aiName.value = prefs.getString("ai.name", "AndyClaw") ?: "AndyClaw"
    _enabledSkills.value = loadEnabledSkills()
    _budgetModeEnabled.value = prefs.getBoolean("budget.enabled", true)
    _selectedBudgetPresetId.value = prefs.getString("budget.presetId", BudgetPreset.defaultPresetId) ?: BudgetPreset.defaultPresetId
    _budgetPresets.value = loadBudgetPresets()
    _smartRoutingEnabled.value = prefs.getBoolean("routing.enabled", false)
    _toolSearchEnabled.value = prefs.getBoolean("routing.toolSearchEnabled", true)
    _selectedRoutingPresetId.value = prefs.getString("routing.presetId", "stock_minimal") ?: "stock_minimal"
    _routingPresets.value = loadRoutingPresets()
    _routingMode.value = org.ethereumphone.andyclaw.skills.RoutingMode.fromString(prefs.getString("routing.mode", "moderate"))
    _routingUseSameModel.value = prefs.getBoolean("routing.useSameModel", false)
    _routingProvider.value = loadRoutingProvider()
    _routingModel.value = prefs.getString("routing.model", "")?.takeIf { it.isNotEmpty() }
      ?: (AnthropicModels.routingModelForProvider(_routingProvider.value)?.modelId ?: "")
    _modelRoutingEnabled.value = prefs.getBoolean("routing.modelRouting.enabled", false)
    _modelRoutingLight.value = prefs.getString("routing.modelRouting.light", "") ?: ""
    _modelRoutingStandard.value = prefs.getString("routing.modelRouting.standard", "") ?: ""
    _modelRoutingPowerful.value = prefs.getString("routing.modelRouting.powerful", "") ?: ""
    _googleOauthClientId.value = prefs.getString("google.oauth.clientId", "") ?: ""
    _googleOauthClientSecret.value = prefs.getString("google.oauth.clientSecret", "") ?: ""
    _googleOauthRefreshToken.value = prefs.getString("google.oauth.refreshToken", "") ?: ""
    _googleOauthAccessToken.value = prefs.getString("google.oauth.accessToken", "") ?: ""
    _googleOauthExpiresAt.value = prefs.getLong("google.oauth.expiresAt", 0L)
    _telegramBotToken.value = prefs.getString("telegram.botToken", "") ?: ""
    _telegramBotEnabled.value = prefs.getBoolean("telegram.botEnabled", false)
    _telegramOwnerChatId.value = prefs.getLong("telegram.ownerChatId", 0L)
    _syncProviderToAll.value = prefs.getBoolean("sync.providerToAll", false)
    _ledMaxBrightness.value = prefs.getInt("led.maxBrightness", 255)
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
