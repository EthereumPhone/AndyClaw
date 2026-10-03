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
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.agent.AgentLoop
import org.ethereumphone.andyclaw.agent.BackgroundMemoryExtractor
import org.ethereumphone.andyclaw.agent.CompactionConfig
import org.ethereumphone.andyclaw.agent.MemoryReranker
import org.ethereumphone.andyclaw.agent.ContextCompactor
import org.ethereumphone.andyclaw.agent.TokenUsageSnapshot
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.LocalLlmClient
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.ethereumphone.andyclaw.commands.SlashCommand
import org.ethereumphone.andyclaw.commands.SlashCommandExecutor
import org.ethereumphone.andyclaw.commands.SlashCommandRegistry
import org.ethereumphone.andyclaw.commands.SlashCommandResult

data class ChatUiMessage(
    val id: String,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolSummary: String? = null,
    val explorerUrl: String? = null,
    val isStreaming: Boolean = false,
    val isSecurityBlock: Boolean = false,
    /**
     * Context summaries only: how many conversation messages immediately before this one
     * (user/assistant, not tool, system, transient or older summaries) stay in the model's
     * context verbatim after it. Persisted in the row's `toolCallId` as `kept:N`.
     */
    val keptBefore: Int = 0,
    /** Shown but never persisted (a slash command's echo): never sent to the model either. */
    val transient: Boolean = false,
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

    /**
     * Background memory extractor, one per chat: it counts what it has read, and a new one per
     * turn re-read the whole conversation every time. Reset with the session.
     */
    private var backgroundExtractor: BackgroundMemoryExtractor? = null
    private var backgroundExtractorFor: Pair<org.ethereumphone.andyclaw.llm.LlmClient, String>? = null

    val slashExecutor = SlashCommandExecutor(
        app.securePrefs,
        memoryManager,
        providerChoices = org.ethereumphone.andyclaw.ui.settings.ProviderSwitch.choices(
            org.ethereumphone.andyclaw.skills.tier.OsCapabilities.hasPrivilegedAccess
        ),
        switchProvider = { org.ethereumphone.andyclaw.ui.settings.ProviderSwitch.select(app, it) },
    )

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

    /** The autopilot run on screen, or null. Kept after it ends for the end card. */
    private val _autopilot = MutableStateFlow<org.ethereumphone.andyclaw.ui.autopilot.AutopilotUiState?>(null)
    val autopilot: StateFlow<org.ethereumphone.andyclaw.ui.autopilot.AutopilotUiState?> = _autopilot.asStateFlow()
    private val autopilotHaptics by lazy { org.ethereumphone.andyclaw.ui.autopilot.AutopilotHaptics(getApplication()) }

    fun dismissAutopilot() {
        _autopilot.value = null
    }

    /**
     * The live view's STOP. The binder calls go off the main thread; any approval the run is
     * waiting on is answered no, since the user just said stop.
     */
    fun stopAutopilot() {
        denyAllApprovals()
        viewModelScope.launch(Dispatchers.IO) {
            org.ethereumphone.andyclaw.autopilot.AgentDisplayCapabilities.requestStop()
        }
    }

    private val _replayShare = MutableStateFlow(org.ethereumphone.andyclaw.ui.autopilot.ReplayShareState.IDLE)
    val replayShare: StateFlow<org.ethereumphone.andyclaw.ui.autopilot.ReplayShareState> = _replayShare.asStateFlow()

    /** Renders the last run as a video and opens the share sheet. Only on the user's tap. */
    fun shareAutopilotReplay() {
        if (_replayShare.value == org.ethereumphone.andyclaw.ui.autopilot.ReplayShareState.WORKING) return
        val recording = org.ethereumphone.andyclaw.autopilot.replay.ReplayRecorder.latest()
        if (recording == null) {
            _replayShare.value = org.ethereumphone.andyclaw.ui.autopilot.ReplayShareState.FAILED
            return
        }
        _replayShare.value = org.ethereumphone.andyclaw.ui.autopilot.ReplayShareState.WORKING
        viewModelScope.launch {
            val file = try {
                withContext(Dispatchers.Default) {
                    org.ethereumphone.andyclaw.autopilot.replay.ReplayVideoExporter.export(getApplication(), recording)
                }
            } catch (e: Exception) {
                Log.w("ChatViewModel", "replay export failed", e)
                _replayShare.value = org.ethereumphone.andyclaw.ui.autopilot.ReplayShareState.FAILED
                return@launch
            }
            _replayShare.value = org.ethereumphone.andyclaw.ui.autopilot.ReplayShareState.IDLE
            val context = getApplication<Application>()
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.replays", file)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("video/mp4")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(
                android.content.Intent.createChooser(send, "Share autopilot replay")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }

    private var agentDisplayJob: Job? = null
    private val _isCompacting = MutableStateFlow(false)
    val isCompacting: StateFlow<Boolean> = _isCompacting.asStateFlow()

    private var turnsSinceLastCompaction = 0

    /** A summary was written since the last turn ran: the next turn re-injects memory. */
    private var compactedSinceLastTurn = false

    private var currentJob: Job? = null

    /** A manual /compact, so the CANCEL shown while it runs can stop it. */
    private var compactJob: Job? = null

    /** Follows the open session's row; see [watchSession]. */
    private var sessionWatch: Job? = null

    /**
     * A turn that throws before the loop's own error path (the session store, compaction,
     * routing) must neither crash the app nor leave the chat busy for good: [sendMessage] marks
     * it busy before any of that runs.
     */
    private val turnFailed = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        Log.e("ChatViewModel", "Turn failed: ${e.javaClass.simpleName}: ${e.message}", e)
        _error.value = e.message ?: "An error occurred"
        _isStreaming.value = false
        _currentToolExecution.value = null
    }

    /** Saving a message is best-effort next to the turn: a failed write is logged, not fatal. */
    private val persistFailed = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        Log.e("ChatViewModel", "Could not save to the session: ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /**
     * Approvals waiting for the user, oldest first; the dialog shows the head. One slot used to
     * be shared by every caller, so when two sub-agents asked at once the first was overwritten
     * and waited forever, and the answer went to whichever asked last.
     */
    private class PendingAsk(
        val request: ApprovalRequest,
        val cont: kotlinx.coroutines.CancellableContinuation<Boolean>,
    )
    private val approvalQueue = ArrayDeque<PendingAsk>()
    private val pendingExplorerUrls = mutableListOf<String>()

    data class ApprovalRequest(
        val description: String,
        val toolName: String? = null,
        val slug: String? = null,
        val threatAssessment: ThreatAssessment? = null,
        /** Which request an answer is for, so a second tap cannot answer the next one unseen. */
        val id: String = java.util.UUID.randomUUID().toString(),
    )

    fun loadSession(sessionId: String) {
        // The screen loads its session again whenever it comes back into view; only another
        // session starts the extractor's count over.
        if (_sessionId.value != sessionId) backgroundExtractor = null
        watchSession(sessionId)
        viewModelScope.launch {
            _sessionId.value = sessionId
            compactedSinceLastTurn = false
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
        sessionWatch?.cancel()
        sessionWatch = null
        backgroundExtractor = null
        _sessionId.value = null
        compactedSinceLastTurn = false
        _messages.value = emptyList()
        _contextWindow.value = ContextWindowState()
    }

    /**
     * Follows [id]'s row. Deleted from the session list (or by a restore) while it is open and
     * idle, the chat starts over: it used to keep showing the conversation, and the next message
     * was written to a session that no longer existed.
     */
    private fun watchSession(id: String) {
        sessionWatch?.cancel()
        sessionWatch = viewModelScope.launch {
            sessionManager.observeSession(id).collect { session ->
                if (session == null && _sessionId.value == id && currentJob?.isActive != true) newSession()
            }
        }
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
        compactJob = viewModelScope.launch {
            _isCompacting.value = true
            val startMs = System.currentTimeMillis()
            try {
                // Everything in the chat: this is not a turn, there is no pending user message
                // to leave out (the old dropLast(1) dropped the latest reply from a button tap).
                val history = buildLlmHistory(_messages.value)
                Log.i("ChatViewModel", "compactNow: conversationHistory=${history.size} messages")
                if (history.size < 3) {
                    Log.w("ChatViewModel", "compactNow: history too short (${history.size}), need at least 3 messages")
                    return@launch
                }
                // Manual compaction: force keepRecent=2, use user's compaction config for other settings
                val keepRecent = 2.coerceAtMost(history.size - 1)
                val compactionCfg = app.securePrefs.compactionConfig.value.copy(enabled = true)
                Log.d("ChatViewModel", "compactNow: keepRecent=$keepRecent, historySize=${history.size}, config=$compactionCfg")
                val compactionModelId = app.getCompactionModelId()
                val compactionClient = app.getCompactionLlmClient()
                Log.i("ChatViewModel", "compactNow: using model=$compactionModelId, client=${compactionClient.javaClass.simpleName}, " +
                    "useSameModel=${app.securePrefs.compactionUseSameModel.value}")
                val compactor = ContextCompactor(compactionClient, compactionCfg)
                val result = withContext(Dispatchers.IO) {
                    compactor.compact(history, compactionModelId, keepRecentOverride = keepRecent)
                }
                val elapsedMs = System.currentTimeMillis() - startMs
                if (result.wasCompacted && result.summaryText.isNotBlank()) {
                    // The summary goes after the messages it kept; record how many, or the next
                    // turn (and every reload) starts at the summary and loses them.
                    val kept = history.size - result.removedMessageCount
                    addContextSummary(sid, result.summaryText, kept)
                    turnsSinceLastCompaction = 0
                    Log.i("ChatViewModel", "compactNow DONE: summarized ${result.removedMessageCount} messages, " +
                        "summaryLength=${result.summaryText.length}, totalMs=${elapsedMs}")
                } else {
                    Log.i("ChatViewModel", "compactNow: nothing to compact (wasCompacted=${result.wasCompacted}, " +
                        "summaryBlank=${result.summaryText.isBlank()}), totalMs=${elapsedMs}")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val elapsedMs = System.currentTimeMillis() - startMs
                Log.e("ChatViewModel", "compactNow FAILED after ${elapsedMs}ms: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                _isCompacting.value = false
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _isStreaming.value || _isCompacting.value || currentJob?.isActive == true) return

        // ── Slash command interception ──────────────────────────────────
        val cmdResult = slashExecutor.execute(text)
        if (cmdResult != null) {
            handleSlashResult(text, cmdResult)
            return
        }

        ledController.onUserMessage()
        // A finished run's card belongs to the turn it was in, not the next one.
        if (_autopilot.value?.finished == true) _autopilot.value = null
        _replayShare.value = org.ethereumphone.andyclaw.ui.autopilot.ReplayShareState.IDLE

        // Busy, with the message on screen, before anything waits: creating the session and
        // loading the model registry can take seconds, and a second send in that window started
        // a second turn in the same chat that CANCEL could not reach. No balance check up front
        // either: the gateway's own refusal (403 "Insufficient balance", onError below) shows the
        // top-up prompt, and the check here refused users the gateway would still serve.
        _isStreaming.value = true
        _streamingText.value = ""
        _error.value = null
        val userMsg = ChatUiMessage(
            id = java.util.UUID.randomUUID().toString(),
            role = "user",
            content = text,
        )
        _messages.update { it + userMsg }

        currentJob = viewModelScope.launch(turnFailed) {
            // Deleted from the list (or by a restore) since it was opened: a new chat, rather
            // than writing into the conversation the user removed.
            _sessionId.value?.let { open ->
                if (sessionManager.getSession(open) == null) {
                    newSession()
                    _messages.value = listOf(userMsg)
                }
            }

            // Ensure session exists
            if (_sessionId.value == null) {
                val model = app.securePrefs.selectedModel.value
                val session = sessionManager.createSession(model = model)
                _sessionId.value = session.id
                watchSession(session.id)
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

            // Auto-title on first message — the first the user said, not a slash command's
            // echo or its output, which are shown but never saved.
            if (_messages.value.count { it.role == "user" && !it.transient } == 1) {
                val title = text.take(50).let { if (text.length > 50) "$it..." else it }
                sessionManager.updateSessionTitle(sid, title)
            }

            ledController.onPromptStart()

            // Build conversation history for agent loop — everything but this turn's message,
            // which AgentLoop adds itself.
            var conversationHistory = buildLlmHistory(_messages.value.filter { it.id != userMsg.id })

            // ── Context compaction check ──
            // Old messages stay in DB/UI; only the LLM context is compacted.
            val compactionCfg = app.securePrefs.compactionConfig.value
            val ctxState = _contextWindow.value
            turnsSinceLastCompaction++
            Log.d("ChatViewModel", "Auto-compact check: usedTokens=${ctxState.usedTokens}, maxTokens=${ctxState.maxTokens}, " +
                "pct=${(ctxState.percentage * 100).toInt()}%, turns=$turnsSinceLastCompaction, " +
                "enabled=${compactionCfg.enabled}, threshold=${compactionCfg.threshold}, interval=${compactionCfg.interval}")
            val shouldCompact = compactionCfg.shouldCompact(ctxState.usedTokens, ctxState.maxTokens, turnsSinceLastCompaction)
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
                    val compactor = ContextCompactor(compactionClient, compactionCfg)
                    val compactResult = compactor.compact(conversationHistory, compactionModelId)
                    val autoCompactMs = System.currentTimeMillis() - autoCompactStart
                    if (compactResult.wasCompacted) {
                        if (compactResult.summaryText.isNotBlank()) {
                            // The summary lands after this turn's user message (already shown
                            // and persisted), so that message counts among the kept ones: the
                            // tail the compactor kept, then the request itself.
                            val kept = conversationHistory.size - compactResult.removedMessageCount
                            addContextSummary(sid, compactResult.summaryText, kept + 1)
                        }
                        conversationHistory = compactResult.compactedHistory
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
            val activeProvider = app.securePrefs.selectedProvider.value
            // For CUSTOM, and OPEN_ROUTER ids that are not ours, the raw id goes out rather than
            // the MINIMAX_M3 fallback (ModelIdOverride); the launcher's turns do the same.
            val customModelIdOverride: String? = org.ethereumphone.andyclaw.llm.ModelIdOverride.of(activeProvider, modelId)
            val model = AnthropicModels.fromModelId(modelId) ?: AnthropicModels.MINIMAX_M3
            val currentTier = org.ethereumphone.andyclaw.skills.tier.OsCapabilities.currentTier()
            val currentEnabledSkillIds = if (app.securePrefs.yoloMode.value) {
                app.nativeSkillRegistry.getAll().map { it.id }.toSet()
            } else {
                app.securePrefs.enabledSkills.value
            }
            val turnClient = app.getLlmClient()
            // Start the likely app while the model plans; costs nothing if the turn needs no app.
            // Not for a confidential or on-device model, whose request stays where the user chose.
            val turnRoute = app.jevTurnRouter?.prewarm(text, turnClient)
            val agentLoop = AgentLoop(
                client = turnClient,
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
                compactionConfig = app.securePrefs.compactionConfig.value,
                memoryReranker = if (app.securePrefs.getString("memory.aiReranking") == "true" && app.getMemoryAiLlmClient() !is LocalLlmClient) {
                    MemoryReranker(app.getMemoryAiLlmClient(), app.getMemoryAiModelId())
                } else null,
                customModelIdOverride = customModelIdOverride,
                // The in-app chat is the user typing.
                provenance = org.ethereumphone.andyclaw.ExecutionEngine.Provenance.USER,
                enforceProvenance = app.securePrefs.provenanceEnforcementEnabled.value,
                flowRecorder = app.flowRecorder,
                flowRepository = app.flowRepositoryOrNull,
                // `sid` rather than the flow: the session was created above, and reading
                // it back through a nullable would make a recorded chat depend on ordering.
                ledger = app.agentLedger(sid),
                toolPrefetch = app.jevToolPrefetch,
                routedApp = { turnRoute?.app },
            )

            // Initialize background memory extractor for this run (opt-in)
            val smartExtractionEnabled = app.securePrefs.getString("memory.smartExtraction") == "true"
            val memoryAiClient = app.getMemoryAiLlmClient()
            val memoryAiModelId = app.getMemoryAiModelId()
            backgroundExtractor = if (smartExtractionEnabled && memoryAiClient !is LocalLlmClient) {
                val key = memoryAiClient to memoryAiModelId
                backgroundExtractor?.takeIf { backgroundExtractorFor == key }
                    ?: BackgroundMemoryExtractor(memoryAiClient, memoryManager, memoryAiModelId)
                        .also { backgroundExtractorFor = key }
            } else null

            val justCompacted = compactedSinceLastTurn
            compactedSinceLastTurn = false
            // Tokens arrive on the stream's IO thread and can still trickle in after Cancel;
            // a cancelled turn's text must not land in the next turn's bubble.
            val turnJob = coroutineContext[Job]
            agentLoop.run(text, conversationHistory, object : AgentLoop.Callbacks {
                override fun onToken(text: String) {
                    if (turnJob?.isActive == false) return
                    _streamingText.update { it + text }
                }

                override fun onStreamRetry(discardedChars: Int) {
                    if (turnJob?.isActive == false) return
                    // The retried call streams the reply again from its start.
                    _streamingText.update { it.dropLast(discardedChars) }
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
                    viewModelScope.launch(persistFailed) {
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
                    _messages.update { it + toolMsg }

                    // Agent display preview lifecycle
                    if (toolName == "agent_display_autopilot") {
                        // Nothing to stop: after a hand-over the model keeps driving the display,
                        // and the view keeps showing it until the turn ends.
                    } else if (toolName == "agent_display_create" && result !is SkillResult.Error) {
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
                    _messages.update { it + securityMsg }
                }

                override fun onAgentStep(event: org.ethereumphone.andyclaw.autopilot.AutopilotEvent) {
                    onAutopilotEvent(event)
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

                    // Exactly what will run: the call's own parameters, then why it needs a yes.
                    val details = org.ethereumphone.andyclaw.safety.ApprovalSummaries.asText(
                        org.ethereumphone.andyclaw.safety.ApprovalSummaries.of(toolName ?: "this tool", toolInput)
                    )
                    val request = ApprovalRequest(
                        description = "$details\n\n$description",
                        toolName = toolName,
                        slug = slug,
                        threatAssessment = threatAssessment,
                    )
                    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                        val ask = PendingAsk(request, cont)
                        synchronized(approvalQueue) {
                            approvalQueue.addLast(ask)
                            publishApprovalHeadLocked()
                        }
                        cont.invokeOnCancellation {
                            val wasOpen = synchronized(approvalQueue) {
                                approvalQueue.remove(ask).also { publishApprovalHeadLocked() }
                            }
                            if (wasOpen) discardPendingInstall(request)
                        }
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
                    endAutopilotForTurn()
                    ledController.onPromptComplete(fullText)

                    // Update context window usage
                    if (tokenUsage != null) {
                        updateContextWindow(tokenUsage, modelId)
                        // Persist token usage + context window state to session DB
                        viewModelScope.launch(persistFailed) {
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

                    // Background memory extraction (ported from Claude Code), over the chat
                    // including this exchange: the history above ends before it.
                    backgroundExtractor?.extractIfNeeded(
                        conversationHistory + Message.user(text) +
                            if (fullText.isNotBlank()) listOf(Message.assistant(listOf(ContentBlock.TextBlock(fullText)))) else emptyList(),
                        viewModelScope,
                    )

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
                    endAutopilotForTurn()
                    ledController.onPromptError()
                }
            }, justCompacted = justCompacted)
        }
    }

    /**
     * The user's answer to [requestId] — the request the dialog showed. An answer for one that is
     * gone (answered already, or dropped with its turn) does nothing: taking the head of the
     * queue instead let a double tap approve the next request without its dialog ever showing.
     */
    fun respondToApproval(requestId: String, approved: Boolean) {
        val ask = synchronized(approvalQueue) {
            approvalQueue.firstOrNull { it.request.id == requestId }?.also {
                approvalQueue.remove(it)
                publishApprovalHeadLocked()
            }
        } ?: return
        val request = ask.request
        if (!approved && request.toolName == "clawhub_install" && request.slug != null) {
            viewModelScope.launch {
                try {
                    app.clawHubManager.cancelPendingInstall(request.slug)
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "Cleanup after denial failed: ${e.message}")
                }
            }
        }
        // A request the turn already dropped (cancelled) must not be revived by a late tap.
        if (ask.cont.isActive) {
            @Suppress("DEPRECATION")
            ask.cont.resume(approved, null)
        }
    }

    /** Every open approval answered no: the user stopped the turn, so nothing waiting may run. */
    private fun denyAllApprovals() {
        val all = synchronized(approvalQueue) {
            approvalQueue.toList().also {
                approvalQueue.clear()
                publishApprovalHeadLocked()
            }
        }
        for (ask in all) {
            discardPendingInstall(ask.request)
            if (ask.cont.isActive) {
                @Suppress("DEPRECATION")
                ask.cont.resume(false, null)
            }
        }
    }

    /**
     * A skill an install dialog downloaded and assessed, which nobody will now confirm: dropped,
     * as a Deny drops it. Left pending, a later clawhub_install confirmed it with no look at the
     * assessment. Off the scope of this screen, which may be going away.
     */
    private fun discardPendingInstall(request: ApprovalRequest) {
        val slug = request.slug ?: return
        if (request.toolName != "clawhub_install") return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                app.clawHubManager.cancelPendingInstall(slug)
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Cleanup of a pending install failed: ${e.message}")
            }
        }
    }

    private fun publishApprovalHeadLocked() {
        _approvalRequest.value = approvalQueue.firstOrNull()?.request
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
        denyAllApprovals()
        currentJob?.cancel()
        // A cancelled turn calls neither onComplete nor onError: the spinner and the emoticon stay.
        ledController.onPromptCancelled()
        compactJob?.cancel()
        _isStreaming.value = false
        _streamingText.value = ""
        _currentToolExecution.value = null
        pendingExplorerUrls.clear()
        endAutopilotForTurn(stopped = true)
    }

    /**
     * The turn is over: the live view shows how the run ended — a hand-over the model finished,
     * a cancel — instead of "running" forever, and keeps its last frame for the end card.
     */
    private fun endAutopilotForTurn(stopped: Boolean = false) {
        val ap = _autopilot.getAndUpdate { it?.endOfTurn(stopped) }
        stopDisplayCapture(clearFrame = ap == null)
    }

    fun clearError() {
        _error.value = null
    }

    fun clearInsufficientBalance() {
        _insufficientBalance.value = false
    }

    private fun onAutopilotEvent(event: org.ethereumphone.andyclaw.autopilot.AutopilotEvent) {
        // update{}: events arrive from the run's threads; a read-then-write lost one when two raced.
        _autopilot.update { current ->
            val base = if (current == null || current.runId != event.runId) {
                org.ethereumphone.andyclaw.ui.autopilot.AutopilotUiState(runId = event.runId)
            } else current
            base.reduce(event)
        }
        autopilotHaptics.on(event)
        when (event.kind) {
            org.ethereumphone.andyclaw.autopilot.AutopilotEvent.Kind.STARTED ->
                startDisplayCapture(intervalMs = autopilotFrameIntervalMs())
            // One frame of how it ended, now, before anything parks the display.
            org.ethereumphone.andyclaw.autopilot.AutopilotEvent.Kind.DONE,
            org.ethereumphone.andyclaw.autopilot.AutopilotEvent.Kind.FAILED -> captureOneFrame()
            else -> Unit
        }
    }

    private fun startDisplayCapture(intervalMs: Long = 1000) {
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

            val scaled = org.ethereumphone.andyclaw.autopilot.AgentDisplayCapabilities.hasV2
            while (isActive) {
                // Before the run has created the display, and while it is parked, there is
                // nothing to show — and nothing to log five times a second.
                if (svc.displayId >= 0) {
                    try {
                        // The OS encodes on demand now; a smaller frame is cheaper on both sides.
                        val frame = if (scaled) svc.captureFrameScaled(480, 70) else svc.captureFrame()
                        if (frame != null) {
                            val bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.size)
                            if (bitmap != null) {
                                _agentDisplayBitmap.value = bitmap
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("ChatViewModel", "Display capture skipped: ${e.message}")
                    }
                }
                delay(intervalMs)
            }
        }
    }

    /**
     * With the OS's live mirror on screen the poll only keeps a fallback frame and the end card's
     * picture, so once a second is plenty; an older OS has only the poll.
     */
    private fun autopilotFrameIntervalMs(): Long =
        if (org.ethereumphone.andyclaw.autopilot.AgentDisplayCapabilities.hasV2) 1_000L else AUTOPILOT_FRAME_INTERVAL_MS

    private fun captureOneFrame() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val svc = org.ethereumphone.andyclaw.skills.builtin.AgentDisplayBinder.serviceOrNull() ?: return@launch
                if (svc.displayId < 0) return@launch
                val frame = svc.captureFrameScaled(480, 70) ?: return@launch
                BitmapFactory.decodeByteArray(frame, 0, frame.size)?.let { _agentDisplayBitmap.value = it }
            } catch (_: Exception) {
            }
        }
    }

    private fun stopDisplayCapture(clearFrame: Boolean = true) {
        agentDisplayJob?.cancel()
        agentDisplayJob = null
        if (clearFrame) _agentDisplayBitmap.value = null
    }

    private fun flushStreamingText(sessionId: String) {
        // Taken and cleared in one step: a token landing between a read and a separate
        // clear (the stream is on another thread) was lost from both bubbles.
        val currentText = _streamingText.getAndUpdate { "" }
        if (currentText.isNotBlank()) {
            val urls = pendingExplorerUrls.toList()
            pendingExplorerUrls.clear()
            val assistantMsg = ChatUiMessage(
                id = java.util.UUID.randomUUID().toString(),
                role = "assistant",
                content = currentText,
                explorerUrl = urls.lastOrNull(),
            )
            _messages.update { it + assistantMsg }
            viewModelScope.launch(persistFailed) {
                sessionManager.addMessage(sessionId, MessageRole.ASSISTANT, currentText)
            }
        }
    }

    /** Shows and persists a context summary that keeps the [kept] messages before it. */
    private suspend fun addContextSummary(sessionId: String, summary: String, kept: Int) {
        sessionManager.addMessage(
            sessionId, MessageRole.CONTEXT_SUMMARY, summary,
            toolCallId = "$SUMMARY_KEPT_PREFIX$kept",
        )
        _messages.update {
            it + ChatUiMessage(
                id = java.util.UUID.randomUUID().toString(),
                role = "context_summary",
                content = summary,
                keptBefore = kept,
            )
        }
        compactedSinceLastTurn = true
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

    /**
     * Stores a structured summary of the conversation turn in long-term memory.
     *
     * Improvements over the original implementation:
     * - Includes both user and assistant text (not just user)
     * - Filters out trivial turns (short confirmations, greetings)
     * - Formats as a structured turn summary for better retrieval
     * - Scores importance based on content substance
     * - Auto-tags based on simple keyword detection
     */
    private fun autoStoreConversationTurn(userText: String, assistantText: String) {
        val autoStoreEnabled = app.securePrefs.getString("memory.autoStore") != "false"
        if (!autoStoreEnabled) return

        // Skip trivial user messages (confirmations, greetings, single words)
        val trimmedUser = userText.trim()
        if (trimmedUser.length < 30) return
        if (TRIVIAL_PATTERN.matches(trimmedUser)) return

        // Skip if assistant response is empty (error/cancelled)
        val trimmedAssistant = assistantText.trim()
        if (trimmedAssistant.isEmpty()) return

        // Build structured turn summary
        val userSummary = trimmedUser.take(400)
        val assistantSummary = trimmedAssistant.take(400)
        val content = "User: $userSummary\nAssistant: $assistantSummary"

        // Auto-detect tags from content
        val tags = mutableListOf("conversation")
        val lowerContent = content.lowercase()
        if (PREFERENCE_KEYWORDS.any { it in lowerContent }) tags.add("preference")
        if (ERROR_KEYWORDS.any { it in lowerContent }) tags.add("troubleshooting")
        if (DECISION_KEYWORDS.any { it in lowerContent }) tags.add("decision")

        // Score importance based on substance
        val importance = when {
            trimmedUser.length > 200 && trimmedAssistant.length > 200 -> 0.5f
            trimmedUser.contains('?') -> 0.4f  // Questions are valuable
            else -> 0.3f
        }

        viewModelScope.launch {
            try {
                memoryManager.store(
                    content = content,
                    source = MemorySource.CONVERSATION,
                    tags = tags,
                    importance = importance,
                )
            } catch (_: Exception) {
                // Memory storage is best-effort; don't disrupt the UI
            }
        }
    }

    companion object {
        /** `toolCallId` of a CONTEXT_SUMMARY row: `kept:N` (see [ChatUiMessage.keptBefore]). */
        internal const val SUMMARY_KEPT_PREFIX = "kept:"

        /** Rows written before the count existed have none: they keep nothing, as they always did. */
        internal fun parseKeptBefore(toolCallId: String?): Int {
            if (toolCallId == null || !toolCallId.startsWith(SUMMARY_KEPT_PREFIX)) return 0
            return toolCallId.removePrefix(SUMMARY_KEPT_PREFIX).toIntOrNull()?.coerceAtLeast(0) ?: 0
        }

        private fun isConversational(msg: ChatUiMessage): Boolean =
            !msg.transient && (msg.role == "user" || msg.role == "assistant")

        private fun toLlmMessage(msg: ChatUiMessage): Message? = when {
            !isConversational(msg) -> null // tool results are handled within the agent loop
            msg.role == "user" -> Message.user(msg.content)
            else -> Message.assistant(listOf(ContentBlock.TextBlock(msg.content)))
        }

        /**
         * The model's view of a chat: from the latest context summary on, older messages stay
         * in the UI only. A summary is written *after* the messages the compactor kept (and,
         * for auto-compaction, after the user message that triggered it), so those are taken
         * from just before it — [ChatUiMessage.keptBefore] of them, skipping anything that is
         * not conversation. Starting at the summary alone dropped exactly the recent context
         * compaction exists to keep, and the request that was being answered.
         */
        internal fun buildLlmHistory(msgs: List<ChatUiMessage>): List<Message> {
            val lastSummaryIndex = msgs.indexOfLast { it.role == "context_summary" }
            if (lastSummaryIndex < 0) return msgs.mapNotNull(::toLlmMessage)

            val summary = msgs[lastSummaryIndex]
            val kept = ArrayDeque<ChatUiMessage>()
            var i = lastSummaryIndex - 1
            while (i >= 0 && kept.size < summary.keptBefore) {
                if (isConversational(msgs[i])) kept.addFirst(msgs[i])
                i--
            }
            val summaryMessage = Message.user(
                "<context_summary>\n" +
                    "This is a compacted summary of older messages in this conversation. " +
                    "If you need more details about something mentioned here, " +
                    "use the search_memory skill to retrieve relevant context from long-term memory.\n\n" +
                    summary.content + "\n" +
                    "</context_summary>"
            )
            return listOf(summaryMessage) +
                kept.mapNotNull(::toLlmMessage) +
                msgs.subList(lastSummaryIndex + 1, msgs.size).mapNotNull(::toLlmMessage)
        }

        /** Without the OS mirror, the live view falls back to frames at this rate. */
        private const val AUTOPILOT_FRAME_INTERVAL_MS = 200L
        /** Matches trivial user messages that aren't worth remembering. */
        private val TRIVIAL_PATTERN = Regex(
            "^(yes|no|ok|okay|sure|thanks|thank you|yep|nope|got it|do it|go ahead|looks good|perfect|great|nice|cool|lgtm|\\+1|👍|k|y|n)\\s*[.!?]*$",
            RegexOption.IGNORE_CASE,
        )
        private val PREFERENCE_KEYWORDS = listOf("prefer", "always use", "never use", "i like", "i don't like", "i want", "don't want")
        private val ERROR_KEYWORDS = listOf("error", "bug", "fix", "crash", "fail", "broken", "wrong")
        private val DECISION_KEYWORDS = listOf("decided", "let's go with", "we'll use", "the plan is", "going to", "switch to")
    }

    // ── Slash command handling ────────────────────────────────────────

    private fun handleSlashResult(rawInput: String, result: SlashCommandResult) {
        // Show user's command as a message
        val userMsg = ChatUiMessage(
            id = java.util.UUID.randomUUID().toString(),
            role = "user",
            content = rawInput,
            // Never persisted, so never sent: "/compact" is not something the user said to the model.
            transient = true,
        )
        _messages.update { it + userMsg }

        // Show system feedback (skip for /compact — result shown as context_summary card)
        if (result.message.isNotBlank() && result.message != "compact") {
            val systemMsg = ChatUiMessage(
                id = java.util.UUID.randomUUID().toString(),
                role = "system",
                content = result.message,
            )
            _messages.update { it + systemMsg }
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
        _messages.update { it + systemMsg }
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
            keptBefore = if (role == MessageRole.CONTEXT_SUMMARY) parseKeptBefore(toolCallId) else 0,
        )
    }
}
