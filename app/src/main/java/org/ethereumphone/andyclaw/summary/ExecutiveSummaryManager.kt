package org.ethereumphone.andyclaw.summary

import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.ethereumphone.andyclaw.NodeApp
import java.io.File
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.llm.MessagesRequest
import org.ethereumphone.andyclaw.llm.MessagesResponse
import org.ethereumphone.andyclaw.llm.StreamingCallback

/**
 * Generates and stores an executive summary via the system-level
 * AndyClawHeartbeatService binder.
 *
 * The summary is a 2-3 sentence snapshot written by the LLM after
 * every heartbeat, notification, or lockscreen voice prompt.
 *
 * When a [streamListener] is registered (by the launcher via IPC),
 * generation uses streaming so that tokens are pushed to the launcher
 * in real time.
 */
class ExecutiveSummaryManager(private val app: NodeApp) {

    /** Listener for real-time streaming of exec summary tokens to the launcher. */
    interface SummaryStreamListener {
        fun onToken(token: String)
        fun onComplete(fullSummary: String)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "ExecSummaryMgr"
        private const val OS_BINDER_DESCRIPTOR = "com.android.server.IAndyClawHeartbeat"
        private const val TRANSACTION_SET = 2 // IBinder.FIRST_CALL_TRANSACTION + 2
        private const val TRANSACTION_GET = 3 // IBinder.FIRST_CALL_TRANSACTION + 3

        private const val HEARTBEAT_SYSTEM_PROMPT = """Write a lockscreen executive summary as bullet points (• prefix). Summarize ONLY: device state (battery, connectivity), pending notifications, and upcoming tasks/reminders. Do NOT include the current time or date. Max 4-5 bullets, each one short sentence. Output ONLY the bullet points, nothing else."""

        private const val LOCKSCREEN_SYSTEM_PROMPT = """Update the lockscreen executive summary after the user issued a voice command. The FIRST bullet (• prefix) must be a one-sentence summary of what the user asked and what was done. Keep remaining bullets about: device state, notifications, tasks. Do NOT include the current time or date. Remove outdated bullets. Max 4-5 bullets total. Output ONLY the bullet points, nothing else."""

        private const val NOTIFICATION_SYSTEM_PROMPT = """Update the lockscreen executive summary based on recent notifications. Replace outdated bullets with the most important new information. Keep remaining bullets about: device state, notifications, tasks. Do NOT include the current time or date. Remove stale bullets. Max 4-5 bullets total. Output ONLY the bullet points, nothing else."""
    }

    /** Set by LauncherBindingService when the launcher registers for streaming updates. */
    @Volatile
    var streamListener: SummaryStreamListener? = null

    // ── Dismissed bullets ────────────────────────────────────────────────────
    // Bullets the user swiped away. The LLM is instructed not to regenerate
    // similar content. Stored as a JSON array, max 10 entries (ring buffer).

    private val dismissedFile = File(app.filesDir, "dismissed_exec_bullets.json")
    private val maxDismissed = 10

    fun addDismissedBullet(bulletText: String) {
        val clean = bulletText.trim().removePrefix("•").trim()
        if (clean.isBlank()) return
        val list = getDismissedBullets().toMutableList()
        if (list.any { it.equals(clean, ignoreCase = true) }) return // already dismissed
        list.add(clean)
        while (list.size > maxDismissed) list.removeAt(0)
        dismissedFile.writeText(JSONArray(list).toString())
        Log.i(TAG, "Dismissed bullet added: \"${clean.take(60)}\" (${list.size} total)")
    }

