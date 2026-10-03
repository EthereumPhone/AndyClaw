package org.ethereumphone.andyclaw.telegram

import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.agent.AgentLoop
import org.ethereumphone.andyclaw.agent.AskUserRequest
import org.ethereumphone.andyclaw.extensions.clawhub.DownloadAssessResult
import org.ethereumphone.andyclaw.extensions.clawhub.ThreatAssessment
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.LlmClient
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.llm.MessagesRequest
import org.ethereumphone.andyclaw.memory.model.MemorySource
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Headless agent runner for Telegram messages.
 *
 * Modeled after [org.ethereumphone.andyclaw.agent.HeartbeatAgentRunner]
 * but maintains per-chat conversation history so multi-turn context
 * is preserved within a service lifecycle.
 *
 * Every inbound Telegram message is [Provenance.UNTRUSTED]: the bot answers whoever
 * messages it. The owner's chat is the one verified at setup ([TelegramOwner]); every
 * other chat is a stranger's.
 *
 * This runner does have a real approval affordance — inline Approve/Decline buttons.
 * It is only a *genuine* one when the buttons reach the owner: sending them to an
 * arbitrary sender would let that sender approve their own irreversible request,
 * which is the hole this whole gate exists to close. So approval prompts are offered
 * in the owner's chat and refused everywhere else.
 *
 * [inlineApprovals] is false where button presses cannot come back: on ethOS the OS polls
 * Telegram for messages only, so the owner's approvals are queued for the phone instead.
 */
