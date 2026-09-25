package org.ethereumphone.andyclaw.frames

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.agent.AgentRunToken
import org.ethereumphone.andyclaw.autopilot.AgentDisplayCapabilities
import org.ethereumphone.andyclaw.ledger.LedgerAction
import org.ethereumphone.andyclaw.ledger.LedgerDraft
import org.ethereumphone.andyclaw.ledger.LedgerKind
import org.ethereumphone.andyclaw.ledger.LedgerOutcome
import org.ethereumphone.andyclaw.ledger.LedgerSink
import org.ethereumphone.andyclaw.services.AgentDisplayAccessibilityService
import org.ethereumphone.andyclaw.skills.ToolRoutes
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayBinder
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayLease

/**
 * "Watch exactly what I did as you": the frames of the agent display, kept for the run that was
 * driving it.
 *
 * Recording follows the display lease, not the launcher. It used to live in the launcher's frame
 * stream, so only launcher chats were ever recorded — a heartbeat, a Telegram run or a cron job
 * that drove the display left no replay — and a launcher that went away ended the recording. Now
 * a recording starts when a run takes the display and ends when it gives it back, and the frames
 * go into that run's ledger session as one row, written before the run's own TURN row so that is
 * the run they belong to. Which frames are kept is [DisplayRecorder]'s call; a private app's are
 * never kept.
 */
class AgentDisplayRecording(
    private val scope: CoroutineScope,
    private val store: () -> SessionFrameStore?,
    private val ledger: () -> LedgerSink?,
    /** The user's "keep display recordings" setting. */
    private val enabled: () -> Boolean,
) : AgentDisplayLease.Listener {

    private class Active(
        val runId: String,
        val ledgerSessionId: String,
        val provenance: String,
        val session: SessionFrameStore.FrameSession,
        val recorder: DisplayRecorder,
        var job: Job? = null,
    )

    @Volatile private var active: Active? = null

    @Synchronized
    override fun onClaimed(token: AgentRunToken) {
        // Already recording this run: a second first claim must not start a second capture loop
        // that nothing would ever end.
        if (active?.runId == token.id) return
        active?.let { finish(it) } // an owner that died without releasing
        val sessionId = token.ledgerSessionId ?: return
        if (!enabled()) return
        val frames = store() ?: return
        val session = runCatching { frames.beginSession(sessionId) }.getOrNull() ?: return
        val a = Active(token.id, sessionId, token.provenance, session, DisplayRecorder())
        active = a
        a.job = scope.launch(Dispatchers.IO) { capture(a) }
    }

    @Synchronized
    override fun onReleased(runId: String) {
        active?.takeIf { it.runId == runId }?.let { finish(it) }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.capture(a: Active) {
        while (isActive) {
            try {
                val svc = AgentDisplayBinder.serviceOrNull()
                val displayId = svc?.displayId ?: -1
                if (svc != null && displayId >= 0) {
                    val now = System.currentTimeMillis()
                    val a11y = AgentDisplayAccessibilityService.instance
                    // A private app's screen is never kept — nor one nobody could check: with no
                    // window to read there is no telling whether it was the wallet.
                    val privateApp = if (a11y == null || !a11y.hasWindowsOn(displayId)) UNCHECKED
                    else a11y.sensitivePackageOnDisplay(displayId)
                    if (privateApp != null) {
                        a.recorder.offer(ByteArray(0), now, privateApp = true)
                    } else {
                        val frame = if (AgentDisplayCapabilities.hasV2) svc.captureFrameScaled(FRAME_WIDTH, FRAME_QUALITY)
                        else svc.captureFrameWithQuality(FRAME_QUALITY)
                        if (frame != null && frame.isNotEmpty() && a.recorder.offer(frame, now) == DisplayRecorder.Verdict.KEEP) {
                            a.session.write(frame)
                        }
                    }
                }
            } catch (e: Exception) {
                // No display between runs of the same turn, a parked one, a binder hiccup:
                // nothing to keep this time round.
            }
            delay(POLL_MS)
        }
    }

    private fun finish(a: Active) {
        if (active === a) active = null
        a.job?.cancel()
        val ids = runCatching { a.session.close() }.getOrDefault(emptyList())
        if (ids.isEmpty()) return
        val sink = ledger() ?: return
        val note = buildString {
            append("${ids.size} frame(s)")
            if (a.recorder.truncated) append("; the recording hit its cap")
            if (a.recorder.skippedPrivate > 0) append("; a private app, or a screen that could not be checked, was not recorded")
        }
        runCatching {
            sink.record(
                LedgerDraft(
                    sessionId = a.ledgerSessionId,
                    kind = LedgerKind.TOOL,
                    intent = "agent display recording",
                    provenance = a.provenance,
                    outcome = LedgerOutcome.OK,
                    routeRung = ToolRoutes.RUNG_DISPLAY,
                    actions = listOf(LedgerAction(tool = "agent_display_capture", ok = true, durationMs = a.recorder.durationMs, note = note)),
                    frames = ids,
                )
            )
        }.onFailure { Log.w(TAG, "could not record the display recording", it) }
        Log.i(TAG, "kept ${ids.size} frame(s) for run ${a.runId}")
    }

    private companion object {
        const val TAG = "AgentDisplayRecording"
        const val UNCHECKED = "(unchecked)"
        const val POLL_MS = 500L
        const val FRAME_WIDTH = 480
        const val FRAME_QUALITY = 70
    }
}
