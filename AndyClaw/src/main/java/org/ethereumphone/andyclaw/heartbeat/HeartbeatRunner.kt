package org.ethereumphone.andyclaw.heartbeat

import java.io.File
import java.time.LocalTime
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.agent.AgentRunner

/**
 * Result of a single heartbeat run.
 */
data class HeartbeatResult(
    val outcome: HeartbeatOutcome,
    val text: String? = null,
    val error: String? = null,
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
    private val log = Logger.getLogger("HeartbeatRunner")
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
            log.info("Heartbeat disabled, not starting")
            return
        }
        stop()
        job = scope.launch {
            log.info("Heartbeat started: interval=${config.intervalMs}ms")
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
            log.fine("Heartbeat skipped: $skipReason")
            return HeartbeatResult(HeartbeatOutcome.SKIPPED)
        }

        return try {
            val heartbeatFile = resolveHeartbeatFile()
            val prompt = buildPrompt(heartbeatFile)

            val response = agentRunner.run(prompt = prompt)

            if (response.isError) {
                return HeartbeatResult(HeartbeatOutcome.ERROR, error = response.text)
            }

            val stripped = HeartbeatPrompt.stripToken(response.text, config.ackMaxChars)

            if (stripped.shouldSkip) {
                log.fine("Heartbeat: HEARTBEAT_OK - nothing to report")
                return HeartbeatResult(HeartbeatOutcome.OK)
            }

            // De-duplicate identical heartbeats within window
            val now = System.currentTimeMillis()
            if (stripped.text == lastHeartbeatText &&
                (now - lastHeartbeatSentAt) < dedupeWindowMs
            ) {
                log.fine("Heartbeat: suppressed duplicate within 24h window")
                return HeartbeatResult(HeartbeatOutcome.OK)
            }

            lastHeartbeatText = stripped.text
            lastHeartbeatSentAt = now
            HeartbeatResult(HeartbeatOutcome.ALERT, text = stripped.text)
        } catch (e: Exception) {
            log.warning("Heartbeat error: ${e.message}")
            HeartbeatResult(HeartbeatOutcome.ERROR, error = e.message)
        }
    }

    /**
     * Request an immediate heartbeat run outside the normal schedule.
     */
    fun requestNow() {
        scope.launch {
            val result = runOnce()
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
}
