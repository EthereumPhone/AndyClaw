package org.ethereumphone.andyclaw.ui.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.IAgentDisplayService
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.agent.AgentLoop
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.llm.MessageContent
import org.ethereumphone.andyclaw.memory.MemoryManager
import org.ethereumphone.andyclaw.memory.model.MemorySource
import org.ethereumphone.andyclaw.sessions.SessionManager
import org.ethereumphone.andyclaw.sessions.model.MessageRole
import org.ethereumphone.andyclaw.sessions.model.SessionMessage
import org.ethereumphone.andyclaw.llm.AnthropicApiException
import org.ethereumphone.andyclaw.llm.LlmProvider
import org.ethereumphone.andyclaw.extensions.clawhub.DownloadAssessResult
import org.ethereumphone.andyclaw.extensions.clawhub.ThreatAssessment
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.math.BigDecimal

data class ChatUiMessage(
    val id: String,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolSummary: String? = null,
    val explorerUrl: String? = null,
    val isStreaming: Boolean = false,
    val isSecurityBlock: Boolean = false,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NodeApp
    private val sessionManager: SessionManager = app.sessionManager
    private val memoryManager: MemoryManager = app.memoryManager
    private val ledController = app.ledController

    private val _messages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val messages: StateFlow<List<ChatUiMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _currentToolExecution = MutableStateFlow<String?>(null)
    val currentToolExecution: StateFlow<String?> = _currentToolExecution.asStateFlow()

    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _insufficientBalance = MutableStateFlow(false)
    val insufficientBalance: StateFlow<Boolean> = _insufficientBalance.asStateFlow()

    private val _approvalRequest = MutableStateFlow<ApprovalRequest?>(null)
    val approvalRequest: StateFlow<ApprovalRequest?> = _approvalRequest.asStateFlow()

    private val _agentDisplayBitmap = MutableStateFlow<Bitmap?>(null)
    val agentDisplayBitmap: StateFlow<Bitmap?> = _agentDisplayBitmap.asStateFlow()

    private var agentDisplayJob: Job? = null
    private var currentJob: Job? = null
    private var approvalContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null
    private val pendingExplorerUrls = mutableListOf<String>()

    private val httpClient = OkHttpClient()

    data class ApprovalRequest(
        val description: String,
        val toolName: String? = null,
        val slug: String? = null,
        val threatAssessment: ThreatAssessment? = null,
    )

    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            _sessionId.value = sessionId
            val messages = sessionManager.getMessages(sessionId)
            // Attach explorer URLs: when a tool message has one, forward it
            // to the next assistant message so the button renders there.
            var pendingUrl: String? = null
            _messages.value = messages.map { msg ->
                val ui = msg.toUiMessage()
                if (msg.role == MessageRole.TOOL && msg.toolName != null) {
                    val formatted = ToolResultFormatter.format(msg.toolName!!, msg.content)
                    if (formatted.explorerUrl != null) pendingUrl = formatted.explorerUrl
                    ui
                } else if (msg.role == MessageRole.ASSISTANT && pendingUrl != null) {
                    val url = pendingUrl
                    pendingUrl = null
                    ui.copy(explorerUrl = url)
                } else {
                    ui
                }
            }
        }
    }

    fun newSession() {
        _sessionId.value = null
        _messages.value = emptyList()
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _isStreaming.value) return
        ledController.onUserMessage()

        currentJob = viewModelScope.launch {
            // Proactive balance check — only when using the premium gateway
            if (OsCapabilities.hasPrivilegedAccess &&
                app.securePrefs.selectedProvider.value == LlmProvider.ETHOS_PREMIUM
            ) {
                val walletAddress = app.securePrefs.walletAddress.value
                if (walletAddress.isNotBlank()) {
                    val balance = fetchUserBalance(walletAddress)
                    if (balance != null && balance < BigDecimal.ONE) {
                        _insufficientBalance.value = true
                        return@launch
                    }
                }
            }

            // Ensure session exists
            if (_sessionId.value == null) {
                val model = app.securePrefs.selectedModel.value
                val session = sessionManager.createSession(model = model)
                _sessionId.value = session.id
            }
            val sid = _sessionId.value!!

            // Add user message
            sessionManager.addMessage(sid, MessageRole.USER, text)
            val userMsg = ChatUiMessage(
                id = java.util.UUID.randomUUID().toString(),
                role = "user",
                content = text,
            )
            _messages.value = _messages.value + userMsg

            // Auto-title on first message
            if (_messages.value.size == 1) {
                val title = text.take(50).let { if (text.length > 50) "$it..." else it }
                sessionManager.updateSessionTitle(sid, title)
            }

            _isStreaming.value = true
            _streamingText.value = ""
            _error.value = null
            ledController.onPromptStart()

            // Build conversation history for agent loop
            val conversationHistory = buildConversationHistory()

            val modelId = app.securePrefs.selectedModel.value
            val model = AnthropicModels.fromModelId(modelId) ?: AnthropicModels.MINIMAX_M25
            val agentLoop = AgentLoop(
                client = app.getLlmClient(),
                skillRegistry = app.nativeSkillRegistry,
                tier = org.ethereumphone.andyclaw.skills.tier.OsCapabilities.currentTier(),
                enabledSkillIds = if (app.securePrefs.yoloMode.value) {
                    app.nativeSkillRegistry.getAll().map { it.id }.toSet()
                } else {
                    app.securePrefs.enabledSkills.value
                },
                model = model,
                aiName = app.userStoryManager.getAiName(),
                userStory = app.userStoryManager.read(),
                memoryManager = memoryManager,
                safetyLayer = app.createSafetyLayer(),
            )

            agentLoop.run(text, conversationHistory, object : AgentLoop.Callbacks {
                override fun onToken(text: String) {
                    _streamingText.value += text
                }

                override fun onToolExecution(toolName: String) {
                    // Flush any accumulated text as a committed assistant bubble
                    flushStreamingText(sid)
                    _currentToolExecution.value = toolName
                }

                override fun onToolResult(toolName: String, result: SkillResult, input: JsonObject?) {
                    _currentToolExecution.value = null
                    val resultText = when (result) {
                        is SkillResult.Success -> result.data
                        is SkillResult.ImageSuccess -> result.text
                        is SkillResult.Error -> "Error: ${result.message}"
                        is SkillResult.RequiresApproval -> "Requires approval: ${result.description}"
                    }
                    viewModelScope.launch {
                        sessionManager.addMessage(sid, MessageRole.TOOL, resultText, toolName = toolName)
                    }
                    val formatted = ToolResultFormatter.format(toolName, resultText, input)
                    if (formatted.explorerUrl != null) {
                        pendingExplorerUrls.add(formatted.explorerUrl)
                    }
                    val toolMsg = ChatUiMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        role = "tool",
                        content = formatted.detail,
                        toolName = toolName,
                        toolSummary = formatted.summary,
                    )
                    _messages.value = _messages.value + toolMsg

                    // Agent display preview lifecycle
                    if (toolName == "agent_display_create" && result !is SkillResult.Error) {
                        startDisplayCapture()
                    } else if (toolName == "agent_display_destroy" || toolName == "agent_display_destroy_and_promote") {
                        stopDisplayCapture()
                    }
                }

                override fun onSecurityBlock(toolName: String, reason: String) {
                    _currentToolExecution.value = null
                    val securityMsg = ChatUiMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        role = "system",
                        content = reason,
                        toolName = toolName,
                        isSecurityBlock = true,
                    )
                    _messages.value = _messages.value + securityMsg
                }

                override suspend fun onApprovalNeeded(
                    description: String,
                    toolName: String?,
                    toolInput: JsonObject?,
                ): Boolean {
                    if (app.securePrefs.yoloMode.value) return true

                    var threatAssessment: ThreatAssessment? = null
                    var slug: String? = null

                    if (toolName == "clawhub_install" && toolInput != null) {
                        slug = toolInput["slug"]?.jsonPrimitive?.content
                        val version = toolInput["version"]?.jsonPrimitive?.content
                        if (slug != null) {
                            try {
                                val result = app.clawHubManager.downloadAndAssess(slug, version)
                                if (result is DownloadAssessResult.Ready) {
                                    threatAssessment = result.assessment
                                }
                            } catch (e: Exception) {
                                Log.w("ChatViewModel", "Threat assessment failed for '$slug': ${e.message}")
                            }
                        }
                    }

                    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                        approvalContinuation = cont
                        _approvalRequest.value = ApprovalRequest(
                            description = description,
                            toolName = toolName,
                            slug = slug,
                            threatAssessment = threatAssessment,
                        )
                    }
                }

                override suspend fun onPermissionsNeeded(permissions: List<String>): Boolean {
                    val requester = app.permissionRequester ?: return false
                    return try {
                        val result = requester.requestIfMissing(permissions)
                        result.values.all { it }
                    } catch (e: Exception) {
                        false
                    }
                }

                override fun onComplete(fullText: String) {
                    // Flush any remaining streamed text as a final bubble
                    flushStreamingText(sid)
                    _isStreaming.value = false
                    _currentToolExecution.value = null
                    stopDisplayCapture()
                    ledController.onPromptComplete(fullText)

                    // Auto-store conversation turn in memory for future context
                    autoStoreConversationTurn(text, fullText)
                }

                override fun onError(error: Throwable) {
                    // Flush any text that was streamed before the error
                    flushStreamingText(sid)
                    Log.e("ChatViewModel", "LLM request failed: ${error.javaClass.simpleName}: ${error.message}", error)
                    if (error is AnthropicApiException &&
                        error.statusCode == 403 &&
                        error.message?.contains("Insufficient balance") == true &&
                        app.securePrefs.selectedProvider.value == LlmProvider.ETHOS_PREMIUM
                    ) {
                        _insufficientBalance.value = true
                    } else {
                        _error.value = error.message ?: "An error occurred"
                    }
                    _isStreaming.value = false
                    _currentToolExecution.value = null
                    stopDisplayCapture()
                    ledController.onPromptError()
                }
            })
        }
    }

    fun respondToApproval(approved: Boolean) {
        val request = _approvalRequest.value
        if (!approved && request?.toolName == "clawhub_install" && request.slug != null) {
            viewModelScope.launch {
                try {
                    app.clawHubManager.cancelPendingInstall(request.slug)
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "Cleanup after denial failed: ${e.message}")
                }
            }
        }
        @Suppress("DEPRECATION")
        approvalContinuation?.resume(approved, null)
        approvalContinuation = null
        _approvalRequest.value = null
    }

    fun cancel() {
        currentJob?.cancel()
        _isStreaming.value = false
        _streamingText.value = ""
        _currentToolExecution.value = null
        pendingExplorerUrls.clear()
        stopDisplayCapture()
    }

    fun clearError() {
        _error.value = null
    }

    fun clearInsufficientBalance() {
        _insufficientBalance.value = false
    }

    private fun startDisplayCapture() {
        if (agentDisplayJob?.isActive == true) return
        agentDisplayJob = viewModelScope.launch(Dispatchers.IO) {
            val svc = try {
                val smClass = Class.forName("android.os.ServiceManager")
                val getService = smClass.getMethod("getService", String::class.java)
                val binder = getService.invoke(null, "agentdisplay") as? IBinder
                binder?.let { IAgentDisplayService.Stub.asInterface(it) }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to get AgentDisplayService", e)
                null
            } ?: return@launch

            while (isActive) {
                try {
                    val frame = svc.captureFrame()
                    if (frame != null) {
                        val bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.size)
                        if (bitmap != null) {
                            _agentDisplayBitmap.value = bitmap
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Display capture failed", e)
                }
                delay(1000)
            }
        }
    }

    private fun stopDisplayCapture() {
        agentDisplayJob?.cancel()
        agentDisplayJob = null
        _agentDisplayBitmap.value = null
    }

    private fun flushStreamingText(sessionId: String) {
        val currentText = _streamingText.value
        if (currentText.isNotBlank()) {
            val urls = pendingExplorerUrls.toList()
            pendingExplorerUrls.clear()
            val assistantMsg = ChatUiMessage(
                id = java.util.UUID.randomUUID().toString(),
                role = "assistant",
                content = currentText,
                explorerUrl = urls.lastOrNull(),
            )
            _messages.value = _messages.value + assistantMsg
            viewModelScope.launch {
                sessionManager.addMessage(sessionId, MessageRole.ASSISTANT, currentText)
            }
        }
        _streamingText.value = ""
    }

    private fun buildConversationHistory(): List<Message> {
        // Convert persisted messages to Message objects (excluding last user msg which AgentLoop adds)
        val msgs = _messages.value.dropLast(1) // Drop the user msg we just added
        return msgs.mapNotNull { msg ->
            when (msg.role) {
                "user" -> Message.user(msg.content)
                "assistant" -> Message.assistant(listOf(ContentBlock.TextBlock(msg.content)))
                "tool" -> null // Tool results are handled within agent loop context
                else -> null
            }
        }
    }

    /**
     * Automatically store the user's message to long-term memory.
     *
     * Only the user's text is stored (not the assistant's reply) to keep
     * memories concise, searchable, and embedding-friendly. Generic assistant
     * acknowledgments like "Sure, I'll remember that!" dilute both keyword
     * and vector search quality.
     *
     * Skips very short messages (< 20 chars) which are unlikely to contain
     * memorable facts. Respects the user's auto-store preference from Settings.
     */
    private fun autoStoreConversationTurn(userText: String, @Suppress("UNUSED_PARAMETER") assistantText: String) {
        val autoStoreEnabled = app.securePrefs.getString("memory.autoStore") != "false"
        if (!autoStoreEnabled) return
        if (userText.length < 20) return

        viewModelScope.launch {
            try {
                memoryManager.store(
                    content = userText.take(500),
                    source = MemorySource.CONVERSATION,
                    tags = listOf("conversation"),
                    importance = 0.3f,
                )
            } catch (_: Exception) {
                // Memory storage is best-effort; don't disrupt the UI
            }
        }
    }

    private suspend fun fetchUserBalance(walletAddress: String): BigDecimal? =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://api.markushaas.com/api/get-user-balance?userId=$walletAddress"
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    val balance = JSONObject(body).optDouble("balance", 0.0)
                    BigDecimal.valueOf(balance)
                }
            } catch (_: Exception) {
                null
            }
        }

    private fun SessionMessage.toUiMessage(): ChatUiMessage {
        val formatted = if (role == MessageRole.TOOL && toolName != null) {
            ToolResultFormatter.format(toolName!!, content)
        } else null

        return ChatUiMessage(
            id = id,
            role = role.name.lowercase(),
            content = formatted?.detail ?: content,
            toolName = toolName,
            toolSummary = formatted?.summary,
        )
    }
}
