package org.ethereumphone.andyclaw.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import org.ethereumphone.andyclaw.skills.SkillResult

data class ChatUiMessage(
    val id: String,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val isStreaming: Boolean = false,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NodeApp
    private val sessionManager: SessionManager = app.sessionManager
    private val memoryManager: MemoryManager = app.memoryManager

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

    private var currentJob: Job? = null
    private var approvalContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null

    data class ApprovalRequest(val description: String)

    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            _sessionId.value = sessionId
            val messages = sessionManager.getMessages(sessionId)
            _messages.value = messages.map { it.toUiMessage() }
        }
    }

    fun newSession() {
        viewModelScope.launch {
            val model = app.securePrefs.selectedModel.value
            val session = sessionManager.createSession(model = model)
            _sessionId.value = session.id
            _messages.value = emptyList()
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _isStreaming.value) return

        currentJob = viewModelScope.launch {
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

                override fun onToolResult(toolName: String, result: SkillResult) {
                    _currentToolExecution.value = null
                    val resultText = when (result) {
                        is SkillResult.Success -> result.data
                        is SkillResult.Error -> "Error: ${result.message}"
                        is SkillResult.RequiresApproval -> "Requires approval: ${result.description}"
                    }
                    viewModelScope.launch {
                        sessionManager.addMessage(sid, MessageRole.TOOL, resultText, toolName = toolName)
                    }
                    val toolMsg = ChatUiMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        role = "tool",
                        content = resultText,
                        toolName = toolName,
                    )
                    _messages.value = _messages.value + toolMsg
                }

                override suspend fun onApprovalNeeded(description: String): Boolean {
                    if (app.securePrefs.yoloMode.value) return true
                    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                        approvalContinuation = cont
                        _approvalRequest.value = ApprovalRequest(description)
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

                    // Auto-store conversation turn in memory for future context
                    autoStoreConversationTurn(text, fullText)
                }

                override fun onError(error: Throwable) {
                    // Flush any text that was streamed before the error
                    flushStreamingText(sid)
                    if (error is AnthropicApiException &&
                        error.statusCode == 403 &&
                        error.message?.contains("Insufficient balance") == true
                    ) {
                        _insufficientBalance.value = true
                    } else {
                        _error.value = error.message ?: "An error occurred"
                    }
                    _isStreaming.value = false
                    _currentToolExecution.value = null
                }
            })
        }
    }

    fun respondToApproval(approved: Boolean) {
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
    }

    fun clearError() {
        _error.value = null
    }

    fun clearInsufficientBalance() {
        _insufficientBalance.value = false
    }

    private fun flushStreamingText(sessionId: String) {
        val currentText = _streamingText.value
        if (currentText.isNotBlank()) {
            val assistantMsg = ChatUiMessage(
                id = java.util.UUID.randomUUID().toString(),
                role = "assistant",
                content = currentText,
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

    private fun SessionMessage.toUiMessage() = ChatUiMessage(
        id = id,
        role = role.name.lowercase(),
        content = content,
        toolName = toolName,
    )
}
