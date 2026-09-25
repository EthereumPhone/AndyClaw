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

    /**
     * An event-driven trigger already ran inside
     * [HeartbeatConfig.backstopQuietMs]. The clock is the backstop, not the loop.
     */
    RECENT_EVENT_TRIGGER,
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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val TAG = "HeartbeatRunner"
    }
    private var config = HeartbeatConfig()
    private var job: Job? = null
    private var lastHeartbeatText: String? = null
    private var lastHeartbeatSentAt: Long = 0

    /**
     * When something that actually happened last woke the agent.
     *
     * Written by every event-driven path — a notification, an inbound message, a fresh
     * reservation parsed out of mail — and read by [shouldSkip]. `@Volatile` because those
     * paths are threads the scheduler's own coroutine has never met.
     */
    @Volatile
    private var lastEventTriggerMs: Long = 0L

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
                runNow()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Run a single heartbeat cycle. Can also be called on-demand.
     *
     * [eventDriven] runs answer something that happened, so the backstop window — which exists
     * to suppress *scheduled* ticks after such a run — never stops them; they used to open the
     * window and then skip themselves inside it. A successful event-driven run is what opens it.
     */
    suspend fun runOnce(eventDriven: Boolean = false): HeartbeatResult {
        val result = runOnceInner(eventDriven)
        if (eventDriven && (result.outcome == HeartbeatOutcome.OK || result.outcome == HeartbeatOutcome.ALERT)) {
            noteEventTrigger()
        }
        return result
    }

    private val pending = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * One heartbeat now — or, while one is already running, exactly one more right after it,
     * however many ask in the meantime. Returns this call's result, or null when the request
     * was folded into the trailing run of the one already going.
     */
    suspend fun runNow(eventDriven: Boolean = false): HeartbeatResult? {
        if (pending.getAndIncrement() > 0) return null
        var last: HeartbeatResult? = null
        var driven = eventDriven
        do {
            pending.set(1) // everything that asked until now is answered by this run
            last = runOnce(driven)
            onResult(last)
            // A run somebody asked for while another was going answers that request.
            driven = true
        } while (pending.decrementAndGet() > 0)
        return last
    }

    private suspend fun runOnceInner(eventDriven: Boolean): HeartbeatResult {
        // Check skip conditions
        val skipReason = shouldSkip(eventDriven)
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
     * An event-driven run just looked at the world with HEARTBEAT.md in hand.
     *
     * Marks the backstop window so the next scheduled tick inside it is skipped. [runOnce]
     * calls it after a successful event-driven run; nothing else should. A reminder or a
     * message that ran the agent without HEARTBEAT.md covered nothing on the user's list, and
     * letting those mark the window meant a stranger writing every nine minutes suppressed the
     * user's own tasks for good. Safe from any thread; it is one write.
     */
    fun noteEventTrigger() {
        lastEventTriggerMs = clock()
    }

    /**
     * Request an immediate heartbeat run outside the normal schedule.
     *
     * [eventDriven] says whether something happened or the user asked. A user pressing the
     * button must not suppress the next scheduled tick — that would make the manual control
     * quietly turn the schedule off.
     */
    fun requestNow(eventDriven: Boolean = false) {
        Log.i(TAG, "requestNow: launching immediate heartbeat (eventDriven=$eventDriven)")
        scope.launch {
            val result = runNow(eventDriven)
            Log.i(TAG, "requestNow: result=${result?.outcome ?: "folded into the running one"}")
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
        // Never opens the backstop window: the run is the sender's, under their provenance, so
        // it did not do the user's list with the user's authority — and a stranger writing
        // every few minutes would otherwise keep the user's own tasks from ever running.
        scope.launch {
            val result = runOnceWithContext(extraContext, provenance, conversationId)
            onResult(result)
        }
    }

    private fun shouldSkip(eventDriven: Boolean = false): HeartbeatSkipReason? {
        if (!config.enabled) return HeartbeatSkipReason.DISABLED
        if (!isWithinActiveHours()) return HeartbeatSkipReason.QUIET_HOURS
        if (!eventDriven && isInsideBackstopWindow()) return HeartbeatSkipReason.RECENT_EVENT_TRIGGER

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

    /**
     * True when an event-driven run has already covered this tick.
     *
     * Deliberately checked *before* the file-content check and after the quiet-hours one:
     * a suppressed tick is a real outcome the user can see in the heartbeat log, and it
     * should say the reason that actually applies rather than the first one that happens to
     * match.
     */
    private fun isInsideBackstopWindow(): Boolean {
        val window = config.backstopQuietMs
        if (window <= 0L) return false
        val last = lastEventTriggerMs
        if (last <= 0L) return false
        return clock() - last < window
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
