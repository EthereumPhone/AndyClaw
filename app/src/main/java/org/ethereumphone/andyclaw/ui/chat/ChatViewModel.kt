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
import org.ethereumphone.andyclaw.agent.BudgetConfig
import org.ethereumphone.andyclaw.agent.BudgetPreset
import org.ethereumphone.andyclaw.agent.ContextCompactor
import org.ethereumphone.andyclaw.agent.TokenUsageSnapshot
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
import org.ethereumphone.andyclaw.commands.SlashCommand
import org.ethereumphone.andyclaw.commands.SlashCommandExecutor
import org.ethereumphone.andyclaw.commands.SlashCommandRegistry
import org.ethereumphone.andyclaw.commands.SlashCommandResult
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

/**
 * Snapshot of how much of the model's context window is currently in use.
 * [usedTokens] is the prompt size from the last API call (conversation + system + tools).
 * [maxTokens] is the model's total context window.
 */
data class ContextWindowState(
    val usedTokens: Int = 0,
    val maxTokens: Int = 0,
) {
    val percentage: Float get() = if (maxTokens > 0) usedTokens.toFloat() / maxTokens else 0f
    val isAvailable: Boolean get() = maxTokens > 0
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NodeApp
    private val sessionManager: SessionManager = app.sessionManager
    private val memoryManager: MemoryManager = app.memoryManager
    private val ledController = app.ledController

    val slashExecutor = SlashCommandExecutor(app.securePrefs, memoryManager)

    private val _slashCommandResult = MutableStateFlow<SlashCommandResult?>(null)
    val slashCommandResult: StateFlow<SlashCommandResult?> = _slashCommandResult.asStateFlow()

    private val _navigationEvent = MutableStateFlow<String?>(null)
    val navigationEvent: StateFlow<String?> = _navigationEvent.asStateFlow()

    private val _slashSuggestions = MutableStateFlow<List<SlashCommand>>(emptyList())
    val slashSuggestions: StateFlow<List<SlashCommand>> = _slashSuggestions.asStateFlow()

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

    private val _askUserRequest = MutableStateFlow<org.ethereumphone.andyclaw.agent.AskUserRequest?>(null)
    val askUserRequest: StateFlow<org.ethereumphone.andyclaw.agent.AskUserRequest?> = _askUserRequest.asStateFlow()

    /** Pending ask_user request stored during the turn, shown after onComplete. */
    private var pendingAskUserRequest: org.ethereumphone.andyclaw.agent.AskUserRequest? = null

    private val _contextWindow = MutableStateFlow(ContextWindowState())
    val contextWindow: StateFlow<ContextWindowState> = _contextWindow.asStateFlow()

    private val _agentDisplayBitmap = MutableStateFlow<Bitmap?>(null)
    val agentDisplayBitmap: StateFlow<Bitmap?> = _agentDisplayBitmap.asStateFlow()

    private var agentDisplayJob: Job? = null
    private val _isCompacting = MutableStateFlow(false)
    val isCompacting: StateFlow<Boolean> = _isCompacting.asStateFlow()

    private var turnsSinceLastCompaction = 0

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

            // Restore context window state from persisted session data
            val session = sessionManager.getSession(sessionId)
            if (session != null && session.lastContextUsed > 0) {
                _contextWindow.value = ContextWindowState(
                    usedTokens = session.lastContextUsed,
                    maxTokens = session.contextLimit,
                )
            } else {
                _contextWindow.value = ContextWindowState()
            }
        }
    }

    fun newSession() {
        _sessionId.value = null
        _messages.value = emptyList()
        _contextWindow.value = ContextWindowState()
    }

    /**
     * Manually trigger context compaction for the current session.
     * Old messages are preserved in the UI; only the LLM context is compacted.
     */
    fun compactNow() {
        val sid = _sessionId.value
        if (sid == null) {
            Log.w("ChatViewModel", "compactNow() skipped: no active session")
            return
        }
        if (_isStreaming.value || _isCompacting.value) {
            Log.d("ChatViewModel", "compactNow() skipped: isStreaming=${_isStreaming.value}, isCompacting=${_isCompacting.value}")
            return
        }
        Log.i("ChatViewModel", "=== compactNow() manual trigger === sessionId=$sid")
        viewModelScope.launch {
            _isCompacting.value = true
            val startMs = System.currentTimeMillis()
            try {
                val history = buildConversationHistory()
                Log.i("ChatViewModel", "compactNow: conversationHistory=${history.size} messages")
                if (history.size < 3) {
                    Log.w("ChatViewModel", "compactNow: history too short (${history.size}), need at least 3 messages")
                    return@launch
                }
                val keepRecent = 2.coerceAtMost(history.size - 1)
                val budgetCfg = BudgetConfig(
                    (app.createBudgetConfig()?.preset ?: BudgetPreset.defaults().first())
                        .copy(historySummarization = true)
                )
                Log.d("ChatViewModel", "compactNow: keepRecent=$keepRecent, historySize=${history.size}")
                val compactionModelId = app.getCompactionModelId()
                val compactionClient = app.getCompactionLlmClient()
                Log.i("ChatViewModel", "compactNow: using model=$compactionModelId, client=${compactionClient.javaClass.simpleName}, " +
                    "useSameModel=${app.securePrefs.compactionUseSameModel.value}")
                val compactor = ContextCompactor(compactionClient, budgetCfg)
                val result = withContext(Dispatchers.IO) {
                    compactor.compact(history, compactionModelId, keepRecentOverride = keepRecent)
                }
                val elapsedMs = System.currentTimeMillis() - startMs
                if (result.wasCompacted && result.summaryText.isNotBlank()) {
                    sessionManager.addMessage(sid, MessageRole.CONTEXT_SUMMARY, result.summaryText)
                    _messages.value = _messages.value + ChatUiMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        role = "context_summary",
                        content = result.summaryText,
                    )
                    turnsSinceLastCompaction = 0
                    Log.i("ChatViewModel", "compactNow DONE: summarized ${result.removedMessageCount} messages, " +
                        "summaryLength=${result.summaryText.length}, totalMs=${elapsedMs}")
                } else {
                    Log.i("ChatViewModel", "compactNow: nothing to compact (wasCompacted=${result.wasCompacted}, " +
                        "summaryBlank=${result.summaryText.isBlank()}), totalMs=${elapsedMs}")
                }
            } catch (e: Exception) {
                val elapsedMs = System.currentTimeMillis() - startMs
                Log.e("ChatViewModel", "compactNow FAILED after ${elapsedMs}ms: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                _isCompacting.value = false
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _isStreaming.value || _isCompacting.value) return

        // ── Slash command interception ──────────────────────────────────
        val cmdResult = slashExecutor.execute(text)
        if (cmdResult != null) {
            handleSlashResult(text, cmdResult)
            return
        }

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
                // Initialize context window with model limit so the bar is visible immediately
                if (_contextWindow.value.maxTokens <= 0) {
                    // Ensure OpenRouter registry is loaded for dynamic context window resolution
                    try { app.openRouterModelRegistry.refreshIfNeeded() } catch (_: Exception) {}
                    val contextLimit = resolveContextLimit(model)
                    if (contextLimit > 0) {
                        _contextWindow.value = ContextWindowState(usedTokens = 0, maxTokens = contextLimit)
                    }
                }
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
            var conversationHistory = buildConversationHistory()

            // ── Context compaction check ──
            // Old messages stay in DB/UI; only the LLM context is compacted.
            val budgetCfg = app.createBudgetConfig()
            val ctxState = _contextWindow.value
            turnsSinceLastCompaction++
            Log.d("ChatViewModel", "Auto-compact check: usedTokens=${ctxState.usedTokens}, maxTokens=${ctxState.maxTokens}, " +
                "pct=${(ctxState.percentage * 100).toInt()}%, turns=$turnsSinceLastCompaction, " +
                "historySummarization=${budgetCfg?.preset?.historySummarization}, " +
                "threshold=${budgetCfg?.preset?.compactionThreshold}, interval=${budgetCfg?.preset?.compactionInterval}")
            val shouldCompact = budgetCfg != null && budgetCfg.shouldCompact(ctxState.usedTokens, ctxState.maxTokens, turnsSinceLastCompaction)
            Log.d("ChatViewModel", "Auto-compact decision: shouldCompact=$shouldCompact")
            if (shouldCompact) {
                val autoCompactStart = System.currentTimeMillis()
                try {
                    _isCompacting.value = true
                    val compactionModelId = app.getCompactionModelId()
                    val compactionClient = app.getCompactionLlmClient()
                    Log.i("ChatViewModel", "=== Auto-compact TRIGGERED === " +
                        "usedTokens=${ctxState.usedTokens}/${ctxState.maxTokens} (${(ctxState.percentage * 100).toInt()}%), " +
                        "turns=$turnsSinceLastCompaction, model=$compactionModelId, " +
                        "client=${compactionClient.javaClass.simpleName}, " +
                        "historySize=${conversationHistory.size}")
                    val compactor = ContextCompactor(compactionClient, budgetCfg!!)
                    val compactResult = compactor.compact(conversationHistory, compactionModelId)
                    val autoCompactMs = System.currentTimeMillis() - autoCompactStart
                    if (compactResult.wasCompacted) {
                        conversationHistory = compactResult.compactedHistory
                        if (compactResult.summaryText.isNotBlank()) {
                            sessionManager.addMessage(sid, MessageRole.CONTEXT_SUMMARY, compactResult.summaryText)
                            _messages.value = _messages.value + ChatUiMessage(
                                id = java.util.UUID.randomUUID().toString(),
                                role = "context_summary",
                                content = compactResult.summaryText,
                            )
                        }
                        turnsSinceLastCompaction = 0
                        Log.i("ChatViewModel", "Auto-compact DONE: removed=${compactResult.removedMessageCount}, " +
                            "summaryLen=${compactResult.summaryText.length}, " +
                            "newHistorySize=${conversationHistory.size}, totalMs=${autoCompactMs}")
                    } else {
                        Log.i("ChatViewModel", "Auto-compact: compactor returned wasCompacted=false, totalMs=${autoCompactMs}")
                    }
                } catch (e: Exception) {
                    val autoCompactMs = System.currentTimeMillis() - autoCompactStart
                    Log.e("ChatViewModel", "Auto-compact FAILED after ${autoCompactMs}ms, continuing without it: " +
                        "${e.javaClass.simpleName}: ${e.message}", e)
                } finally {
                    _isCompacting.value = false
                }
            }

            val modelId = app.securePrefs.selectedModel.value
            val model = AnthropicModels.fromModelId(modelId) ?: AnthropicModels.MINIMAX_M25
            val currentTier = org.ethereumphone.andyclaw.skills.tier.OsCapabilities.currentTier()
            val currentEnabledSkillIds = if (app.securePrefs.yoloMode.value) {
                app.nativeSkillRegistry.getAll().map { it.id }.toSet()
            } else {
                app.securePrefs.enabledSkills.value
            }
            val agentLoop = AgentLoop(
                client = app.getLlmClient(),
                skillRegistry = app.nativeSkillRegistry,
                tier = currentTier,
                enabledSkillIds = currentEnabledSkillIds,
                model = model,
                aiName = app.userStoryManager.getAiName(),
                userStory = app.userStoryManager.read(),
                soulContent = app.soulManager.read(),
                memoryManager = memoryManager,
                safetyLayer = app.createSafetyLayer(),
                smartRouter = if (app.securePrefs.smartRoutingEnabled.value && !app.securePrefs.toolSearchEnabled.value) app.smartRouter else null,
                toolSearchService = app.createToolSearchService(currentTier, currentEnabledSkillIds),
                budgetConfig = app.createBudgetConfig(),
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

                override fun onAskUserDisplayed(request: org.ethereumphone.andyclaw.agent.AskUserRequest) {
                    // Store pending — overlay shown after turn completes (onComplete)
                    pendingAskUserRequest = request
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

                override fun onComplete(fullText: String, tokenUsage: TokenUsageSnapshot?) {
                    // Flush any remaining streamed text as a final bubble
                    flushStreamingText(sid)
                    _isStreaming.value = false
                    _currentToolExecution.value = null
                    stopDisplayCapture()
                    ledController.onPromptComplete(fullText)

                    // Update context window usage
                    if (tokenUsage != null) {
                        updateContextWindow(tokenUsage, modelId)
                        // Persist token usage + context window state to session DB
                        viewModelScope.launch {
                            sessionManager.addTokenUsage(
                                sid,
                                tokenUsage.totalInputTokens,
                                tokenUsage.totalOutputTokens,
                                tokenUsage.totalInputTokens + tokenUsage.totalOutputTokens,
                            )
                            val ctx = _contextWindow.value
                            sessionManager.updateContextWindow(sid, ctx.usedTokens, ctx.maxTokens)
                        }
                    }

                    // Auto-store conversation turn in memory for future context
                    autoStoreConversationTurn(text, fullText)

                    // Show ask_user overlay now that the turn is fully complete
                    pendingAskUserRequest?.let {
                        _askUserRequest.value = it
                        pendingAskUserRequest = null
                    }
                }

                override fun onError(error: Throwable) {
                    pendingAskUserRequest = null
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

    /**
     * Called from the UI when the user submits answers to ask_user questions.
     * Formats the Q&A and sends it as a new user message (new turn).
     * Pass null if the user skips/dismisses.
     */
    fun respondToAskUser(response: org.ethereumphone.andyclaw.agent.AskUserResponse?) {
        val request = _askUserRequest.value
        _askUserRequest.value = null
        if (response != null && request != null) {
            val chatText = org.ethereumphone.andyclaw.agent.formatAskUserForChat(request, response)
            sendMessage(chatText)
        } else {
            sendMessage("[User dismissed — do not proceed, wait for next instruction]")
        }
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

        // If a CONTEXT_SUMMARY exists, use it as the boundary:
        // only include the summary + messages after it for the LLM.
        // Old messages before the summary stay in the UI but are not sent to the LLM.
        val lastSummaryIndex = msgs.indexOfLast { it.role == "context_summary" }
        val effectiveMsgs = if (lastSummaryIndex >= 0) {
            Log.d("ChatViewModel", "buildConversationHistory: found CONTEXT_SUMMARY at index $lastSummaryIndex/${msgs.size}, " +
                "using ${msgs.size - lastSummaryIndex} of ${msgs.size} messages for LLM")
            msgs.subList(lastSummaryIndex, msgs.size)
        } else {
            Log.d("ChatViewModel", "buildConversationHistory: no CONTEXT_SUMMARY found, using all ${msgs.size} messages")
            msgs
        }

        return effectiveMsgs.mapNotNull { msg ->
            when (msg.role) {
                "user" -> Message.user(msg.content)
                "assistant" -> Message.assistant(listOf(ContentBlock.TextBlock(msg.content)))
                "context_summary" -> Message.user(
                    "<context_summary>\n" +
                    "This is a compacted summary of older messages in this conversation. " +
                    "If you need more details about something mentioned here, " +
                    "use the search_memory skill to retrieve relevant context from long-term memory.\n\n" +
                    msg.content + "\n" +
                    "</context_summary>"
                )
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
    /**
     * Resolve the model's context window and update [_contextWindow] with the
     * latest token usage from the API response.
     */
    private fun resolveContextLimit(modelId: String): Int {
        // Try OpenRouter registry first (dynamic, most accurate)
        val registryModel = app.openRouterModelRegistry.getModelById(modelId)
        if (registryModel != null && registryModel.contextLength > 0) {
            return registryModel.contextLength
        }
        // Fall back to static enum value
        val enumModel = AnthropicModels.fromModelId(modelId)
        if (enumModel != null && enumModel.contextWindow > 0) {
            return enumModel.contextWindow
        }
        // Last resort: if the previous context window had a valid limit, keep it
        // (the model didn't change, we just couldn't resolve it this time)
        if (_contextWindow.value.maxTokens > 0) {
            return _contextWindow.value.maxTokens
        }
        Log.w("ChatViewModel", "ContextWindow | could not resolve context limit for model=$modelId " +
            "(registry=${registryModel != null}, enum=${enumModel != null})")
        return 0
    }

    private fun updateContextWindow(tokenUsage: TokenUsageSnapshot, modelId: String) {
        // lastInputTokens already includes non-cached + cached tokens = true full prompt size.
        // Don't add totalOutputTokens — output becomes part of next turn's input automatically.
        val used = tokenUsage.lastInputTokens
        val contextLimit = resolveContextLimit(modelId)

        _contextWindow.value = ContextWindowState(
            usedTokens = used,
            maxTokens = contextLimit,
        )
        Log.d("ChatViewModel", "ContextWindow | used=$used/$contextLimit (${String.format("%.1f", if (contextLimit > 0) used * 100f / contextLimit else 0f)}%) inputTokens=${tokenUsage.lastInputTokens} (cache_read=${tokenUsage.cacheReadTokens} cache_write=${tokenUsage.cacheWriteTokens})")
    }

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

    // ── Slash command handling ────────────────────────────────────────

    private fun handleSlashResult(rawInput: String, result: SlashCommandResult) {
        // Show user's command as a message
        val userMsg = ChatUiMessage(
            id = java.util.UUID.randomUUID().toString(),
            role = "user",
            content = rawInput,
        )
        _messages.value = _messages.value + userMsg

        // Show system feedback (skip for /compact — result shown as context_summary card)
        if (result.message.isNotBlank() && result.message != "compact") {
            val systemMsg = ChatUiMessage(
                id = java.util.UUID.randomUUID().toString(),
                role = "system",
                content = result.message,
            )
            _messages.value = _messages.value + systemMsg
        }

        _slashCommandResult.value = result

        when (result) {
            is SlashCommandResult.ActionDone -> {
                when (result.message) {
                    "Conversation cleared." -> newSession()
                    "Memory reindex started." -> viewModelScope.launch {
                        try { memoryManager.reindex(force = true) } catch (_: Exception) { }
                    }
                    "compact" -> compactNow()
                }
            }
            is SlashCommandResult.Navigate -> {
                _navigationEvent.value = result.route
            }
            else -> { /* Toggles, cycles, help, errors — message is enough */ }
        }
    }

    /**
     * Select a cycle option after the user picks from the presented list.
     */
    fun selectCycleOption(commandId: String, index: Int) {
        val result = slashExecutor.selectCycleOption(commandId, index)
        val systemMsg = ChatUiMessage(
            id = java.util.UUID.randomUUID().toString(),
            role = "system",
            content = result.message,
        )
        _messages.value = _messages.value + systemMsg
        _slashCommandResult.value = result
    }

    /**
     * Called by the UI as the user types — updates autocomplete suggestions.
     */
    fun onInputChanged(text: String) {
        if (text.startsWith("/")) {
            val prefix = text.removePrefix("/").lowercase()
            _slashSuggestions.value = SlashCommandRegistry.matching(prefix)
        } else {
            _slashSuggestions.value = emptyList()
        }
    }

    fun triggerReindex() {
        viewModelScope.launch {
            try {
                memoryManager.reindex(force = true)
            } catch (_: Exception) { }
        }
    }

    fun consumeSlashResult() {
        _slashCommandResult.value = null
    }

    fun consumeNavigationEvent() {
        _navigationEvent.value = null
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
