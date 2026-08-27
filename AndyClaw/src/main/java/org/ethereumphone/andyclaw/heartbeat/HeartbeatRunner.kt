package org.ethereumphone.andyclaw.heartbeat

import android.util.Log
import java.io.File
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.agent.AgentRunner

/**
 * Result of a single heartbeat run.
 */
data class HeartbeatResult(
    val outcome: HeartbeatOutcome,
    val text: String? = null,
    val error: String? = null,
    /** Why the run was skipped. Only set when [outcome] is [HeartbeatOutcome.SKIPPED]. */
    val skipReason: HeartbeatSkipReason? = null,
)

enum class HeartbeatOutcome {
    /** Agent replied HEARTBEAT_OK - nothing to report. */
    OK,
    /** Agent returned actionable content to deliver. */
    ALERT,
    /** Heartbeat was skipped (disabled, quiet hours, empty file, etc.). */
    SKIPPED,
    /** Heartbeat run failed with an error. */
    ERROR,
}

enum class HeartbeatSkipReason {
    DISABLED,
    QUIET_HOURS,
    EMPTY_HEARTBEAT_FILE,
}

/**
 * The heartbeat runner - periodically invokes the AI agent to check HEARTBEAT.md
 * and relay actionable content. Mirrors OpenClaw's heartbeat-runner.ts.
 *
 * The heartbeat is the "pulse" of the AI - a periodic self-initiated check that
 * keeps the agent aware of pending tasks and reminders.
 */
