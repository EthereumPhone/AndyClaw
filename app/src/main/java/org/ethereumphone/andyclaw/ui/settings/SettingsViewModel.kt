package org.ethereumphone.andyclaw.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NodeApp
    private val prefs = app.securePrefs

    val selectedModel = prefs.selectedModel
    val yoloMode = prefs.yoloMode

    val currentTier: String get() = OsCapabilities.currentTier().name
    val isPrivileged: Boolean get() = OsCapabilities.hasPrivilegedAccess

    val availableModels = AnthropicModels.entries.toList()

    val registeredSkills = app.nativeSkillRegistry.getAll()

    fun setSelectedModel(modelId: String) {
        prefs.setSelectedModel(modelId)
    }

    fun setYoloMode(enabled: Boolean) {
        prefs.setYoloMode(enabled)
    }

}
