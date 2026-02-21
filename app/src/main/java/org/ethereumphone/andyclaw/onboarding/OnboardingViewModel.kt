package org.ethereumphone.andyclaw.onboarding

import android.Manifest
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ethereumphone.andyclaw.BuildConfig
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.LlmProvider
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.llm.MessagesRequest
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities
import org.ethereumphone.walletsdk.WalletSDK
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NodeApp

    /** True on ethOS devices — step 0 is wallet sign. False on standard Android — step 0 is API key. */
    val isPrivileged: Boolean = OsCapabilities.hasPrivilegedAccess

    // Wallet auth (ethOS only)
    val walletAddress = MutableStateFlow("")
    val walletSignature = MutableStateFlow("")
    val isSigning = MutableStateFlow(false)

    // API key auth (non-ethOS only)
    val apiKey = MutableStateFlow("")

    // Provider selection (non-ethOS only)
    val selectedProvider = MutableStateFlow(LlmProvider.OPEN_ROUTER)
    val tinfoilApiKey = MutableStateFlow("")

    val goals = MutableStateFlow("")
    val customName = MutableStateFlow(generateFunnyName())
    val values = MutableStateFlow("")

    val totalSteps: Int = 5 // Auth (sign or api key), Goals, Name, Values, Permissions

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

    fun signWithWallet() {
        if (isSigning.value) return
        isSigning.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val rpc = "https://base-mainnet.g.alchemy.com/v2/${BuildConfig.ALCHEMY_API}"
                val sdk = WalletSDK(
                    context = app,
                    web3jInstance = Web3j.build(HttpService(rpc)),
                    bundlerRPCUrl = "https://api.pimlico.io/v2/8453/rpc?apikey=${BuildConfig.BUNDLER_API}",
                )
                val address = withContext(Dispatchers.IO) { sdk.getAddress() }
                val sig = sdk.signMessage("Signing into AndyClaw", chainId = 8453)
                walletAddress.value = address
                walletSignature.value = sig
            } catch (e: Exception) {
                Log.e("OnboardingViewModel", "Wallet signing failed", e)
                _error.value = "Wallet signing failed: ${e.message}"
            } finally {
                isSigning.value = false
            }
        }
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
                // Save auth so the LLM client can use it
                if (isPrivileged) {
                    app.securePrefs.setWalletAuth(walletAddress.value, walletSignature.value)
                } else {
                    val provider = selectedProvider.value
                    app.securePrefs.setSelectedProvider(provider)
                    when (provider) {
                        LlmProvider.OPEN_ROUTER -> app.securePrefs.setApiKey(apiKey.value.trim())
                        LlmProvider.TINFOIL -> app.securePrefs.setTinfoilApiKey(tinfoilApiKey.value.trim())
                        LlmProvider.LOCAL -> { /* No API key needed */ }
                    }
                    // Set default model for the selected provider
                    val defaultModel = AnthropicModels.defaultForProvider(provider)
                    app.securePrefs.setSelectedModel(defaultModel.modelId)
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

                val story = try {
                    val provider = if (isPrivileged) LlmProvider.OPEN_ROUTER else selectedProvider.value
                    val profileModel = AnthropicModels.defaultForProvider(provider)
                    val request = MessagesRequest(
                        model = profileModel.modelId,
                        maxTokens = 1024,
                        messages = listOf(Message.user(prompt)),
                    )

                    val response = app.getLlmClient().sendMessage(request)
                    val text = response.content
                        .filterIsInstance<ContentBlock.TextBlock>()
                        .joinToString("\n") { it.text }

                    // Ensure the name heading is present even if the LLM omitted it
                    if (text.startsWith("# Name:")) text
                    else "# Name: $aiName\n\n$text"
                } catch (e: Exception) {
                    Log.w("OnboardingViewModel", "Profile generation failed, using template", e)
                    buildString {
                        appendLine("# Name: $aiName")
                        appendLine()
                        appendLine("## Story")
                        if (userGoals.isNotBlank()) appendLine("You want to: $userGoals")
                        if (userValues.isNotBlank()) appendLine("You care about: $userValues")
                    }.trim()
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
                Log.e("OnboardingViewModel", "Onboarding submit failed", e)
                _error.value = e.message ?: "Failed to complete setup"
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