class HeartbeatRunner(
    private val scope: CoroutineScope,
    private val agentRunner: AgentRunner,
    private val workspaceDir: String,
    private val onResult: (HeartbeatResult) -> Unit,
) {
    companion object {
        private const val TAG = "HeartbeatRunner"
    }
    private var config = HeartbeatConfig()
    private var job: Job? = null
    private var lastHeartbeatText: String? = null
    private var lastHeartbeatSentAt: Long = 0

    /** De-duplication window: suppress identical heartbeats within this period. */
    private val dedupeWindowMs = 24L * 60 * 60 * 1000 // 24 hours

    fun updateConfig(newConfig: HeartbeatConfig) {
        val wasRunning = job?.isActive == true
        config = newConfig
        if (wasRunning) {
            stop()
            start()
        }
    }

    fun start() {
        if (!config.enabled) {
            Log.i(TAG, "Heartbeat disabled, not starting")
            return
        }
        stop()
        job = scope.launch {
            Log.i(TAG, "Heartbeat started: interval=${config.intervalMs}ms")
            while (isActive) {
                delay(config.intervalMs)
                if (!isActive) break
                val result = runOnce()
                onResult(result)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Run a single heartbeat cycle. Can also be called on-demand.
     */
    suspend fun runOnce(): HeartbeatResult {
        // Check skip conditions
        val skipReason = shouldSkip()
        if (skipReason != null) {
            Log.i(TAG, "Heartbeat skipped: $skipReason")
            return HeartbeatResult(HeartbeatOutcome.SKIPPED, skipReason = skipReason)
        }

        return try {
            val heartbeatFile = resolveHeartbeatFile()
            val prompt = buildPrompt(heartbeatFile)
            Log.i(TAG, "runOnce: calling agentRunner.run(), prompt=${prompt.take(200)}")

            val startMs = System.currentTimeMillis()
            // HEARTBEAT.md is the user's own task list — trusted content.
            val response = agentRunner.run(prompt = prompt, provenance = Provenance.TRUSTED)
            val elapsedMs = System.currentTimeMillis() - startMs
            Log.i(TAG, "runOnce: agentRunner.run() returned in ${elapsedMs}ms, isError=${response.isError}, textLen=${response.text.length}")

            if (response.isError) {
                Log.e(TAG, "runOnce: agent returned error: ${response.text.take(300)}")
                return HeartbeatResult(HeartbeatOutcome.ERROR, error = response.text)
            }

            val stripped = HeartbeatPrompt.stripToken(response.text, config.ackMaxChars)

            if (stripped.shouldSkip) {
                Log.i(TAG, "Heartbeat: HEARTBEAT_OK - nothing to report")
                return HeartbeatResult(HeartbeatOutcome.OK)
            }

            // De-duplicate identical heartbeats within window
            val now = System.currentTimeMillis()
            if (stripped.text == lastHeartbeatText &&
                (now - lastHeartbeatSentAt) < dedupeWindowMs
            ) {
                Log.i(TAG, "Heartbeat: suppressed duplicate within 24h window")
                return HeartbeatResult(HeartbeatOutcome.OK)
            }

            lastHeartbeatText = stripped.text
            lastHeartbeatSentAt = now
            Log.i(TAG, "Heartbeat ALERT: ${stripped.text.take(200)}")
            HeartbeatResult(HeartbeatOutcome.ALERT, text = stripped.text)
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat error: ${e.message}", e)
            HeartbeatResult(HeartbeatOutcome.ERROR, error = e.message)
        }
    }

    /**
     * Request an immediate heartbeat run outside the normal schedule.
     */
    fun requestNow() {
        Log.i(TAG, "requestNow: launching immediate heartbeat")
        scope.launch {
            val result = runOnce()
            Log.i(TAG, "requestNow: result=${result.outcome}, text=${result.text?.take(100)}, error=${result.error?.take(100)}")
            onResult(result)
        }
    }

    /**
     * Request an immediate heartbeat run with extra context injected into the prompt.
     * The [extraContext] is appended after HEARTBEAT.md content so the agent can
     * act on it (e.g. new incoming XMTP messages).
     *
     * [provenance] classifies [extraContext], which is usually written by whoever
     * messaged the device — hence the closed default.
     */
    fun requestNowWithContext(
        extraContext: String,
        provenance: Provenance = Provenance.UNTRUSTED,
        conversationId: String? = null,
    ) {
        scope.launch {
            val result = runOnceWithContext(extraContext, provenance, conversationId)
            onResult(result)
        }
    }

    private fun shouldSkip(): HeartbeatSkipReason? {
        if (!config.enabled) return HeartbeatSkipReason.DISABLED
        if (!isWithinActiveHours()) return HeartbeatSkipReason.QUIET_HOURS

        // Check if heartbeat file is effectively empty
        val file = resolveHeartbeatFile()
        if (file.exists()) {
            val content = file.readText()
            if (HeartbeatPrompt.isContentEffectivelyEmpty(content)) {
                return HeartbeatSkipReason.EMPTY_HEARTBEAT_FILE
            }
        }

        return null
    }

    private fun isWithinActiveHours(): Boolean {
        val start = config.activeHoursStart ?: return true
        val end = config.activeHoursEnd ?: return true
        val now = LocalTime.now().hour
        return if (start <= end) {
            now in start..end
        } else {
            // Wraps around midnight (e.g., 22..6)
            now >= start || now <= end
        }
    }

    private fun resolveHeartbeatFile(): File {
        val path = config.heartbeatFilePath
        return if (path != null) {
            File(path)
        } else {
            File(workspaceDir, "HEARTBEAT.md")
        }
    }

    private fun buildPrompt(heartbeatFile: File): String {
        val base = config.prompt
        return if (heartbeatFile.exists()) {
            val content = heartbeatFile.readText()
            "$base\n\n--- HEARTBEAT.md ---\n$content"
        } else {
            base
        }
    }

    /**
     * Run a single heartbeat cycle with extra context appended to the prompt.
     * Skips the empty-file and quiet-hours checks since the caller is providing
     * explicit trigger context (e.g. new XMTP messages).
     *
     * The run carries the caller's [provenance], not the heartbeat's.
     */
    private suspend fun runOnceWithContext(
        extraContext: String,
        provenance: Provenance,
        conversationId: String?,
    ): HeartbeatResult {
        if (!config.enabled) {
            Log.i(TAG, "runOnceWithContext: heartbeat disabled, skipping")
            return HeartbeatResult(HeartbeatOutcome.SKIPPED, skipReason = HeartbeatSkipReason.DISABLED)
        }

        Log.i(TAG, "runOnceWithContext: extraContext=${extraContext.take(200)}")
        return try {
            val heartbeatFile = resolveHeartbeatFile()
            val basePrompt = buildPrompt(heartbeatFile)
            val prompt = "$basePrompt\n\n--- INCOMING CONTEXT ---\n$extraContext"

            Log.i(TAG, "runOnceWithContext: calling agentRunner.run() as $provenance")
            val startMs = System.currentTimeMillis()
            // The injected context is somebody else's words even though the
            // surrounding heartbeat prompt is the user's — the caller classifies it.
            val response = agentRunner.run(
                prompt = prompt,
                provenance = provenance,
                conversationId = conversationId,
            )
            val elapsedMs = System.currentTimeMillis() - startMs
            Log.i(TAG, "runOnceWithContext: agentRunner returned in ${elapsedMs}ms, isError=${response.isError}, textLen=${response.text.length}")

            if (response.isError) {
                Log.e(TAG, "runOnceWithContext: agent error: ${response.text.take(300)}")
                return HeartbeatResult(HeartbeatOutcome.ERROR, error = response.text)
            }

            val stripped = HeartbeatPrompt.stripToken(response.text, config.ackMaxChars)

            if (stripped.shouldSkip) {
                Log.i(TAG, "Heartbeat (with context): HEARTBEAT_OK - nothing to report")
                return HeartbeatResult(HeartbeatOutcome.OK)
            }

            lastHeartbeatText = stripped.text
            lastHeartbeatSentAt = System.currentTimeMillis()
            Log.i(TAG, "Heartbeat (with context) ALERT: ${stripped.text.take(200)}")
            HeartbeatResult(HeartbeatOutcome.ALERT, text = stripped.text)
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat (with context) error: ${e.message}", e)
            HeartbeatResult(HeartbeatOutcome.ERROR, error = e.message)
        }
    }
}
