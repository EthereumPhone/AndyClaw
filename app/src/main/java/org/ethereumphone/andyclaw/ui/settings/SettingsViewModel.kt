package org.ethereumphone.andyclaw.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NodeApp
    private val prefs = app.securePrefs

    val apiKey = prefs.apiKey
    val selectedModel = prefs.selectedModel

    private val _isApiKeyValid = MutableStateFlow<Boolean?>(null)
    val isApiKeyValid: StateFlow<Boolean?> = _isApiKeyValid.asStateFlow()

    val currentTier: String get() = OsCapabilities.currentTier().name
    val isPrivileged: Boolean get() = OsCapabilities.hasPrivilegedAccess

    val availableModels = AnthropicModels.entries.toList()

    val registeredSkills = app.nativeSkillRegistry.getAll()

    fun setApiKey(key: String) {
        prefs.setApiKey(key)
        _isApiKeyValid.value = null
    }

    fun setSelectedModel(modelId: String) {
        prefs.setSelectedModel(modelId)
    }

    fun validateApiKey() {
        val key = prefs.apiKey.value
        if (key.isBlank()) {
            _isApiKeyValid.value = false
            return
        }
        // Basic format validation - Anthropic keys start with "sk-ant-"
        _isApiKeyValid.value = key.startsWith("sk-ant-") && key.length > 20
    }
}