class TelegramAgentRunner(
    private val app: NodeApp,
    private val botClient: TelegramBotClient,
    private val inlineApprovals: Boolean = true,
) {

    companion object {
        private const val TAG = "TelegramAgentRunner"
        private const val MAX_HISTORY_PER_CHAT = 40 // 20 user + 20 assistant
        private const val APPROVAL_TIMEOUT_MS = 3L * 60 * 1000 // 3 minutes
        private const val ASK_USER_TIMEOUT_MS = 3L * 60 * 1000 // 3 minutes
    }

    // Runs for different chats go on concurrently, and /clear or a settings change clears from
    // yet another thread. Each chat's own list is only touched under that chat's lock.
    private val chatHistories = ConcurrentHashMap<Long, MutableList<Message>>()
    private val memoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /**
     * Called by [TelegramBotService] when a callback query button press arrives.
     * Resolves the suspended [onApprovalNeeded] coroutine.
     */
    fun resolveApproval(requestId: String, approved: Boolean) {
        pendingApprovals.remove(requestId)?.complete(approved)
    }

    suspend fun run(chatId: Long, userMessage: String): String {
        Log.i(TAG, "=== TELEGRAM RUN (chat=$chatId) ===")
        Log.i(TAG, "Message: ${userMessage.take(500)}")

        val client = app.getLlmClient()
        val registry = app.nativeSkillRegistry
        val tier = OsCapabilities.currentTier()
        val aiName = app.userStoryManager.getAiName()
        val userStory = app.userStoryManager.read()

        val modelId = app.securePrefs.selectedModel.value
        val model = AnthropicModels.fromModelId(modelId) ?: AnthropicModels.MINIMAX_M3

        val enabledSkillIds = if (app.securePrefs.yoloMode.value) {
            registry.getAll().map { it.id }.toSet()
        } else {
            app.securePrefs.enabledSkills.value
        }
        // The chat verified at setup, never "the first chat that wrote" — see TelegramOwner.
        val isOwnerChat = TelegramOwner.isOwner(app.securePrefs.telegramOwnerChatId.value, chatId)
        val agentLoop = AgentLoop(
            client = client,
            skillRegistry = registry,
            tier = tier,
            enabledSkillIds = enabledSkillIds,
            model = model,
            aiName = aiName,
            // The reply goes back to this chat. Anyone but the owner reading it must not get the
            // owner's story or what the agent remembers about them.
            userStory = if (isOwnerChat) userStory else null,
            // Nor the owner's standing instructions, which are theirs and may say anything.
            soulContent = if (isOwnerChat) app.soulManager.read() else null,
            memoryManager = if (isOwnerChat) app.memoryManager else null,
            safetyLayer = app.createSafetyLayer(),
            smartRouter = if (app.securePrefs.smartRoutingEnabled.value && !app.securePrefs.toolSearchEnabled.value) app.smartRouter else null,
            toolSearchService = app.createToolSearchService(tier, enabledSkillIds),
            budgetConfig = app.createBudgetConfig(),
            provenance = Provenance.UNTRUSTED,
            triggerConversationId = chatId.toString(),
            enforceProvenance = app.securePrefs.provenanceEnforcementEnabled.value,
            flowRecorder = app.flowRecorder,
            flowRepository = app.flowRepositoryOrNull,
            ledger = app.agentLedger("telegram:$chatId"),
            replyAudience = if (isOwnerChat) {
                org.ethereumphone.andyclaw.safety.ReplyAudience.OWNER
            } else {
                org.ethereumphone.andyclaw.safety.ReplyAudience.STRANGER
            },
            ledgerIntent = org.ethereumphone.andyclaw.agent.BackgroundIntent.telegram(isOwnerChat),
        )

        val ledController = app.ledController
        val history = chatHistories.getOrPut(chatId) { mutableListOf() }

        val collectedText = StringBuilder()
        val completion = CompletableDeferred<String>()
        // A Telegram run is answered in Telegram: the LEDs only, never the terminal screen.
        ledController.onPromptStart(terminal = false)
        var usedTools = false

        val callbacks = object : AgentLoop.Callbacks {
            override fun onToken(text: String) {
                collectedText.append(text)
            }

            override fun onToolExecution(toolName: String) {
                usedTools = true
                Log.i(TAG, "Tool call (chat=$chatId): $toolName")
            }

            override fun onToolResult(toolName: String, result: SkillResult, input: kotlinx.serialization.json.JsonObject?) {
                val resultStr = when (result) {
                    is SkillResult.Success -> "Success: ${result.data.take(300)}"
                    is SkillResult.ImageSuccess -> "ImageSuccess: ${result.text.take(300)}"
                    is SkillResult.Error -> "Error: ${result.message}"
                    is SkillResult.RequiresApproval -> "RequiresApproval: ${result.description}"
                }
                Log.i(TAG, "Tool result (chat=$chatId, $toolName): $resultStr")
            }

            override fun onSecurityBlock(toolName: String, reason: String) {
                Log.w(TAG, "SECURITY BLOCK (chat=$chatId, $toolName): $reason")
                // The owner sees why; anyone else just gets the agent's answer.
                if (!isOwnerChat) return
                memoryScope.launch {
                    botClient.sendMessage(
                        chatId,
                        "Security block on `$toolName`: $reason",
                    )
                }
            }

            override fun onAskUserDisplayed(request: AskUserRequest) {
                // Send questions as a Telegram message — user's reply comes as a new message
                val text = buildString {
                    appendLine("❓ Clarification needed:")
                    for ((i, q) in request.questions.withIndex()) {
                        if (request.questions.size > 1) append("${i + 1}. ")
                        append(q.question)
                        if (q.options.isNotEmpty()) {
                            append(" [${q.options.joinToString(" / ")}]")
                        }
                        appendLine()
                    }
                    append("Reply with your answer:")
                }
                Log.i(TAG, "ask_user (telegram chat=$chatId): ${request.questions.size} question(s)")
                memoryScope.launch { botClient.sendMessage(chatId, text) }
            }

            override suspend fun onApprovalNeeded(
                description: String,
                toolName: String?,
                toolInput: JsonObject?,
            ): Boolean {
                // Buttons sent to a stranger are not an approval, they are the
                // attacker signing their own request. Only the owner's chat gets to
                // decide; anything else is refused and queued for the user.
                if (!isOwnerChat) {
                    Log.w(TAG, "Refusing approval from non-owner chat $chatId: ${toolName ?: "?"} — $description")
                    queueForPhone(toolName, toolInput, description)
                    memoryScope.launch {
                        botClient.sendMessage(
                            chatId,
                            "That needs the phone owner's approval. I've queued it for them.",
                        )
                    }
                    return false
                }

                // Past the owner check the sender is the device owner, so their own
                // YOLO setting applies as it does anywhere else. A non-owner chat
                // never reaches this line, whatever YOLO says.
                if (app.securePrefs.yoloMode.value) return true

                // No way to hear the answer: the buttons would be pressed into the void, and the
                // run would wait three minutes holding the chat's lock before declining itself.
                if (!inlineApprovals) {
                    Log.i(TAG, "Queueing approval for the phone (chat=$chatId, tool=${toolName ?: "?"})")
                    val queued = queueForPhone(toolName, toolInput, description)
                    memoryScope.launch {
                        botClient.sendMessage(
                            chatId,
                            if (queued) {
                                "That needs your approval. I've put it on your phone — approve it there."
                            } else {
                                "That needs your approval, and I couldn't queue it on your phone. " +
                                    "Please do it from the phone directly."
                            },
                        )
                    }
                    return false
                }

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
                            Log.w(TAG, "Threat assessment failed for '$slug': ${e.message}")
                        }
                    }
                }

                val warningText = buildApprovalMessage(description, toolName, slug, threatAssessment)
                val requestId = UUID.randomUUID().toString()
                val deferred = CompletableDeferred<Boolean>()
                pendingApprovals[requestId] = deferred

                val sentMessageId = botClient.sendMessageWithInlineKeyboard(
                    chatId = chatId,
                    text = warningText,
                    buttons = listOf(
                        InlineButton("Approve", "approve::$requestId"),
                        InlineButton("Decline", "decline::$requestId"),
                    ),
                )

                val approved = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
                    deferred.await()
                }

                if (approved == null) {
                    pendingApprovals.remove(requestId)
                    Log.w(TAG, "Approval timed out (chat=$chatId, tool=$toolName)")
                    if (sentMessageId != null) {
                        botClient.editMessageText(
                            chatId, sentMessageId,
                            "Approval timed out — automatically declined.",
                        )
                    }
                }

                val result = approved ?: false

                if (!result && slug != null) {
                    try {
                        app.clawHubManager.cancelPendingInstall(slug)
                    } catch (e: Exception) {
                        Log.w(TAG, "Cleanup after denial/timeout failed: ${e.message}")
                    }
                }

                return result
            }

            /** Queues the refused call as a card on the phone. True when it was queued. */
            private fun queueForPhone(toolName: String?, toolInput: JsonObject?, description: String): Boolean =
                try {
                    val name = toolName ?: "unknown"
                    app.pendingApprovalStore.queue(
                        org.ethereumphone.andyclaw.safety.PendingApprovalStore.Request(
                            source = "telegram",
                            provenance = Provenance.UNTRUSTED.name,
                            toolName = name,
                            input = toolInput,
                            description = org.ethereumphone.andyclaw.safety.ApprovalSummaries.of(name, toolInput).title,
                            conversationId = chatId.toString(),
                            ledgerSessionId = "telegram:$chatId",
                            effect = org.ethereumphone.andyclaw.safety.ToolEffects.of(
                                name, registry.getTools(tier).firstOrNull { it.name == name },
                            ).name,
                            toolReason = description,
                        )
                    ) != null
                } catch (e: Exception) {
                    Log.w(TAG, "Could not queue pending approval: ${e.message}")
                    false
                }

            override suspend fun onPermissionsNeeded(permissions: List<String>): Boolean {
                val allGranted = permissions.all { perm ->
                    ContextCompat.checkSelfPermission(app, perm) == PackageManager.PERMISSION_GRANTED
                }
                if (allGranted) return true

                val requester = app.permissionRequester
                if (requester != null) {
                    return try {
                        requester.requestIfMissing(permissions).values.all { it }
                    } catch (e: Exception) {
                        Log.w(TAG, "Permission request failed: ${e.message}")
                        false
                    }
                }

                Log.w(TAG, "Permissions not available (background): $permissions")
                return false
            }

            override fun onComplete(fullText: String, tokenUsage: org.ethereumphone.andyclaw.agent.TokenUsageSnapshot?) {
                Log.i(TAG, "=== TELEGRAM RUN COMPLETE (chat=$chatId) ===")
                ledController.onPromptComplete(fullText, terminal = false)

                history.add(Message.user(userMessage))
                history.add(Message.assistant(listOf(ContentBlock.TextBlock(fullText))))
                trimHistory(chatId)

                // Only the owner's own words become memories: a stranger's would be fed back into
                // every later prompt as if the owner had said them.
                if (isOwnerChat) autoStoreConversationTurn(userMessage)

                completion.complete(fullText)
            }

            override fun onError(error: Throwable) {
                Log.e(TAG, "=== TELEGRAM RUN FAILED (chat=$chatId) ===", error)
                ledController.onPromptError(terminal = false)
                completion.complete("Sorry, an error occurred: ${error.message}")
            }
        }

        try {
            agentLoop.run(
                userMessage = userMessage,
                conversationHistory = history.toList(),
                callbacks = callbacks,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            ledController.onPromptCancelled(terminal = false)
            throw e
        }

        val executionText = completion.await()

        if (!usedTools) return executionText

        return summarizeForTelegram(client, model, aiName, userMessage, executionText)
    }

    private fun buildApprovalMessage(
        description: String,
        toolName: String?,
        slug: String?,
        assessment: ThreatAssessment?,
    ): String = buildString {
        if (assessment != null) {
            appendLine("*Security Warning*")
            appendLine()
            if (slug != null) appendLine("Skill: `$slug`")
            appendLine("Threat level: *${assessment.level.displayName}*")
            appendLine()
            appendLine(assessment.summary)

            if (assessment.indicators.isNotEmpty()) {
                appendLine()
                appendLine("_Detected issues:_")
                for (indicator in assessment.indicators) {
                    appendLine("  - [${indicator.severity}] ${indicator.category}: ${indicator.description}")
                }
            }

            appendLine()
            appendLine("Only approve if you trust the skill author.")
        } else {
            appendLine("*Approval Required*")
            appendLine()
            if (toolName != null) appendLine("Tool: `$toolName`")
            appendLine(description)
        }
    }

    /**
     * Makes a lightweight LLM call to distill the full agent execution output
     * into a concise, user-friendly Telegram message.
     */
    private suspend fun summarizeForTelegram(
        client: LlmClient,
        model: AnthropicModels,
        aiName: String?,
        userMessage: String,
        executionText: String,
    ): String {
        val name = aiName ?: "the assistant"
        val request = MessagesRequest(
            model = model.modelId,
            maxTokens = 1024,
            system = "You are $name replying to a user on Telegram. " +
                    "You just completed a task on the user's behalf. Below is the user's original " +
                    "request and the full execution log from carrying it out. " +
                    "Write a short, friendly reply telling the user what you did and the outcome. " +
                    "Do NOT include internal reasoning, tool names, or technical process details. " +
                    "Keep it conversational and concise.",
            messages = listOf(
                Message.user(
                    "My request: $userMessage\n\n---\nExecution log:\n${executionText.take(3000)}"
                ),
            ),
        )

        return try {
            val response = client.sendMessage(request)
            val summary = response.content
                .filterIsInstance<ContentBlock.TextBlock>()
                .joinToString("") { it.text }
                .trim()

            if (summary.isNotBlank()) {
                Log.i(TAG, "Summarized execution for Telegram (${summary.length} chars)")
                summary
            } else {
                Log.w(TAG, "Summary was blank, falling back to execution text")
                executionText
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to summarize for Telegram, falling back: ${e.message}")
            executionText
        }
    }

    fun clearHistory(chatId: Long) {
        chatHistories.remove(chatId)
    }

    fun clearAllHistory() {
        chatHistories.clear()
    }

    private fun trimHistory(chatId: Long) {
        val history = chatHistories[chatId] ?: return
        while (history.size > MAX_HISTORY_PER_CHAT) {
            history.removeFirst()
        }
    }

    private fun autoStoreConversationTurn(userText: String) {
        val autoStoreEnabled = app.securePrefs.getString("memory.autoStore") != "false"
        if (!autoStoreEnabled) return
        if (userText.length < 20) return

        memoryScope.launch {
            try {
                app.memoryManager.store(
                    content = userText.take(500),
                    source = MemorySource.CONVERSATION,
                    tags = listOf("conversation", "telegram"),
                    importance = 0.3f,
                )
            } catch (_: Exception) {
                // Best-effort
            }
        }
    }
}
