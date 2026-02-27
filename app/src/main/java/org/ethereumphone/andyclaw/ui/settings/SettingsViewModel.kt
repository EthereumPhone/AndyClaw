package org.ethereumphone.andyclaw.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.extensions.ExtensionDescriptor
import org.ethereumphone.andyclaw.extensions.toSkillAdapters
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.LlmProvider
import org.ethereumphone.andyclaw.llm.ModelDownloadManager
import org.ethereumphone.andyclaw.PaymasterSDK
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NodeApp
    private val prefs = app.securePrefs

    val selectedModel = prefs.selectedModel
    val yoloMode = prefs.yoloMode
    val enabledSkills = prefs.enabledSkills
    val notificationReplyEnabled = prefs.notificationReplyEnabled
    val heartbeatOnNotificationEnabled = prefs.heartbeatOnNotificationEnabled
    val heartbeatOnXmtpMessageEnabled = prefs.heartbeatOnXmtpMessageEnabled
    val heartbeatIntervalMinutes = prefs.heartbeatIntervalMinutes

    val selectedProvider = prefs.selectedProvider
    val tinfoilApiKey = prefs.tinfoilApiKey
    val apiKey = prefs.apiKey

    val telegramBotToken = prefs.telegramBotToken
    val telegramBotEnabled = prefs.telegramBotEnabled

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
    }

    fun setNotificationReplyEnabled(enabled: Boolean) {
        prefs.setNotificationReplyEnabled(enabled)
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

    fun toggleSkill(skillId: String, enabled: Boolean) {
        prefs.setSkillEnabled(skillId, enabled)
    }

    fun setTelegramBotToken(token: String) {
        prefs.setTelegramBotToken(token)
    }

    fun setTelegramBotEnabled(enabled: Boolean) {
        prefs.setTelegramBotEnabled(enabled)
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
