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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
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
import org.ethereumphone.andyclaw.agent.BudgetPreset
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.RoutingPreset
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities

/**
 * Unified model representation for the model selection UI.
 * Combines both static [AnthropicModels] entries and dynamic OpenRouter models.
 */
/**
 * Unified model representation for the model selection UI.
 * Combines both static [AnthropicModels] entries and dynamic OpenRouter models.
 */
data class DisplayModel(
    val modelId: String,
    val displayName: String,
    /** Provider name shown as subtitle (e.g., "Anthropic", "Google", "OpenAI"). */
    val subtitle: String,
    /** Pricing details shown on long-press (e.g., "$3.00/M in · $15.00/M out"). */
    val pricingDetail: String? = null,
    /** Sort priority: lower = shown first. Known/popular models get low values. */
    val sortPriority: Int = Int.MAX_VALUE,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NodeApp
    private val prefs = app.securePrefs

    val selectedModel = prefs.selectedModel
    val yoloMode = prefs.yoloMode
    val safetyEnabled = prefs.safetyEnabled
    val enabledSkills = prefs.enabledSkills
    val budgetModeEnabled = prefs.budgetModeEnabled
    val selectedBudgetPresetId = prefs.selectedBudgetPresetId
    val budgetPresets = prefs.budgetPresets
    val smartRoutingEnabled = prefs.smartRoutingEnabled
    val selectedRoutingPresetId = prefs.selectedRoutingPresetId
    val routingPresets = prefs.routingPresets
    val routingMode = prefs.routingMode
    val routingUseSameModel = prefs.routingUseSameModel
    val routingProvider = prefs.routingProvider
    val routingModel = prefs.routingModel
    val modelRoutingEnabled = prefs.modelRoutingEnabled
    val modelRoutingLight = prefs.modelRoutingLight
    val modelRoutingStandard = prefs.modelRoutingStandard
    val modelRoutingPowerful = prefs.modelRoutingPowerful
    val notificationReplyEnabled = prefs.notificationReplyEnabled
    val executiveSummaryEnabled = prefs.executiveSummaryEnabled
    val heartbeatOnNotificationEnabled = prefs.heartbeatOnNotificationEnabled
    val heartbeatOnXmtpMessageEnabled = prefs.heartbeatOnXmtpMessageEnabled
    val heartbeatIntervalMinutes = prefs.heartbeatIntervalMinutes
    val heartbeatUseSameModel = prefs.heartbeatUseSameModel
    val heartbeatProvider = prefs.heartbeatProvider
    val heartbeatModel = prefs.heartbeatModel

    val selectedProvider = prefs.selectedProvider
    val syncProviderToAll = prefs.syncProviderToAll
    val tinfoilApiKey = prefs.tinfoilApiKey
    val apiKey = prefs.apiKey
    val openaiApiKey = prefs.openaiApiKey
    val veniceApiKey = prefs.veniceApiKey

    // ── Local LLM (on-device, llama.cpp via Llamatik) ──────────────────
    val ggufModels = app.ggufRegistry.models
    val selectedGgufFilename  = prefs.selectedGgufFilename
    val localLlmTemperature   = prefs.localLlmTemperature
    val localLlmTopP          = prefs.localLlmTopP
    val localLlmTopK          = prefs.localLlmTopK
    val localLlmMaxTokens     = prefs.localLlmMaxTokens
    val localLlmRepeatPenalty = prefs.localLlmRepeatPenalty
    val localLlmNCtx          = prefs.localLlmNCtx
    val localLlmNBatch        = prefs.localLlmNBatch
    val localLlmNThreads      = prefs.localLlmNThreads
    val localLlmNGpuLayers    = prefs.localLlmNGpuLayers
    val localLlmUseMmap       = prefs.localLlmUseMmap
    val claudeOauthRefreshToken = prefs.claudeOauthRefreshToken
    val chatgptOauthRefreshToken = prefs.chatgptOauthRefreshToken
    val customBaseUrl  = prefs.customBaseUrl
    val customApiKey   = prefs.customApiKey
    val customModelId  = prefs.customModelId

    // CUSTOM-provider /v1/models discovery — populated by fetchCustomModels()
    private val _customAvailableModels = MutableStateFlow<List<String>>(emptyList())
    val customAvailableModels: StateFlow<List<String>> = _customAvailableModels.asStateFlow()
    private val _customModelsFetching = MutableStateFlow(false)
    val customModelsFetching: StateFlow<Boolean> = _customModelsFetching.asStateFlow()
    private val _customModelsFetchError = MutableStateFlow<String?>(null)
    val customModelsFetchError: StateFlow<String?> = _customModelsFetchError.asStateFlow()

    val telegramBotEnabled = prefs.telegramBotEnabled
    val telegramOwnerChatId = prefs.telegramOwnerChatId
    val ledMaxBrightness = prefs.ledMaxBrightness

    val googleOauthRefreshToken = prefs.googleOauthRefreshToken
    val googleOauthClientId = prefs.googleOauthClientId
    val googleOauthClientSecret = prefs.googleOauthClientSecret

    val currentTier: String get() = OsCapabilities.currentTier().name
    val isPrivileged: Boolean get() = OsCapabilities.hasPrivilegedAccess

    /** Available models filtered by the currently selected provider (enum-only, for non-OpenRouter). */
    val availableModels: List<AnthropicModels>
        get() {
            val provider = prefs.selectedProvider.value
            val effective = if (isPrivileged && provider == LlmProvider.LOCAL) LlmProvider.ETHOS_PREMIUM else provider
            return AnthropicModels.forProvider(effective)
        }

    /** Search/filter text for the model selection screen. */
    private val _modelSearchQuery = MutableStateFlow("")
    val modelSearchQuery: StateFlow<String> = _modelSearchQuery.asStateFlow()

    fun setModelSearchQuery(query: String) {
        _modelSearchQuery.value = query
    }

    /**
     * Enriched model list for the model selection UI.
     * For OpenRouter / ethOS Premium: merges static enum entries with dynamic
     * OpenRouter registry models, deduplicates, and ranks known models first.
     * For other providers: wraps the static enum entries.
     * Applies [modelSearchQuery] filter when non-empty.
     */
    fun getDisplayModels(): List<DisplayModel> {
        val provider = prefs.selectedProvider.value
        val effective = if (isPrivileged && provider == LlmProvider.LOCAL) LlmProvider.ETHOS_PREMIUM else provider
        return getDisplayModelsForProvider(effective, _modelSearchQuery.value)
    }

    /**
     * Shared logic for building a filtered/sorted [DisplayModel] list for any provider.
     * Used by main model selection, heartbeat, routing, and model routing tier screens.
     */
    private fun getDisplayModelsForProvider(
        provider: LlmProvider,
        searchQuery: String,
        includeEnumFallbacks: Boolean = false,
    ): List<DisplayModel> {
        val query = searchQuery.trim().lowercase()

        val models = when {
            provider == LlmProvider.OPEN_ROUTER || provider == LlmProvider.ETHOS_PREMIUM ->
                buildOpenRouterDisplayModels(provider, includeEnumFallbacks)
            provider == LlmProvider.CUSTOM ->
                buildCustomDisplayModels()
            else -> AnthropicModels.forProvider(provider).map { model ->
                DisplayModel(
                    modelId = model.modelId,
                    displayName = model.name,
                    subtitle = buildEnumModelSubtitle(model),
                    sortPriority = if (model == AnthropicModels.defaultForProvider(provider)) 0 else 100,
                )
            }
        }

        val filtered = if (query.isBlank()) {
            models.sortedBy { it.sortPriority }
        } else {
            models.filter {
                it.displayName.lowercase().contains(query) ||
                    it.modelId.lowercase().contains(query) ||
                    it.subtitle.lowercase().contains(query)
            }.sortedBy { it.sortPriority }
        }

        // For CUSTOM provider: if the user typed something that doesn't match
        // any fetched model id, surface a synthetic "Use \"<query>\"" row so
        // they can pick a model the server didn't advertise (some self-hosted
        // setups don't expose /v1/models, or expose it incompletely). The row
        // produces modelId = the raw typed string.
        if (provider == LlmProvider.CUSTOM && query.isNotBlank() &&
            filtered.none { it.modelId.equals(_modelSearchQuery.value.trim(), ignoreCase = true) }
        ) {
            val raw = _modelSearchQuery.value.trim()
            if (raw.isNotEmpty()) {
                return filtered + DisplayModel(
                    modelId = raw,
                    displayName = "Use \"$raw\"",
                    subtitle = "Send this as the model id (not in server list)",
                    sortPriority = 999,
                )
            }
        }
        return filtered
    }

    /** /v1/models discovery results for the CUSTOM provider, in DisplayModel shape. */
    private fun buildCustomDisplayModels(): List<DisplayModel> {
        val fetched = _customAvailableModels.value
        val currentlySelected = prefs.selectedModel.value
        return fetched.map { id ->
            DisplayModel(
                modelId = id,
                displayName = id,
                subtitle = "self-hosted",
                sortPriority = if (id == currentlySelected) 0 else 100,
            )
        }
    }

    private fun buildOpenRouterDisplayModels(
        provider: LlmProvider,
        includeEnumFallbacks: Boolean = false,
    ): List<DisplayModel> {
        val registry = app.openRouterModelRegistry
        val registryModels = registry.getAllModels()

        // If registry is empty, fall back to enum-only
        if (registryModels.isEmpty()) {
            return AnthropicModels.forProvider(provider).map { model ->
                DisplayModel(model.modelId, model.name, buildEnumModelSubtitle(model), sortPriority = 100)
            }
        }

        // Known popular model IDs in display order (rank = position)
        val popularModelIds = listOf(
            "anthropic/claude-sonnet-4.6",
            "anthropic/claude-opus-4.6",
            "google/gemini-3.1-pro-preview",
            "google/gemini-2.5-pro",
            "google/gemini-2.5-flash",
            "openai/gpt-4.1",
            "openai/gpt-4.1-mini",
            "x-ai/grok-4",
            "moonshotai/kimi-k2.5",
            "minimax/minimax-m2.5",
            "qwen/qwen3.5-plus-02-15",
            "qwen/qwen3.5-flash-02-23",
            "deepseek/deepseek-r1",
            "meta-llama/llama-4-maverick",
        )

        val seen = mutableSetOf<String>()
        val result = mutableListOf<DisplayModel>()

        // 1. Popular models first (in defined order)
        for ((index, id) in popularModelIds.withIndex()) {
            val regModel = registryModels.find { it.id == id }
            if (regModel != null) {
                seen.add(id)
                result.add(DisplayModel(
                    modelId = regModel.id,
                    displayName = stripProviderPrefix(regModel.name),
                    subtitle = buildModelSubtitle(regModel),
                    pricingDetail = buildPricingDetail(regModel),
                    sortPriority = index,
                ))
            }
        }

        // 2. Remaining models sorted alphabetically by name
        val remaining = registryModels
            .filter { it.id !in seen }
            .sortedBy { it.name.lowercase() }

        for (model in remaining) {
            seen.add(model.id)
            result.add(DisplayModel(
                modelId = model.id,
                displayName = stripProviderPrefix(model.name),
                subtitle = buildModelSubtitle(model),
                pricingDetail = buildPricingDetail(model),
                sortPriority = popularModelIds.size + result.size,
            ))
        }

        // 3. Static enum models not in registry. Always included for ethOS
        //    Premium (Tinfoil models). For other providers only included when
        //    explicitly requested (e.g. routing model selector needs lightweight
        //    models that lack tool support and get filtered by the registry).
        if (includeEnumFallbacks || provider == LlmProvider.ETHOS_PREMIUM) {
            for (model in AnthropicModels.forProvider(provider)) {
                if (model.modelId !in seen) {
                    seen.add(model.modelId)
                    result.add(DisplayModel(
                        modelId = model.modelId,
                        displayName = model.name,
                        subtitle = buildEnumModelSubtitle(model),
                        sortPriority = popularModelIds.size + result.size,
                    ))
                }
            }
        }

        return result
    }

    /** Extract the provider name from an OpenRouter model ID (e.g., "anthropic/claude-..." → "Anthropic"). */
    private fun extractProvider(modelId: String): String {
        val prefix = modelId.substringBefore("/", modelId)
        return prefix.replaceFirstChar { it.uppercase() }
    }

    /**
     * Strip provider prefix from OpenRouter display names.
     * The API often returns names like "Google: Gemini 2.5 Pro" or "Anthropic: Claude Sonnet 4.6".
     * Since we show the provider as subtitle, the prefix is redundant.
     */
    private fun stripProviderPrefix(name: String): String {
        val colonIndex = name.indexOf(':')
        if (colonIndex in 1..30) {
            return name.substring(colonIndex + 1).trim()
        }
        return name
    }

    private fun buildPricingDetail(model: org.ethereumphone.andyclaw.llm.OpenRouterModelRegistry.ParsedModel): String {
        val promptPrice = model.promptPricePerToken * 1_000_000
        val completionPrice = model.completionPricePerToken * 1_000_000
        val ctx = formatContextLength(model.contextLength)
        val maxOut = when {
            model.maxCompletionTokens >= 1_000_000 -> "${model.maxCompletionTokens / 1_000_000}M"
            model.maxCompletionTokens >= 1_000 -> "${model.maxCompletionTokens / 1_000}K"
            else -> "${model.maxCompletionTokens}"
        }
        val prompt = if (promptPrice < 0.01) "<\$0.01/M in" else "\$${String.format("%.2f", promptPrice)}/M in"
        val completion = "\$${String.format("%.2f", completionPrice)}/M out"
        return "$prompt · $completion · $ctx · ${maxOut} max out"
    }

    /** Build a compact subtitle for OpenRouter registry models: "Anthropic · 1M ctx · $3/$15 per M" */
    private fun buildModelSubtitle(model: org.ethereumphone.andyclaw.llm.OpenRouterModelRegistry.ParsedModel): String {
        val provider = extractProvider(model.id)
        val ctx = formatContextLength(model.contextLength)
        val inPrice = model.promptPricePerToken * 1_000_000
        val outPrice = model.completionPricePerToken * 1_000_000
        val inStr = if (inPrice < 0.01) "<\$0.01" else "\$${String.format("%.2f", inPrice)}"
        val outStr = "\$${String.format("%.2f", outPrice)}"
        return "$provider · $ctx · $inStr/$outStr per M"
    }

    /** Build a subtitle for static enum models: "Anthropic · 1M ctx" */
    private fun buildEnumModelSubtitle(model: AnthropicModels): String {
        val provider = extractProvider(model.modelId)
        return if (model.contextWindow > 0) {
            "$provider · ${formatContextLength(model.contextWindow)}"
        } else {
            provider
        }
    }

    private fun formatContextLength(tokens: Int): String = when {
        tokens >= 1_000_000 -> "${tokens / 1_000_000}M ctx"
        tokens >= 1_000 -> "${tokens / 1_000}K ctx"
        else -> "${tokens} ctx"
    }

    /** Trigger an OpenRouter model list refresh (if cache is stale). Called when entering model selection. */
    suspend fun refreshOpenRouterModelsIfNeeded() {
        try {
            app.openRouterModelRegistry.refreshIfNeeded()
        } catch (_: Exception) {
            // Best-effort — model list will use disk cache or enum fallback
        }
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

    private val _smartExtractionEnabled = MutableStateFlow(false)
    val smartExtractionEnabled: StateFlow<Boolean> = _smartExtractionEnabled.asStateFlow()

    private val _aiRerankingEnabled = MutableStateFlow(false)
    val aiRerankingEnabled: StateFlow<Boolean> = _aiRerankingEnabled.asStateFlow()

    private val _isReindexing = MutableStateFlow(false)
    val isReindexing: StateFlow<Boolean> = _isReindexing.asStateFlow()

    // ── Extensions ──────────────────────────────────────────────────────

    private val _extensions = MutableStateFlow<List<ExtensionDescriptor>>(emptyList())
    val extensions: StateFlow<List<ExtensionDescriptor>> = _extensions.asStateFlow()

    private val _isExtensionScanning = MutableStateFlow(false)
    val isExtensionScanning: StateFlow<Boolean> = _isExtensionScanning.asStateFlow()

    // ── Backup / Restore ──────────────────────────────────────────────

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _pendingImportInfo = MutableStateFlow<org.ethereumphone.andyclaw.backup.BackupInfo?>(null)
    val pendingImportInfo: StateFlow<org.ethereumphone.andyclaw.backup.BackupInfo?> = _pendingImportInfo.asStateFlow()

    /** Show password dialog for export. */
    private val _showExportPasswordDialog = MutableStateFlow(false)
    val showExportPasswordDialog: StateFlow<Boolean> = _showExportPasswordDialog.asStateFlow()

    /** Show password dialog for import (encrypted backup). */
    private val _showImportPasswordDialog = MutableStateFlow(false)
    val showImportPasswordDialog: StateFlow<Boolean> = _showImportPasswordDialog.asStateFlow()

    /** Error message from a failed import (e.g. wrong password). */
    private val _backupError = MutableStateFlow<String?>(null)
    val backupError: StateFlow<String?> = _backupError.asStateFlow()

    private var pendingImportUri: android.net.Uri? = null
    private var pendingImportPassword: String? = null

    private val backupManager by lazy { org.ethereumphone.andyclaw.backup.BackupManager(app) }

    /** Step 1 of export: show password dialog. */
    fun requestExport() {
        _showExportPasswordDialog.value = true
    }

    /** Step 2 of export: user entered a password (may be empty). */
    fun createBackup(context: android.content.Context, password: String) {
        _showExportPasswordDialog.value = false
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val timestamp = System.currentTimeMillis()
                val aiName = prefs.aiName.value.replace(" ", "_")
                val fileName = "${aiName}_backup_$timestamp${org.ethereumphone.andyclaw.backup.BackupManager.FILE_EXTENSION}"

                // Save to Downloads so the user always has a copy
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                )
                downloadsDir.mkdirs()
                val file = java.io.File(downloadsDir, fileName)
                file.outputStream().use {
                    backupManager.createBackup(it, password.takeIf { p -> p.isNotEmpty() })
                }

                android.widget.Toast.makeText(
                    context,
                    "Backup saved to Downloads/$fileName",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Backup export failed", e)
            } finally {
                _isExporting.value = false
            }
        }
    }

    fun dismissExportPasswordDialog() {
        _showExportPasswordDialog.value = false
    }

    /** Step 1 of import: user picked a file. Check if encrypted. */
    fun onImportFilePicked(uri: android.net.Uri, context: android.content.Context) {
        pendingImportUri = uri
        viewModelScope.launch {
            try {
                val encrypted = context.contentResolver.openInputStream(uri)?.use { stream ->
                    backupManager.isEncrypted(stream)
                } ?: false

                if (encrypted) {
                    // Need password first
                    _showImportPasswordDialog.value = true
                } else {
                    // Read manifest directly (no password)
                    proceedWithImportManifest(context, null)
                }
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Failed to check backup file", e)
            }
        }
    }

    /** Step 2 of import (encrypted only): user entered the password. */
    fun onImportPasswordEntered(context: android.content.Context, password: String) {
        _showImportPasswordDialog.value = false
        _backupError.value = null
        pendingImportPassword = password
        viewModelScope.launch {
            proceedWithImportManifest(context, password)
        }
    }

    fun dismissImportPasswordDialog() {
        _showImportPasswordDialog.value = false
        pendingImportUri = null
    }

    private suspend fun proceedWithImportManifest(context: android.content.Context, password: String?) {
        val uri = pendingImportUri ?: return
        try {
            val info = context.contentResolver.openInputStream(uri)?.use { stream ->
                backupManager.readManifest(stream, password)
            }
            if (info != null) {
                pendingImportPassword = password
                _pendingImportInfo.value = info
            } else {
                _backupError.value = "Could not read backup file"
            }
        } catch (e: javax.crypto.AEADBadTagException) {
            _backupError.value = "Wrong password"
            // Re-show password dialog
            _showImportPasswordDialog.value = true
        } catch (e: Exception) {
            Log.e("SettingsViewModel", "Failed to read backup manifest", e)
            _backupError.value = "Failed to read backup: ${e.message}"
        }
    }

    /** Step 3 of import: user confirmed restore. */
    fun confirmImport(context: android.content.Context) {
        val uri = pendingImportUri ?: return
        val password = pendingImportPassword
        _pendingImportInfo.value = null
        pendingImportUri = null
        pendingImportPassword = null

        viewModelScope.launch {
            _isImporting.value = true
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    backupManager.restoreBackup(stream, password)
                }
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Backup import failed", e)
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun dismissImportDialog() {
        _pendingImportInfo.value = null
        pendingImportUri = null
        pendingImportPassword = null
    }

    fun dismissBackupError() {
        _backupError.value = null
    }

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
        // Sync to heartbeat and compaction when the toggle is on
        if (prefs.syncProviderToAll.value) {
            syncHeartbeatToProvider(provider)
            syncCompactionToProvider(provider)
        }
    }

    fun setSyncProviderToAll(enabled: Boolean) {
        prefs.setSyncProviderToAll(enabled)
        // When enabling, immediately sync heartbeat and compaction to the current provider
        if (enabled) {
            val provider = prefs.selectedProvider.value
            syncHeartbeatToProvider(provider)
            syncCompactionToProvider(provider)
        }
    }

    private fun syncHeartbeatToProvider(provider: LlmProvider) {
        prefs.setHeartbeatProvider(provider)
        // Use the user's previous selection for this provider if available, otherwise default
        val userModel = prefs.getHeartbeatUserModelForProvider(provider)
        val model = userModel ?: AnthropicModels.defaultForProvider(provider).modelId
        prefs.setHeartbeatModel(model)
        // Also ensure heartbeat is set to use its own provider (not "same as main")
        prefs.setHeartbeatUseSameModel(false)
    }

    private fun syncCompactionToProvider(provider: LlmProvider) {
        prefs.setCompactionProvider(provider)
        val model = AnthropicModels.defaultForProvider(provider).modelId
        prefs.setCompactionModel(model)
        prefs.setCompactionUseSameModel(false)
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

    // ── Local LLM setters / actions ────────────────────────────────────
    fun selectGguf(filename: String)            = prefs.setSelectedGgufFilename(filename)
    fun setLocalLlmTemperature(v: Float)        = prefs.setLocalLlmTemperature(v)
    fun setLocalLlmTopP(v: Float)               = prefs.setLocalLlmTopP(v)
    fun setLocalLlmTopK(v: Int)                 = prefs.setLocalLlmTopK(v)
    fun setLocalLlmMaxTokens(v: Int)            = prefs.setLocalLlmMaxTokens(v)
    fun setLocalLlmRepeatPenalty(v: Float)      = prefs.setLocalLlmRepeatPenalty(v)
    fun setLocalLlmNCtx(v: Int)                 = prefs.setLocalLlmNCtx(v)
    fun setLocalLlmNBatch(v: Int)               = prefs.setLocalLlmNBatch(v)
    fun setLocalLlmNThreads(v: Int)             = prefs.setLocalLlmNThreads(v)
    fun setLocalLlmNGpuLayers(v: Int)           = prefs.setLocalLlmNGpuLayers(v)
    fun setLocalLlmUseMmap(v: Boolean)          = prefs.setLocalLlmUseMmap(v)

    /** Copy a user-picked .gguf URI into filesDir/models/ and auto-select it. */
    fun importGgufFromUri(uri: android.net.Uri) {
        viewModelScope.launch {
            val model = app.ggufRegistry.importFromUri(uri)
            if (model != null) prefs.setSelectedGgufFilename(model.filename)
        }
    }

    /** Delete an imported GGUF (refuses to delete the builtin). */
    fun deleteImportedGguf(filename: String) {
        if (app.ggufRegistry.delete(filename)) {
            if (prefs.selectedGgufFilename.value == filename) {
                prefs.setSelectedGgufFilename(org.ethereumphone.andyclaw.llm.DEFAULT_BUILTIN_GGUF)
            }
        }
    }

    /** Restore every local-LLM knob to LocalLlmRuntimeConfig.DEFAULT. */
    fun resetLocalLlmConfig() {
        val d = org.ethereumphone.andyclaw.llm.LocalLlmRuntimeConfig.DEFAULT
        prefs.setLocalLlmTemperature(d.temperature)
        prefs.setLocalLlmTopP(d.topP)
        prefs.setLocalLlmTopK(d.topK)
        prefs.setLocalLlmMaxTokens(d.maxTokens)
        prefs.setLocalLlmRepeatPenalty(d.repeatPenalty)
        prefs.setLocalLlmNCtx(d.nCtx)
        prefs.setLocalLlmNBatch(d.nBatch)
        prefs.setLocalLlmNThreads(d.nThreads)
        prefs.setLocalLlmNGpuLayers(d.nGpuLayers)
        prefs.setLocalLlmUseMmap(d.useMmap)
    }

    fun setChatGptOauthRefreshToken(token: String) {
        prefs.setChatGptOauthRefreshToken(token)
    }

    fun setCustomBaseUrl(value: String)  = prefs.setCustomBaseUrl(value)
    fun setCustomApiKey(value: String)   = prefs.setCustomApiKey(value)
    fun setCustomModelId(value: String)  = prefs.setCustomModelId(value)

    /**
     * GET {derived}/v1/models against the user's CUSTOM endpoint. Every major
     * OpenAI-compatible self-hosted server (Ollama, LM Studio, vLLM, LocalAI,
     * llama.cpp server) exposes this list. Failure modes are recorded in
     * [customModelsFetchError] — UI shows them but keeps the free-text Model
     * ID field as a fallback.
     */
    fun fetchCustomModels() {
        val baseUrl = prefs.customBaseUrl.value.trim()
        if (baseUrl.isBlank()) {
            _customAvailableModels.value = emptyList()
            _customModelsFetchError.value = null
            return
        }
        val modelsUrl = modelsUrlFromChatUrl(baseUrl)
        viewModelScope.launch {
            _customModelsFetching.value = true
            _customModelsFetchError.value = null
            try {
                val ids = withContext(Dispatchers.IO) {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()
                    val req = Request.Builder().url(modelsUrl).apply {
                        val key = prefs.customApiKey.value
                        if (key.isNotBlank()) addHeader("Authorization", "Bearer $key")
                    }.build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw RuntimeException("HTTP ${resp.code}")
                        }
                        val body = resp.body?.string().orEmpty()
                        val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                            ?: throw RuntimeException("response missing 'data' array")
                        data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                    }
                }
                _customAvailableModels.value = ids
            } catch (e: Exception) {
                _customAvailableModels.value = emptyList()
                _customModelsFetchError.value = e.message ?: "fetch failed"
            } finally {
                _customModelsFetching.value = false
            }
        }
    }

    /** Derive the /v1/models URL from the user's chat-completions URL. */
    private fun modelsUrlFromChatUrl(chatUrl: String): String {
        val trimmed = chatUrl.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed.removeSuffix("/chat/completions") + "/models"
            trimmed.endsWith("/v1") -> "$trimmed/models"
            trimmed.contains("/v1/") -> trimmed.substringBefore("/v1/") + "/v1/models"
            else -> "$trimmed/v1/models"
        }
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
            val allIds = app.nativeSkillRegistry.getAll().map { it.id }.toSet()
            prefs.setAllSkillsEnabled(allIds)
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
        // Record this as the user's explicit choice for the current heartbeat provider
        prefs.setHeartbeatUserModelForProvider(prefs.heartbeatProvider.value, modelId)
    }

    /** Available models filtered by the heartbeat's selected provider. */
    val availableHeartbeatModels: List<AnthropicModels>
        get() {
            val provider = prefs.heartbeatProvider.value
            val effective = if (isPrivileged && provider == LlmProvider.LOCAL) LlmProvider.ETHOS_PREMIUM else provider
            return AnthropicModels.forProvider(effective)
        }

    /** Search/filter text for heartbeat model selection. */
    private val _heartbeatModelSearchQuery = MutableStateFlow("")
    val heartbeatModelSearchQuery: StateFlow<String> = _heartbeatModelSearchQuery.asStateFlow()
    fun setHeartbeatModelSearchQuery(query: String) { _heartbeatModelSearchQuery.value = query }

    fun getHeartbeatDisplayModels(): List<DisplayModel> {
        val provider = prefs.heartbeatProvider.value
        val effective = if (isPrivileged && provider == LlmProvider.LOCAL) LlmProvider.ETHOS_PREMIUM else provider
        return getDisplayModelsForProvider(effective, _heartbeatModelSearchQuery.value)
    }

    // ── Compaction settings ────────────────────────────────────────

    val compactionConfig = prefs.compactionConfig
    val compactionUseSameModel = prefs.compactionUseSameModel
    val compactionProvider = prefs.compactionProvider
    val compactionModel = prefs.compactionModel

    fun updateCompactionConfig(transform: (org.ethereumphone.andyclaw.agent.CompactionConfig) -> org.ethereumphone.andyclaw.agent.CompactionConfig) {
        prefs.setCompactionConfig(transform(prefs.compactionConfig.value))
    }

    fun setCompactionUseSameModel(enabled: Boolean) {
        prefs.setCompactionUseSameModel(enabled)
        if (!enabled && prefs.compactionModel.value.isEmpty()) {
            prefs.setCompactionProvider(prefs.selectedProvider.value)
            prefs.setCompactionModel(prefs.selectedModel.value)
        }
    }

    fun setCompactionProvider(provider: LlmProvider) {
        prefs.setCompactionProvider(provider)
        val defaultModel = AnthropicModels.defaultForProvider(provider)
        prefs.setCompactionModel(defaultModel.modelId)
    }

    fun setCompactionModel(modelId: String) {
        prefs.setCompactionModel(modelId)
    }

    private val _compactionModelSearchQuery = MutableStateFlow("")
    val compactionModelSearchQuery: StateFlow<String> = _compactionModelSearchQuery.asStateFlow()
    fun setCompactionModelSearchQuery(query: String) { _compactionModelSearchQuery.value = query }

    fun getCompactionDisplayModels(): List<DisplayModel> {
        val provider = prefs.compactionProvider.value
        val effective = if (isPrivileged && provider == LlmProvider.LOCAL) LlmProvider.ETHOS_PREMIUM else provider
        return getDisplayModelsForProvider(effective, _compactionModelSearchQuery.value)
    }

    /** Returns true when the given provider has valid credentials / auth configured. */
    fun isProviderConfigured(provider: LlmProvider): Boolean = when (provider) {
        LlmProvider.ETHOS_PREMIUM -> isPrivileged
        LlmProvider.OPEN_ROUTER -> prefs.apiKey.value.isNotBlank()
        LlmProvider.CLAUDE_OAUTH -> prefs.claudeOauthRefreshToken.value.isNotBlank()
        LlmProvider.OPENAI_OAUTH -> prefs.chatgptOauthRefreshToken.value.isNotBlank()
        LlmProvider.TINFOIL -> prefs.tinfoilApiKey.value.isNotBlank()
        LlmProvider.OPENAI -> prefs.openaiApiKey.value.isNotBlank()
        LlmProvider.VENICE -> prefs.veniceApiKey.value.isNotBlank()
        LlmProvider.LOCAL -> if (isPrivileged) true else app.modelDownloadManager.isModelDownloaded
        LlmProvider.CUSTOM -> prefs.customBaseUrl.value.isNotBlank() && prefs.customModelId.value.isNotBlank()
    }

    val isLedAvailable: Boolean get() = app.ledController.isAvailable

    fun toggleSkill(skillId: String, enabled: Boolean) {
        prefs.setSkillEnabled(skillId, enabled)
    }

    fun setBudgetModeEnabled(enabled: Boolean) {
        prefs.setBudgetModeEnabled(enabled)
    }

    fun selectBudgetPreset(presetId: String) {
        prefs.setSelectedBudgetPresetId(presetId)
    }

    fun saveBudgetPreset(preset: BudgetPreset) {
        val current = prefs.budgetPresets.value.toMutableList()
        val index = current.indexOfFirst { it.id == preset.id }
        if (index >= 0) {
            current[index] = preset
        } else {
            current.add(preset)
        }
        prefs.setBudgetPresets(current)
    }

    fun deleteBudgetPreset(presetId: String) {
        val current = prefs.budgetPresets.value.toMutableList()
        current.removeAll { it.id == presetId && !it.isStock }
        prefs.setBudgetPresets(current)
        if (prefs.selectedBudgetPresetId.value == presetId) {
            prefs.setSelectedBudgetPresetId(BudgetPreset.defaultPresetId)
        }
    }

    fun revertBudgetPreset(presetId: String) {
        val defaults = BudgetPreset.defaults()
        val defaultPreset = defaults.find { it.id == presetId } ?: return
        val current = prefs.budgetPresets.value.toMutableList()
        val index = current.indexOfFirst { it.id == presetId }
        if (index >= 0) {
            current[index] = defaultPreset
        }
        prefs.setBudgetPresets(current)
    }

    fun setSmartRoutingEnabled(enabled: Boolean) {
        prefs.setSmartRoutingEnabled(enabled)
    }

    fun setRoutingMode(mode: org.ethereumphone.andyclaw.skills.RoutingMode) {
        prefs.setRoutingMode(mode)
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
        // Record this as the user's explicit choice for the current routing provider
        prefs.setRoutingUserModelForProvider(prefs.routingProvider.value, modelId)
    }

    // ── Model routing (difficulty-based model selection) ──────────────

    fun setModelRoutingEnabled(enabled: Boolean) {
        prefs.setModelRoutingEnabled(enabled)
    }

    fun setModelRoutingLight(modelId: String) {
        prefs.setModelRoutingLight(modelId)
    }

    fun setModelRoutingStandard(modelId: String) {
        prefs.setModelRoutingStandard(modelId)
    }

    fun setModelRoutingPowerful(modelId: String) {
        prefs.setModelRoutingPowerful(modelId)
    }

    /** Available models for model routing tier selection (uses main provider). */
    val availableModelRoutingModels: List<AnthropicModels>
        get() {
            val provider = prefs.selectedProvider.value
            val effective = if (isPrivileged && provider == LlmProvider.LOCAL) LlmProvider.ETHOS_PREMIUM else provider
            return AnthropicModels.forProvider(effective)
        }

    /** Search/filter text for model routing tier selection. */
    private val _modelRoutingSearchQuery = MutableStateFlow("")
    val modelRoutingSearchQuery: StateFlow<String> = _modelRoutingSearchQuery.asStateFlow()
    fun setModelRoutingSearchQuery(query: String) { _modelRoutingSearchQuery.value = query }

    fun getModelRoutingDisplayModels(): List<DisplayModel> {
        val provider = prefs.selectedProvider.value
        val effective = if (isPrivileged && provider == LlmProvider.LOCAL) LlmProvider.ETHOS_PREMIUM else provider
        return getDisplayModelsForProvider(effective, _modelRoutingSearchQuery.value)
    }

    /** Available models filtered by the routing's selected provider. */
    val availableRoutingModels: List<AnthropicModels>
        get() {
            val provider = prefs.routingProvider.value
            val effective = if (isPrivileged && provider == LlmProvider.LOCAL) LlmProvider.ETHOS_PREMIUM else provider
            return AnthropicModels.forProvider(effective)
        }

    /** Search/filter text for routing model selection. */
    private val _routingModelSearchQuery = MutableStateFlow("")
    val routingModelSearchQuery: StateFlow<String> = _routingModelSearchQuery.asStateFlow()
    fun setRoutingModelSearchQuery(query: String) { _routingModelSearchQuery.value = query }

    fun getRoutingDisplayModels(): List<DisplayModel> {
        val provider = prefs.routingProvider.value
        val effective = if (isPrivileged && provider == LlmProvider.LOCAL) LlmProvider.ETHOS_PREMIUM else provider
        return getDisplayModelsForProvider(effective, _routingModelSearchQuery.value, includeEnumFallbacks = true)
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
        prefs.putString("memory.autoStore", if (enabled) "true" else "false")
    }

    fun setSmartExtractionEnabled(enabled: Boolean) {
        _smartExtractionEnabled.value = enabled
        prefs.putString("memory.smartExtraction", if (enabled) "true" else "false")
    }

    fun setAiRerankingEnabled(enabled: Boolean) {
        _aiRerankingEnabled.value = enabled
        prefs.putString("memory.aiReranking", if (enabled) "true" else "false")
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
        _smartExtractionEnabled.value = prefs.getString("memory.smartExtraction") == "true"
        _aiRerankingEnabled.value = prefs.getString("memory.aiReranking") == "true"
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
