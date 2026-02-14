package org.ethereumphone.andyclaw.agent

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities

/**
 * An [AgentRunner] that bridges the heartbeat's text-in/text-out interface
 * to the full [AgentLoop] with tool_use capabilities.
 *
 * Runs headlessly: auto-approves all tools (dangerous ones like send_transaction
 * will fail gracefully via their own permission checks) and skips Android
 * runtime permission requests (no UI available in background).
 */
class HeartbeatAgentRunner(private val app: NodeApp) : AgentRunner {

    companion object {
        private const val TAG = "HeartbeatAgentRunner"
    }

    override suspend fun run(
        prompt: String,
        systemPrompt: String?,
        skillsPrompt: String?,
    ): AgentResponse {
        val client = app.anthropicClient
        val registry = app.nativeSkillRegistry
        val tier = OsCapabilities.currentTier()
        val aiName = app.userStoryManager.getAiName()
        val userStory = app.userStoryManager.read()

        val agentLoop = AgentLoop(
            client = client,
            skillRegistry = registry,
            tier = tier,
            aiName = aiName,
            userStory = userStory,
        )

        val collectedText = StringBuilder()
        val completion = CompletableDeferred<AgentResponse>()

        val callbacks = object : AgentLoop.Callbacks {
            override fun onToken(text: String) {
                collectedText.append(text)
            }

            override fun onToolExecution(toolName: String) {
                Log.d(TAG, "Executing tool: $toolName")
            }

            override fun onToolResult(toolName: String, result: SkillResult) {
                Log.d(TAG, "Tool result ($toolName): ${result::class.simpleName}")
            }

            override suspend fun onApprovalNeeded(description: String): Boolean {
                Log.d(TAG, "Auto-approving: $description")
                return true
            }

            override suspend fun onPermissionsNeeded(permissions: List<String>): Boolean {
                val requester = app.permissionRequester
                if (requester != null) {
                    return try {
                        val results = requester.requestIfMissing(permissions)
                        results.values.all { it }
                    } catch (e: Exception) {
                        Log.w(TAG, "Permission request failed: ${e.message}")
                        false
                    }
                }
                Log.w(TAG, "No permission requester available (background), denying: $permissions")
                return false
            }

            override fun onComplete(fullText: String) {
                completion.complete(AgentResponse(text = fullText))
            }

            override fun onError(error: Throwable) {
                Log.e(TAG, "AgentLoop error: ${error.message}", error)
                completion.complete(AgentResponse(text = error.message ?: "Unknown error", isError = true))
            }
        }

        agentLoop.run(
            userMessage = prompt,
            conversationHistory = emptyList(),
            callbacks = callbacks,
        )

        return completion.await()
    }
}
