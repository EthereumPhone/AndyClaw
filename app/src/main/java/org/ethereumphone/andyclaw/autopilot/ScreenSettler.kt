package org.ethereumphone.andyclaw.autopilot

import android.os.SystemClock
import kotlinx.coroutines.delay
import org.ethereumphone.andyclaw.services.AgentDisplayAccessibilityService

/**
 * Waits until the agent display has reacted to an action and stopped changing.
 *
 * Input is injected asynchronously, so a fixed sleep either wastes time or reads a stale tree
 * — and an autopilot that acts on a stale tree taps the wrong thing. Instead: wait for the
 * first accessibility event after the action (proof the app reacted), then for a quiet period
 * with no further events. No event at all within the first window means the action did nothing,
 * which the executor treats as a failed step rather than something to wait out.
 */
object ScreenSettler {

    enum class Kind(val firstEventMs: Long, val quietMs: Long, val capMs: Long, val fallbackMs: Long) {
        TAP(250, 80, 1500, 120),
        TYPE(150, 60, 800, 80),
        SCROLL(250, 100, 1200, 180),
        BACK(250, 120, 1500, 150),
        LAUNCH(3000, 150, 5000, 1200),
    }

    data class Result(val ms: Long, val changed: Boolean)

    private const val POLL_MS = 8L

    /** The event counter to pass back into [await] — read it just before acting. */
    fun mark(): Long = AgentDisplayAccessibilityService.eventSeq.get()

    suspend fun await(seqBefore: Long, kind: Kind, launchPackage: String? = null): Result {
        val start = SystemClock.uptimeMillis()
        if (AgentDisplayAccessibilityService.instance == null) {
            // No event source: the old fixed delay, and no claim about whether anything changed.
            delay(kind.fallbackMs)
            return Result(kind.fallbackMs, changed = true)
        }
        val seq = AgentDisplayAccessibilityService.eventSeq

        // 1. Did the app react at all? For a launch, specifically: did its window appear?
        while (true) {
            val elapsed = SystemClock.uptimeMillis() - start
            val reacted = if (kind == Kind.LAUNCH) {
                AgentDisplayAccessibilityService.lastWindowChangeUptime >= start &&
                    (launchPackage == null || AgentDisplayAccessibilityService.lastWindowChangePackage == launchPackage)
            } else {
                seq.get() != seqBefore
            }
            if (reacted) break
            if (elapsed >= kind.firstEventMs) return Result(elapsed, changed = false)
            delay(POLL_MS)
        }

        // 2. Then wait for it to go quiet.
        while (true) {
            val now = SystemClock.uptimeMillis()
            val sinceLast = now - AgentDisplayAccessibilityService.lastEventUptime
            if (sinceLast >= kind.quietMs || now - start >= kind.capMs) break
            delay((kind.quietMs - sinceLast).coerceIn(1, 16))
        }
        return Result(SystemClock.uptimeMillis() - start, changed = true)
    }
}
