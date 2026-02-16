package org.ethereumphone.andyclaw.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.extensions.ExtensionDescriptor
import org.ethereumphone.andyclaw.extensions.toSkillAdapters
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NodeApp
    private val prefs = app.securePrefs

    val selectedModel = prefs.selectedModel
    val yoloMode = prefs.yoloMode
    val enabledSkills = prefs.enabledSkills

    val currentTier: String get() = OsCapabilities.currentTier().name
    val isPrivileged: Boolean get() = OsCapabilities.hasPrivilegedAccess

    val availableModels = AnthropicModels.entries.toList()

    val registeredSkills get() = app.nativeSkillRegistry.getAll()

    // ── Memory ──────────────────────────────────────────────────────────

    private val _memoryCount = MutableStateFlow(0)
    val memoryCount: StateFlow<Int> = _memoryCount.asStateFlow()

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
        refreshMemoryCount()
        refreshExtensions()
    }

    // ── Actions ─────────────────────────────────────────────────────────

    fun setSelectedModel(modelId: String) {
        prefs.setSelectedModel(modelId)
    }

    fun setYoloMode(enabled: Boolean) {
        prefs.setYoloMode(enabled)
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        prefs.setSkillEnabled(skillId, enabled)
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
                val memories = app.memoryManager.list(limit = 1000)
                for (memory in memories) {
                    app.memoryManager.delete(memory.id)
                }
                _memoryCount.value = 0
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

    // ── Internal ────────────────────────────────────────────────────────

    private fun refreshMemoryCount() {
        viewModelScope.launch {
            try {
                _memoryCount.value = app.memoryManager.list(limit = 10_000).size
            } catch (_: Exception) {
                _memoryCount.value = 0
            }
        }
        // Load saved preference
        _autoStoreEnabled.value = prefs.getString("memory.autoStore") != "false"
    }

    private fun refreshExtensions() {
        _extensions.value = app.extensionEngine.registry.getAll()
    }
}
