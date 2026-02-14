package org.ethereumphone.andyclaw.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.llm.MessagesRequest

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NodeApp

    val goals = MutableStateFlow("")
    val customName = MutableStateFlow("")
    val values = MutableStateFlow("")

    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun nextStep() {
        if (_currentStep.value < 2) _currentStep.value++
    }

    fun previousStep() {
        if (_currentStep.value > 0) _currentStep.value--
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
                    model = AnthropicModels.SONNET_4.modelId,
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
}
