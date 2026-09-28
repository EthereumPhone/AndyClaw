package org.ethereumphone.andyclaw.ui.autopilot

import org.ethereumphone.andyclaw.autopilot.AutopilotEvent

/** What the live view shows for one autopilot run. Built by folding [AutopilotEvent]s. */
data class AutopilotUiState(
    val runId: String,
    val subgoals: List<String> = emptyList(),
    val subgoalIndex: Int = 0,
    val steps: Int = 0,
    val elapsedMs: Long = 0,
    val plannerCalls: Int = 0,
    /** Where the focus ring goes, in agent-display pixels. */
    val targetX: Int? = null,
    val targetY: Int? = null,
    val targetLabel: String? = null,
    val action: String? = null,
    val confidence: Double? = null,
    val source: AutopilotEvent.Source? = null,
    val lastStepMs: Long? = null,
    val lastJevMs: Long? = null,
    /** Newest first, capped. */
    val ticker: List<TickerLine> = emptyList(),
    val phase: Phase = Phase.RUNNING,
    val reason: String? = null,
    /** How the run ended, in one sentence for the user; never a reason code. */
    val message: String? = null,
    /** Bumped on every action so the tap ripple replays even on the same spot. */
    val actionSeq: Int = 0,
    /** When the run reached a final phase (uptime), so the view can fold itself away. */
    val finishedAtMs: Long? = null,
) {
    /**
     * HANDOFF is not an end: the autopilot gave the task back and the model is carrying on, so
     * STOP stays. It becomes [ENDED] when the turn is over. STOPPED is the user's own STOP, and
     * no failure.
     */
    enum class Phase { RUNNING, THINKING, HANDOFF, DONE, FAILED, STOPPED, ENDED }

    data class TickerLine(val step: Int, val text: String, val ms: Long?, val confidence: Double?, val fromPlanner: Boolean)

    val stepsPerSecond: Double get() = if (elapsedMs <= 0) 0.0 else steps * 1000.0 / elapsedMs
    val finished: Boolean get() = phase in FINAL_PHASES

    fun reduce(e: AutopilotEvent): AutopilotUiState {
        val base = copy(
            subgoals = e.subgoals.ifEmpty { subgoals },
            subgoalIndex = e.subgoalIndex,
            steps = e.step,
            elapsedMs = e.elapsedMs,
            plannerCalls = e.plannerCalls,
        )
        return when (e.kind) {
            AutopilotEvent.Kind.STARTED -> base.copy(phase = Phase.RUNNING)
            AutopilotEvent.Kind.ACTING -> base.copy(
                phase = Phase.RUNNING,
                targetX = e.target?.centerX,
                targetY = e.target?.centerY,
                targetLabel = e.target?.name,
                action = e.action,
                confidence = e.confidence,
                source = e.source,
                actionSeq = actionSeq + 1,
            )
            AutopilotEvent.Kind.SETTLED -> {
                val line = TickerLine(
                    step = e.step,
                    text = "${verb(e.action)} ${e.target?.name?.let { "\"${it.take(24)}\"" } ?: ""}".trim(),
                    ms = e.timings?.stepMs,
                    confidence = e.confidence,
                    fromPlanner = e.source == AutopilotEvent.Source.PLANNER,
                )
                base.copy(
                    lastStepMs = e.timings?.stepMs,
                    lastJevMs = e.timings?.jevMs,
                    ticker = (listOf(line) + ticker).take(MAX_TICKER),
                )
            }
            AutopilotEvent.Kind.SUBGOAL_DONE -> base
            AutopilotEvent.Kind.ESCALATED -> base.copy(phase = Phase.THINKING, reason = e.reason)
            AutopilotEvent.Kind.DONE -> base.copy(
                phase = Phase.DONE,
                subgoalIndex = subgoals.size,
                message = e.message,
                finishedAtMs = now(),
            )
            AutopilotEvent.Kind.FAILED -> {
                val phase = when (e.outcome) {
                    "handoff" -> Phase.HANDOFF
                    "stopped", "cancelled" -> Phase.STOPPED
                    else -> Phase.FAILED
                }
                base.copy(
                    phase = phase,
                    reason = e.reason,
                    message = e.message,
                    finishedAtMs = if (phase in FINAL_PHASES) now() else null,
                )
            }
        }
    }

    /**
     * The turn is over. A run still showing as running or handed over is final now: after a
     * hand-over the model finished (or not) in the chat, and nothing will move this view again.
     */
    fun endOfTurn(stopped: Boolean = false): AutopilotUiState = when {
        finished -> this
        stopped -> copy(phase = Phase.STOPPED, message = message ?: "Stopped", finishedAtMs = now())
        phase == Phase.HANDOFF -> copy(phase = Phase.ENDED, finishedAtMs = now())
        else -> copy(phase = Phase.ENDED, message = message ?: "Ended", finishedAtMs = now())
    }

    companion object {
        private const val MAX_TICKER = 6
        private val FINAL_PHASES = setOf(Phase.DONE, Phase.FAILED, Phase.STOPPED, Phase.ENDED)

        private fun now(): Long = System.nanoTime() / 1_000_000

        fun verb(action: String?) = when (action) {
            "tap" -> "TAP"
            "long" -> "HOLD"
            "type" -> "TYPE"
            "scroll_fwd", "scroll_back", "reveal" -> "SCROLL"
            "back" -> "BACK"
            else -> action?.uppercase() ?: ""
        }
    }
}
