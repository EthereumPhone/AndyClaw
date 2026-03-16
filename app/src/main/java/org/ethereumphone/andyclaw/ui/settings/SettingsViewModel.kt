package org.ethereumphone.andyclaw.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.extensions.ExtensionDescriptor
import org.ethereumphone.andyclaw.extensions.toSkillAdapters
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.LlmProvider
import org.ethereumphone.andyclaw.llm.ModelDownloadManager
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import org.ethereumphone.andyclaw.PaymasterSDK
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.RoutingPreset
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NodeApp
    private val prefs = app.securePrefs

    val selectedModel = prefs.selectedModel
    val yoloMode = prefs.yoloMode
    val safetyEnabled = prefs.safetyEnabled
    val enabledSkills = prefs.enabledSkills
    val smartRoutingEnabled = prefs.smartRoutingEnabled
    val selectedRoutingPresetId = prefs.selectedRoutingPresetId
    val routingPresets = prefs.routingPresets
    val routingUseSameModel = prefs.routingUseSameModel
    val routingProvider = prefs.routingProvider
    val routingModel = prefs.routingModel
    val notificationReplyEnabled = prefs.notificationReplyEnabled
    val executiveSummaryEnabled = prefs.executiveSummaryEnabled
    val heartbeatOnNotificationEnabled = prefs.heartbeatOnNotificationEnabled
    val heartbeatOnXmtpMessageEnabled = prefs.heartbeatOnXmtpMessageEnabled
    val heartbeatIntervalMinutes = prefs.heartbeatIntervalMinutes
    val heartbeatUseSameModel = prefs.heartbeatUseSameModel
    val heartbeatProvider = prefs.heartbeatProvider
    val heartbeatModel = prefs.heartbeatModel

    val selectedProvider = prefs.selectedProvider
    val tinfoilApiKey = prefs.tinfoilApiKey
    val apiKey = prefs.apiKey
    val openaiApiKey = prefs.openaiApiKey
    val veniceApiKey = prefs.veniceApiKey
    val claudeOauthRefreshToken = prefs.claudeOauthRefreshToken

    val telegramBotEnabled = prefs.telegramBotEnabled
    val telegramOwnerChatId = prefs.telegramOwnerChatId
    val ledMaxBrightness = prefs.ledMaxBrightness

    val googleOauthRefreshToken = prefs.googleOauthRefreshToken
    val googleOauthClientId = prefs.googleOauthClientId
    val googleOauthClientSecret = prefs.googleOauthClientSecret

    val currentTier: String get() = OsCapabilities.currentTier().name
    val isPrivileged: Boolean get() = OsCapabilities.hasPrivilegedAccess

    /** Available models filtered by the currently selected provider. */
    val availableModels: List<AnthropicModels>
        get() {
            val provider = prefs.selectedProvider.value
            val effective = if (isPrivileged && provider == LlmProvider.LOCAL) LlmProvider.ETHOS_PREMIUM else provider
            return AnthropicModels.forProvider(effective)
        }

    val modelDownloadManager: ModelDownloadManager get() = app.modelDownloadManager

    val registeredSkills get() = app.nativeSkillRegistry.getAll()

    // ── Paymaster ────────────────────────────────────────────────────────

    private val paymasterSDK = PaymasterSDK(application)

    private val _paymasterBalance = MutableStateFlow<String?>(null)
    val paymasterBalance: StateFlow<String?> = _paymasterBalance.asStateFlow()

    private var balancePollingJob: Job? = null

    // ── Memory ──────────────────────────────────────────────────────────

    /** Reactive memory count — auto-updates when memories are added or deleted. */
    val memoryCount: StateFlow<Int> = app.memoryManager.observeCount()
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _autoStoreEnabled = MutableStateFlow(true)
    val autoStoreEnabled: StateFlow<Boolean> = _autoStoreEnabled.asStateFlow()

    private val _isReindexing = MutableStateFlow(false)
    val isReindexing: StateFlow<Boolean> = _isReindexing.asStateFlow()

    // ── Extensions ──────────────────────────────────────────────────────

    private val _extensions = MutableStateFlow<List<ExtensionDescriptor>>(emptyList())
    val extensions: StateFlow<List<ExtensionDescriptor>> = _extensions.asStateFlow()

    private val _isExtensionScanning = MutableStateFlow(false)
    val isExtensionScanning: StateFlow<Boolean> = _isExtensionScanning.asStateFlow()

    // ── Skill inspection ─────────────────────────────────────────────────

    private val _inspectedSkill = MutableStateFlow<InspectedSkillInfo?>(null)
    val inspectedSkill: StateFlow<InspectedSkillInfo?> = _inspectedSkill.asStateFlow()

    init {
        loadAutoStorePreference()
        refreshExtensions()
        if (isPrivileged) initPaymaster()
    }

    // ── Actions ─────────────────────────────────────────────────────────

    fun setSelectedProvider(provider: LlmProvider) {
        prefs.setSelectedProvider(provider)
        // Switch to the default model for the new provider
        val defaultModel = AnthropicModels.defaultForProvider(provider)
        prefs.setSelectedModel(defaultModel.modelId)
        // Unload local model when switching away from LOCAL
        if (provider != LlmProvider.LOCAL && app.llamaCpp.isModelLoaded) {
            app.llamaCpp.unload()
        }
    }

    fun setTinfoilApiKey(key: String) {
        prefs.setTinfoilApiKey(key)
    }

    fun setApiKey(key: String) {
        prefs.setApiKey(key)
    }

    fun setOpenaiApiKey(key: String) {
        prefs.setOpenaiApiKey(key)
    }

    fun setVeniceApiKey(key: String) {
        prefs.setVeniceApiKey(key)
    }

    fun setClaudeOauthRefreshToken(token: String) {
        prefs.setClaudeOauthRefreshToken(token)
        // Clear cached access token so the manager fetches a fresh one
        prefs.setClaudeOauthAccessToken("")
        prefs.setClaudeOauthExpiresAt(0L)
    }

    fun downloadLocalModel() {
        viewModelScope.launch {
            app.modelDownloadManager.download()
        }
    }

    fun deleteLocalModel() {
        app.modelDownloadManager.deleteModel()
        if (app.llamaCpp.isModelLoaded) {
            app.llamaCpp.unload()
        }
    }

    fun setSelectedModel(modelId: String) {
        prefs.setSelectedModel(modelId)
    }

    fun setYoloMode(enabled: Boolean) {
        prefs.setYoloMode(enabled)
        if (enabled) {
            prefs.setSkillEnabled("agent_display", true)
        }
    }

    fun setSafetyEnabled(enabled: Boolean) {
        prefs.setSafetyEnabled(enabled)
    }

    fun setNotificationReplyEnabled(enabled: Boolean) {
        prefs.setNotificationReplyEnabled(enabled)
    }

    fun setExecutiveSummaryEnabled(enabled: Boolean) {
        prefs.setExecutiveSummaryEnabled(enabled)
    }

    fun setHeartbeatOnNotificationEnabled(enabled: Boolean) {
        prefs.setHeartbeatOnNotificationEnabled(enabled)
    }

    fun setHeartbeatOnXmtpMessageEnabled(enabled: Boolean) {
        prefs.setHeartbeatOnXmtpMessageEnabled(enabled)
    }

    fun setHeartbeatIntervalMinutes(minutes: Int) {
        prefs.setHeartbeatIntervalMinutes(minutes)
    }

    fun setHeartbeatUseSameModel(enabled: Boolean) {
        prefs.setHeartbeatUseSameModel(enabled)
        if (!enabled) {
            // Initialize heartbeat provider/model from current global settings
            if (prefs.heartbeatModel.value.isEmpty()) {
                prefs.setHeartbeatProvider(prefs.selectedProvider.value)
                prefs.setHeartbeatModel(prefs.selectedModel.value)
            }
        }
    }

    fun setHeartbeatProvider(provider: LlmProvider) {
        prefs.setHeartbeatProvider(provider)
        val defaultModel = AnthropicModels.defaultForProvider(provider)
        prefs.setHeartbeatModel(defaultModel.modelId)
    }

    fun setHeartbeatModel(modelId: String) {
        prefs.setHeartbeatModel(modelId)
    }

    /** Available models filtered by the heartbeat's selected provider. */
    val availableHeartbeatModels: List<AnthropicModels>
        get() {
            val provider = prefs.heartbeatProvider.value
            val effective = if (isPrivileged && provider == LlmProvider.LOCAL) LlmProvider.ETHOS_PREMIUM else provider
            return AnthropicModels.forProvider(effective)
        }

    /** Returns true when the given provider has valid credentials / auth configured. */
    fun isProviderConfigured(provider: LlmProvider): Boolean = when (provider) {
        LlmProvider.ETHOS_PREMIUM -> isPrivileged
        LlmProvider.OPEN_ROUTER -> prefs.apiKey.value.isNotBlank()
        LlmProvider.CLAUDE_OAUTH -> prefs.claudeOauthRefreshToken.value.isNotBlank()
        LlmProvider.TINFOIL -> prefs.tinfoilApiKey.value.isNotBlank()
        LlmProvider.OPENAI -> prefs.openaiApiKey.value.isNotBlank()
        LlmProvider.VENICE -> prefs.veniceApiKey.value.isNotBlank()
        LlmProvider.LOCAL -> if (isPrivileged) true else app.modelDownloadManager.isModelDownloaded
    }

    val isLedAvailable: Boolean get() = app.ledController.isAvailable

    fun toggleSkill(skillId: String, enabled: Boolean) {
        prefs.setSkillEnabled(skillId, enabled)
    }

    fun setSmartRoutingEnabled(enabled: Boolean) {
        prefs.setSmartRoutingEnabled(enabled)
    }

    fun selectRoutingPreset(presetId: String) {
        prefs.setSelectedRoutingPresetId(presetId)
    }

    fun saveRoutingPreset(preset: RoutingPreset) {
        val current = prefs.routingPresets.value.toMutableList()
        val index = current.indexOfFirst { it.id == preset.id }
        if (index >= 0) {
            current[index] = preset
        } else {
            current.add(preset)
        }
        prefs.setRoutingPresets(current)
    }

    fun deleteRoutingPreset(presetId: String) {
        val current = prefs.routingPresets.value.toMutableList()
        current.removeAll { it.id == presetId && !it.isStock }
        prefs.setRoutingPresets(current)
        // If the deleted preset was selected, fall back to default
        if (prefs.selectedRoutingPresetId.value == presetId) {
            prefs.setSelectedRoutingPresetId(RoutingPreset.defaultPresetId)
        }
    }

    fun revertStockPreset(presetId: String) {
        val defaults = RoutingPreset.defaults()
        val defaultPreset = defaults.find { it.id == presetId } ?: return
        val current = prefs.routingPresets.value.toMutableList()
        val index = current.indexOfFirst { it.id == presetId }
        if (index >= 0) {
            current[index] = defaultPreset
        }
        prefs.setRoutingPresets(current)
    }

    fun setRoutingUseSameModel(enabled: Boolean) {
        prefs.setRoutingUseSameModel(enabled)
        if (!enabled) {
            // Initialize routing provider/model from current global settings
            if (prefs.routingModel.value.isEmpty()) {
                prefs.setRoutingProvider(prefs.selectedProvider.value)
                // Use the default routing model for that provider
                val routingModel = AnthropicModels.routingModelForProvider(prefs.selectedProvider.value)
                if (routingModel != null) {
                    prefs.setRoutingModel(routingModel.modelId)
                }
            }
        }
    }

    fun setRoutingProvider(provider: LlmProvider) {
        prefs.setRoutingProvider(provider)
        val defaultModel = AnthropicModels.routingModelForProvider(provider)
            ?: AnthropicModels.defaultForProvider(provider)
        prefs.setRoutingModel(defaultModel.modelId)
    }

    fun setRoutingModel(modelId: String) {
        prefs.setRoutingModel(modelId)
    }

    /** Available models filtered by the routing's selected provider. */
    val availableRoutingModels: List<AnthropicModels>
        get() {
            val provider = prefs.routingProvider.value
            val effective = if (isPrivileged && provider == LlmProvider.LOCAL) LlmProvider.ETHOS_PREMIUM else provider
            return AnthropicModels.forProvider(effective)
        }

    fun setLedMaxBrightness(value: Int) {
        prefs.setLedMaxBrightness(value)
    }

    fun completeTelegramSetup(token: String, ownerChatId: Long) {
        prefs.setTelegramBotToken(token)
        prefs.setTelegramOwnerChatId(ownerChatId)
        prefs.setTelegramBotEnabled(true)
        // Immediately tell the OS to start polling via direct binder transact.
        // HeartbeatBindingService's observeTelegramPrefs() will also pick this up,
        // but we send it eagerly in case the service hasn't started observing yet.
        notifyOsTelegramRegister(token)
    }

    fun clearTelegramSetup() {
        prefs.clearTelegramSetup()
        notifyOsTelegramUnregister()
    }

    fun setGoogleOauthClientId(value: String) {
        prefs.setGoogleOauthClientId(value)
    }

    fun setGoogleOauthClientSecret(value: String) {
        prefs.setGoogleOauthClientSecret(value)
    }

    fun startGoogleOAuthFlow(context: android.content.Context) {
        viewModelScope.launch {
            app.googleAuthManager.startOAuthFlow(context)
        }
    }

    fun disconnectGoogle() {
        prefs.clearGoogleOauthSetup()
    }

    private fun notifyOsTelegramRegister(token: String) {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "andyclawheartbeat") as? IBinder ?: return
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken("com.android.server.IAndyClawHeartbeat")
                data.writeString(token)
                binder.transact(IBinder.FIRST_CALL_TRANSACTION + 0, data, null, IBinder.FLAG_ONEWAY)
            } finally {
                data.recycle()
            }
        } catch (e: Exception) {
            Log.w("SettingsViewModel", "Failed to notify OS of Telegram register", e)
        }
    }

    private fun notifyOsTelegramUnregister() {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "andyclawheartbeat") as? IBinder ?: return
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken("com.android.server.IAndyClawHeartbeat")
                binder.transact(IBinder.FIRST_CALL_TRANSACTION + 1, data, null, IBinder.FLAG_ONEWAY)
            } finally {
                data.recycle()
            }
        } catch (e: Exception) {
            Log.w("SettingsViewModel", "Failed to notify OS of Telegram unregister", e)
        }
    }

    fun setAutoStoreEnabled(enabled: Boolean) {
        _autoStoreEnabled.value = enabled
        // Persist preference
        prefs.putString("memory.autoStore", if (enabled) "true" else "false")
    }

    fun reindexMemory() {
        viewModelScope.launch {
            _isReindexing.value = true
            try {
                app.memoryManager.reindex(force = true)
            } catch (_: Exception) {
                // Best-effort
            } finally {
                _isReindexing.value = false
            }
        }
    }

    fun clearAllMemories() {
        viewModelScope.launch {
            try {
                app.memoryManager.deleteAll()
                // Count updates automatically via observeCount() Flow
            } catch (_: Exception) {
                // Best-effort
            }
        }
    }

    fun rescanExtensions() {
        viewModelScope.launch {
            _isExtensionScanning.value = true
            try {
                app.extensionEngine.discoverAndRegister()
                val adapters = app.extensionEngine.toSkillAdapters()
                for (adapter in adapters) {
                    app.nativeSkillRegistry.register(adapter)
                }
                _extensions.value = app.extensionEngine.registry.getAll()
            } catch (_: Exception) {
                // Best-effort
            } finally {
                _isExtensionScanning.value = false
            }
        }
    }

    // ── Skill inspection ────────────────────────────────────────────────

    fun inspectSkill(skill: AndyClawSkill) {
        viewModelScope.launch {
            val content = when {
                skill.id.startsWith("clawhub:") -> {
                    val slug = skill.id.removePrefix("clawhub:")
                    withContext(Dispatchers.IO) {
                        app.clawHubManager.readSkillContent(slug)
                    }
                }
                skill.id.startsWith("ai:") -> {
                    withContext(Dispatchers.IO) {
                        val skillDir = java.io.File(app.aiSkillsDir, skill.id.removePrefix("ai:"))
                        val skillMd = java.io.File(skillDir, "SKILL.md")
                        if (skillMd.isFile) skillMd.readText() else null
                    }
                }
                else -> null
            }

            _inspectedSkill.value = InspectedSkillInfo(
                name = skill.name,
                subtitle = skill.id,
                content = content ?: generateManifestContent(skill),
            )
        }
    }

    fun dismissSkillInspection() {
        _inspectedSkill.value = null
    }

    private fun generateManifestContent(skill: AndyClawSkill): String = buildString {
        appendLine(skill.baseManifest.description)
        appendLine()
        appendLine("Tools:")
        for (tool in skill.baseManifest.tools) {
            appendLine("  • ${tool.name} — ${tool.description}")
        }
        val privTools = skill.privilegedManifest?.tools
        if (!privTools.isNullOrEmpty()) {
            appendLine()
            appendLine("Privileged tools:")
            for (tool in privTools) {
                appendLine("  • ${tool.name} — ${tool.description}")
            }
        }
    }

    // ── Paymaster internals ────────────────────────────────────────────

    private fun initPaymaster() {
        viewModelScope.launch {
            if (paymasterSDK.initialize()) {
                // Show cached value immediately
                _paymasterBalance.value = paymasterSDK.getCurrentBalance() ?: "0.0"
                // Ask the OS to fetch the latest from backend
                paymasterSDK.queryUpdate()
                // Re-read after 500ms and 1s to pick up the updated value
                delay(500)
                paymasterSDK.getCurrentBalance()?.let { _paymasterBalance.value = it }
                delay(500)
                paymasterSDK.getCurrentBalance()?.let { _paymasterBalance.value = it }
                startBalancePolling()
            }
        }
    }

    private fun startBalancePolling() {
        balancePollingJob?.cancel()
        balancePollingJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                paymasterSDK.getCurrentBalance()?.let { _paymasterBalance.value = it }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        balancePollingJob?.cancel()
        paymasterSDK.cleanup()
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun loadAutoStorePreference() {
        _autoStoreEnabled.value = prefs.getString("memory.autoStore") != "false"
    }

    private fun refreshExtensions() {
        _extensions.value = app.extensionEngine.registry.getAll()
    }
}

data class InspectedSkillInfo(
    val name: String,
    val subtitle: String,
    val content: String,
)
