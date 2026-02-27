package org.ethereumphone.andyclaw.telegram

import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.agent.AgentLoop
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.memory.model.MemorySource
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities

/**
 * Headless agent runner for Telegram messages.
 *
 * Modeled after [org.ethereumphone.andyclaw.agent.HeartbeatAgentRunner]
 * but maintains per-chat conversation history so multi-turn context
 * is preserved within a service lifecycle.
 */
class TelegramAgentRunner(private val app: NodeApp) {

    companion object {
        private const val TAG = "TelegramAgentRunner"
        private const val MAX_HISTORY_PER_CHAT = 40 // 20 user + 20 assistant
    }

    private val chatHistories = mutableMapOf<Long, MutableList<Message>>()
    private val memoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun run(chatId: Long, userMessage: String): String {
        Log.i(TAG, "=== TELEGRAM RUN (chat=$chatId) ===")
        Log.i(TAG, "Message: ${userMessage.take(500)}")

        val client = app.getLlmClient()
        val registry = app.nativeSkillRegistry
        val tier = OsCapabilities.currentTier()
        val aiName = app.userStoryManager.getAiName()
        val userStory = app.userStoryManager.read()

        val modelId = app.securePrefs.selectedModel.value
        val model = AnthropicModels.fromModelId(modelId) ?: AnthropicModels.MINIMAX_M25

        val agentLoop = AgentLoop(
            client = client,
            skillRegistry = registry,
            tier = tier,
            enabledSkillIds = if (app.securePrefs.yoloMode.value) {
                registry.getAll().map { it.id }.toSet()
            } else {
                app.securePrefs.enabledSkills.value
            },
            model = model,
            aiName = aiName,
            userStory = userStory,
            memoryManager = app.memoryManager,
        )

        val history = chatHistories.getOrPut(chatId) { mutableListOf() }

        val collectedText = StringBuilder()
        val completion = CompletableDeferred<String>()

        val callbacks = object : AgentLoop.Callbacks {
            override fun onToken(text: String) {
                collectedText.append(text)
            }

            override fun onToolExecution(toolName: String) {
                Log.i(TAG, "Tool call (chat=$chatId): $toolName")
            }

            override fun onToolResult(toolName: String, result: SkillResult) {
                val resultStr = when (result) {
                    is SkillResult.Success -> "Success: ${result.data.take(300)}"
                    is SkillResult.Error -> "Error: ${result.message}"
                    is SkillResult.RequiresApproval -> "RequiresApproval: ${result.description}"
                }
                Log.i(TAG, "Tool result (chat=$chatId, $toolName): $resultStr")
            }

            override suspend fun onApprovalNeeded(description: String): Boolean {
                Log.i(TAG, "Auto-approving (chat=$chatId): $description")
                return true
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

            override fun onComplete(fullText: String) {
                Log.i(TAG, "=== TELEGRAM RUN COMPLETE (chat=$chatId) ===")

                history.add(Message.user(userMessage))
                history.add(Message.assistant(listOf(ContentBlock.TextBlock(fullText))))
                trimHistory(chatId)

                autoStoreConversationTurn(userMessage)

                completion.complete(fullText)
            }

            override fun onError(error: Throwable) {
                Log.e(TAG, "=== TELEGRAM RUN FAILED (chat=$chatId) ===", error)
                completion.complete("Sorry, an error occurred: ${error.message}")
            }
        }

        agentLoop.run(
            userMessage = userMessage,
            conversationHistory = history.toList(),
            callbacks = callbacks,
        )

        return completion.await()
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
