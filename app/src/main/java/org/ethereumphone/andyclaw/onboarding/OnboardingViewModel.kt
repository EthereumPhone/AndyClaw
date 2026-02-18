package org.ethereumphone.andyclaw.onboarding

import android.Manifest
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.BuildConfig
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.llm.MessagesRequest
import org.ethereumphone.andyclaw.skills.AndyClawSkill

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NodeApp

    val apiKey = MutableStateFlow("")
    val goals = MutableStateFlow("")
    val customName = MutableStateFlow(generateFunnyName())
    val values = MutableStateFlow("")

    val needsApiKey: Boolean = BuildConfig.OPENROUTER_API_KEY.isEmpty()
    val totalSteps: Int = if (needsApiKey) 5 else 4

    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _yoloMode = MutableStateFlow(false)
    val yoloMode: StateFlow<Boolean> = _yoloMode.asStateFlow()

    private val _selectedSkills = MutableStateFlow<Set<String>>(emptySet())
    val selectedSkills: StateFlow<Set<String>> = _selectedSkills.asStateFlow()

    val registeredSkills: List<AndyClawSkill>
        get() = app.nativeSkillRegistry.getAll()

    fun nextStep() {
        if (_currentStep.value < totalSteps - 1) _currentStep.value++
    }

    fun previousStep() {
        if (_currentStep.value > 0) _currentStep.value--
    }

    fun setYoloMode(enabled: Boolean) {
        _yoloMode.value = enabled
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        val current = _selectedSkills.value.toMutableSet()
        if (enabled) current.add(skillId) else current.remove(skillId)
        _selectedSkills.value = current.toSet()
    }

    fun submit(onComplete: () -> Unit) {
        if (_isSubmitting.value) return
        _isSubmitting.value = true
        _error.value = null

        val aiName = customName.value.trim().ifEmpty { "AndyClaw" }
        val userGoals = goals.value.trim()
        val userValues = values.value.trim()

        viewModelScope.launch {
            try {
                // Save API key before using the client so resolveApiKey() picks it up
                if (needsApiKey) {
                    app.securePrefs.setApiKey(apiKey.value.trim())
                }

                val prompt = buildString {
                    appendLine("You are helping set up a personalized AI assistant for a dGEN1 Ethereum Phone user.")
                    appendLine("Based on the user's answers below, write a concise user profile in markdown.")
                    appendLine()
                    appendLine("Format requirements:")
                    appendLine("- First line MUST be: # Name: $aiName")
                    appendLine("- Then a ## Story section summarizing who this user is and what they want")
                    appendLine("- Keep it under 200 words")
                    appendLine("- Write in second person (\"you\")")
                    appendLine()
                    appendLine("User's answers:")
                    appendLine("Goals: $userGoals")
                    appendLine("Values/Priorities: $userValues")
                    appendLine("Chosen AI name: $aiName")
                }

                val request = MessagesRequest(
                    model = AnthropicModels.MINIMAX_M25.modelId,
                    maxTokens = 1024,
                    messages = listOf(Message.user(prompt)),
                )

                val response = app.anthropicClient.sendMessage(request)
                val text = response.content
                    .filterIsInstance<ContentBlock.TextBlock>()
                    .joinToString("\n") { it.text }

                // Ensure the name heading is present even if the LLM omitted it
                val story = if (text.startsWith("# Name:")) {
                    text
                } else {
                    "# Name: $aiName\n\n$text"
                }

                app.userStoryManager.write(story)
                app.securePrefs.setAiName(aiName)

                // Persist YOLO mode and skill selections
                val isYolo = _yoloMode.value
                app.securePrefs.setYoloMode(isYolo)
                if (isYolo) {
                    val allIds = app.nativeSkillRegistry.getAll().map { it.id }.toSet()
                    app.securePrefs.setAllSkillsEnabled(allIds)
                } else {
                    app.securePrefs.setAllSkillsEnabled(_selectedSkills.value)
                }

                // Request all runtime permissions the app may need
                requestAllPermissions()

                _isSubmitting.value = false
                onComplete()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to generate profile"
                _isSubmitting.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    companion object {
        private val ADJECTIVES = listOf(
            "Cosmic", "Turbo", "Mega", "Pixel", "Neon", "Quantum", "Glitch",
            "Fuzzy", "Hyper", "Mighty", "Sneaky", "Spicy", "Chill", "Zippy",
            "Groovy", "Wacky", "Crispy", "Jolly", "Breezy", "Zesty",
        )
        private val NOUNS = listOf(
            "Panda", "Wizard", "Goblin", "Nugget", "Toaster", "Llama", "Pickle",
            "Walrus", "Potato", "Waffle", "Penguin", "Noodle", "Muffin", "Cactus",
            "Banana", "Otter", "Taco", "Yeti", "Pretzel", "Badger",
        )

        fun generateFunnyName(): String = ADJECTIVES.random() + NOUNS.random()
    }

    private suspend fun requestAllPermissions() {
        val requester = app.permissionRequester ?: return
        val permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        try {
            val results = requester.requestIfMissing(permissions)
            Log.i("OnboardingViewModel", "Permission results: $results")
        } catch (e: Exception) {
            Log.w("OnboardingViewModel", "Permission request failed: ${e.message}")
        }
    }
}