    fun getDismissedBullets(): List<String> {
        if (!dismissedFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(dismissedFile.readText())
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Appends dismissed-bullet instructions to a system prompt if any exist. */
    private fun augmentPromptWithDismissals(basePrompt: String): String {
        val dismissed = getDismissedBullets()
        if (dismissed.isEmpty()) return basePrompt
        val dismissList = dismissed.joinToString("\n") { "- $it" }
        return basePrompt + "\n\nIMPORTANT: The user has dismissed these bullets — do NOT regenerate similar content:\n$dismissList"
    }

    /**
     * Generate and store an executive summary after a heartbeat or notification.
     * No-op if the feature is disabled.
     */
    suspend fun generateAndStore(agentOutput: String) {
        if (!app.securePrefs.executiveSummaryEnabled.value) {
            Log.i(TAG, "generateAndStore: executive summary disabled, skipping")
            return
        }

        Log.i(TAG, "generateAndStore: starting from heartbeat output (${agentOutput.length} chars)")
        try {
            val currentSummary = readSummaryFromService()
            Log.i(TAG, "generateAndStore: current summary=${currentSummary.take(200)}")
            val prompt = buildString {
                if (currentSummary.isNotBlank()) {
                    appendLine("Current executive summary: $currentSummary")
                    appendLine()
                }
                appendLine("Latest heartbeat output:")
                append(agentOutput.take(2000))
            }
            val listener = streamListener
            val newSummary = if (listener != null) {
                callLlmStreaming(HEARTBEAT_SYSTEM_PROMPT, prompt)
            } else {
                callLlm(HEARTBEAT_SYSTEM_PROMPT, prompt)
            }
            if (newSummary.isNotBlank()) {
                writeSummaryToService(newSummary)
                listener?.onComplete(newSummary)
                Log.i(TAG, "generateAndStore: summary written (${newSummary.length} chars)")
            } else {
                Log.w(TAG, "generateAndStore: LLM returned blank summary")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate executive summary", e)
            streamListener?.onError(e.message ?: "Unknown error")
        }
    }

    /**
     * Generate and store an executive summary after a lockscreen voice prompt.
     * No-op if the feature is disabled.
     */
    suspend fun generateAndStoreForLockscreen(userPrompt: String, agentOutput: String) {
        if (!app.securePrefs.executiveSummaryEnabled.value) return

        try {
            val currentSummary = readSummaryFromService()
            val prompt = buildString {
                if (currentSummary.isNotBlank()) {
                    appendLine("Current executive summary: $currentSummary")
                    appendLine()
                }
                appendLine("User's voice command: $userPrompt")
                appendLine()
                appendLine("Execution result:")
                append(agentOutput.take(2000))
            }
            val listener = streamListener
            val newSummary = if (listener != null) {
                callLlmStreaming(LOCKSCREEN_SYSTEM_PROMPT, prompt)
            } else {
                callLlm(LOCKSCREEN_SYSTEM_PROMPT, prompt)
            }
            if (newSummary.isNotBlank()) {
                writeSummaryToService(newSummary)
                listener?.onComplete(newSummary)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate lockscreen executive summary", e)
            streamListener?.onError(e.message ?: "Unknown error")
        }
    }

    /**
     * Generate and store an executive summary after an incoming notification.
     * The prompt already contains the current summary + notification details (built by the OS).
     */
    suspend fun generateAndStoreForNotification(prompt: String) {
        if (!app.securePrefs.executiveSummaryEnabled.value) {
            Log.i(TAG, "generateAndStoreForNotification: executive summary disabled, skipping")
            return
        }

        Log.i(TAG, "generateAndStoreForNotification: starting, prompt=${prompt.take(200)}")
        try {
            val listener = streamListener
            val newSummary = if (listener != null) {
                callLlmStreaming(NOTIFICATION_SYSTEM_PROMPT, prompt)
            } else {
                callLlm(NOTIFICATION_SYSTEM_PROMPT, prompt)
            }
            Log.i(TAG, "generateAndStoreForNotification: LLM returned ${newSummary.length} chars: ${newSummary.take(300)}")
            if (newSummary.isNotBlank()) {
                writeSummaryToService(newSummary)
                listener?.onComplete(newSummary)
                Log.i(TAG, "generateAndStoreForNotification: summary written to service")
            } else {
                Log.w(TAG, "generateAndStoreForNotification: LLM returned blank summary")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate notification executive summary", e)
            streamListener?.onError(e.message ?: "Unknown error")
        }
    }

    // ── Non-streaming LLM call (existing) ────────────────────────────────────

    private suspend fun callLlm(systemPrompt: String, userMessage: String): String {
        val client = app.getHeartbeatLlmClient()
        val useSameModel = app.securePrefs.heartbeatUseSameModel.value
        val modelId = if (useSameModel) {
            app.securePrefs.selectedModel.value
        } else {
            app.securePrefs.heartbeatModel.value
        }
        val model = AnthropicModels.fromModelId(modelId) ?: AnthropicModels.MINIMAX_M3
        val augmentedPrompt = augmentPromptWithDismissals(systemPrompt)

        Log.i(TAG, "callLlm: model=${model.modelId}, provider=${model.provider}, useSame=$useSameModel, userMsg=${userMessage.take(150)}")

        val request = MessagesRequest(
            model = model.modelId,
            messages = listOf(
                Message.user(userMessage),
            ),
            system = augmentedPrompt,
            maxTokens = 300,
        )

        Log.i(TAG, "callLlm: sending request to LLM...")
        val startMs = System.currentTimeMillis()
        val response = client.sendMessage(request)
        val elapsedMs = System.currentTimeMillis() - startMs
        val text = response.content
            .filterIsInstance<ContentBlock.TextBlock>()
            .joinToString("") { it.text }
            .trim()
        val usage = response.usage
        Log.i(TAG, "callLlm: response received in ${elapsedMs}ms, ${text.length} chars, inputTokens=${usage?.inputTokens}, outputTokens=${usage?.outputTokens}")
        return text
    }

    // ── Streaming LLM call (new — pushes tokens to launcher) ─────────────────

    private suspend fun callLlmStreaming(systemPrompt: String, userMessage: String): String {
        val client = app.getHeartbeatLlmClient()
        val useSameModel = app.securePrefs.heartbeatUseSameModel.value
        val modelId = if (useSameModel) {
            app.securePrefs.selectedModel.value
        } else {
            app.securePrefs.heartbeatModel.value
        }
        val model = AnthropicModels.fromModelId(modelId) ?: AnthropicModels.MINIMAX_M3
        val augmentedPrompt = augmentPromptWithDismissals(systemPrompt)

        Log.i(TAG, "callLlmStreaming: model=${model.modelId}, provider=${model.provider}, useSame=$useSameModel")

        val request = MessagesRequest(
            model = model.modelId,
            messages = listOf(
                Message.user(userMessage),
            ),
            system = augmentedPrompt,
            maxTokens = 300,
        )

        val fullText = StringBuilder()
        val completable = CompletableDeferred<String>()
        val listener = streamListener

        Log.i(TAG, "callLlmStreaming: sending streaming request to LLM...")
        val startMs = System.currentTimeMillis()

        client.streamMessage(request, object : StreamingCallback {
            override fun onToken(text: String) {
                fullText.append(text)
                try {
                    listener?.onToken(text)
                } catch (e: Exception) {
                    Log.w(TAG, "callLlmStreaming: listener onToken failed", e)
                }
            }

            override fun onToolUse(id: String, name: String, input: JsonObject) {
                // Exec summary never uses tools — ignore
            }

            override fun onComplete(response: MessagesResponse) {
                val elapsedMs = System.currentTimeMillis() - startMs
                val result = fullText.toString().trim()
                val usage = response.usage
                Log.i(TAG, "callLlmStreaming: completed in ${elapsedMs}ms, ${result.length} chars, inputTokens=${usage?.inputTokens}, outputTokens=${usage?.outputTokens}")
                completable.complete(result)
            }

            override fun onError(error: Throwable) {
                Log.e(TAG, "callLlmStreaming: LLM error", error)
                completable.completeExceptionally(error)
            }
        })

        return completable.await()
    }

    // ── OS binder communication ──────────────────────────────────────────────

    private fun getServiceBinder(): IBinder? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            getService.invoke(null, "andyclawheartbeat") as? IBinder
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get andyclawheartbeat service", e)
            null
        }
    }

    fun readSummaryFromService(): String {
        val binder = getServiceBinder() ?: return ""
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(OS_BINDER_DESCRIPTOR)
            binder.transact(
                IBinder.FIRST_CALL_TRANSACTION + TRANSACTION_GET,
                data, reply, 0
            )
            reply.readException()
            reply.readString() ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read executive summary from service", e)
            ""
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Removes a dismissed bullet from the currently cached summary in the
     * system service so it doesn't reappear on the next fetch.
     */
    fun removeBulletFromCachedSummary(bulletText: String) {
        val current = readSummaryFromService()
        if (current.isBlank()) return
        val filtered = current.lines()
            .filter { it.trim() != bulletText.trim() }
            .joinToString("\n")
            .trim()
        if (filtered != current.trim()) {
            writeSummaryToService(filtered)
            Log.i(TAG, "Removed bullet from cached summary (${current.length} -> ${filtered.length} chars)")
        }
    }

    private fun writeSummaryToService(summary: String) {
        val binder = getServiceBinder() ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(OS_BINDER_DESCRIPTOR)
            data.writeString(summary)
            binder.transact(
                IBinder.FIRST_CALL_TRANSACTION + TRANSACTION_SET,
                data, null, IBinder.FLAG_ONEWAY
            )
            Log.d(TAG, "Executive summary written (${summary.length} chars)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write executive summary to service", e)
        } finally {
            data.recycle()
        }
    }
}
