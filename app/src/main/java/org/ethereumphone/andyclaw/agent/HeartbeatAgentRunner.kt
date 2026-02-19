package org.ethereumphone.andyclaw.agent

import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.heartbeat.HeartbeatLogEntry
import org.ethereumphone.andyclaw.heartbeat.HeartbeatLogStore
import org.ethereumphone.andyclaw.heartbeat.HeartbeatToolCall
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
class HeartbeatAgentRunner(
    private val app: NodeApp,
    private val logStore: HeartbeatLogStore,
) : AgentRunner {

    companion object {
        private const val TAG = "HeartbeatAgentRunner"
    }

    override suspend fun run(
        prompt: String,
        systemPrompt: String?,
        skillsPrompt: String?,
    ): AgentResponse {
        Log.i(TAG, "=== HEARTBEAT RUN STARTING ===")
        Log.i(TAG, "Prompt: ${prompt.take(500)}")

        val client = app.anthropicClient
        val registry = app.nativeSkillRegistry
        val tier = OsCapabilities.currentTier()
        val aiName = app.userStoryManager.getAiName()
        val userStory = app.userStoryManager.read()

        Log.i(TAG, "AI name: $aiName, tier: $tier, userStory present: ${userStory != null}")

        val agentLoop = AgentLoop(
            client = client,
            skillRegistry = registry,
            tier = tier,
            enabledSkillIds = if (app.securePrefs.yoloMode.value) {
                registry.getAll().map { it.id }.toSet()
            } else {
                app.securePrefs.enabledSkills.value
            },
            aiName = aiName,
            userStory = userStory,
        )

        val collectedText = StringBuilder()
        val completion = CompletableDeferred<AgentResponse>()
        val collectedToolCalls = mutableListOf<HeartbeatToolCall>()
        val startTimeMs = System.currentTimeMillis()

        val callbacks = object : AgentLoop.Callbacks {
            override fun onToken(text: String) {
                collectedText.append(text)
                Log.i(TAG, "LLM token: $text")
            }

            override fun onToolExecution(toolName: String) {
                Log.i(TAG, "LLM calling tool: $toolName")
            }

            override fun onToolResult(toolName: String, result: SkillResult) {
                val resultStr = when (result) {
                    is SkillResult.Success -> "Success: ${result.data.take(500)}"
                    is SkillResult.Error -> "Error: ${result.message}"
                    is SkillResult.RequiresApproval -> "RequiresApproval: ${result.description}"
                }
                Log.i(TAG, "Tool result ($toolName): $resultStr")
                collectedToolCalls.add(HeartbeatToolCall(
                    toolName = toolName,
                    result = resultStr.take(500),
                ))
            }

            override suspend fun onApprovalNeeded(description: String): Boolean {
                Log.i(TAG, "Auto-approving: $description")
                return true
            }

            override suspend fun onPermissionsNeeded(permissions: List<String>): Boolean {
                // First check if all permissions are already granted — no UI needed
                val allAlreadyGranted = permissions.all { perm ->
                    ContextCompat.checkSelfPermission(app, perm) == PackageManager.PERMISSION_GRANTED
                }
                if (allAlreadyGranted) {
                    Log.i(TAG, "Permissions already granted: $permissions")
                    return true
                }

                // Not all granted — try requesting via UI if available
                val requester = app.permissionRequester
                if (requester != null) {
                    return try {
                        val results = requester.requestIfMissing(permissions)
                        val allGranted = results.values.all { it }
                        Log.i(TAG, "Permission request result: $results -> granted=$allGranted")
                        allGranted
                    } catch (e: Exception) {
                        Log.w(TAG, "Permission request failed: ${e.message}")
                        false
                    }
                }

                val missing = permissions.filter { perm ->
                    ContextCompat.checkSelfPermission(app, perm) != PackageManager.PERMISSION_GRANTED
                }
                Log.w(TAG, "No permission requester available (background), missing: $missing")
                return false
            }

            override fun onComplete(fullText: String) {
                Log.i(TAG, "=== HEARTBEAT RUN COMPLETE ===")
                Log.i(TAG, "LLM full response: ${fullText.take(1000)}")
                logStore.append(HeartbeatLogEntry(
                    timestampMs = System.currentTimeMillis(),
                    outcome = "success",
                    prompt = prompt.take(200),
                    responseText = fullText.take(1000),
                    toolCalls = collectedToolCalls.toList(),
                    durationMs = System.currentTimeMillis() - startTimeMs,
                ))
                completion.complete(AgentResponse(text = fullText))
            }

            override fun onError(error: Throwable) {
                Log.e(TAG, "=== HEARTBEAT RUN FAILED ===", error)
                logStore.append(HeartbeatLogEntry(
                    timestampMs = System.currentTimeMillis(),
                    outcome = "error",
                    prompt = prompt.take(200),
                    responseText = "",
                    error = error.message ?: "Unknown error",
                    toolCalls = collectedToolCalls.toList(),
                    durationMs = System.currentTimeMillis() - startTimeMs,
                ))
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
