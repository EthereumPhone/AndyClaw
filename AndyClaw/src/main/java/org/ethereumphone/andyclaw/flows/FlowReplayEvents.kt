package org.ethereumphone.andyclaw.flows

import org.ethereumphone.andyclaw.autopilot.AutopilotEvent
import org.ethereumphone.andyclaw.autopilot.AutopilotEvent.Kind
import org.ethereumphone.andyclaw.autopilot.AutopilotEventSink
import org.ethereumphone.andyclaw.autopilot.AutopilotOutcome
import org.ethereumphone.andyclaw.autopilot.StepTimings

/**
 * A compiled-flow replay told as an autopilot run (IPC-08).
 *
 * The home screen's card, its preview and its STOP are all built from autopilot events: STARTED
 * starts the frames, the ticker follows ACTING/SETTLED, one DONE or FAILED ends the card. A replay
 * emitted none of them, so the more tasks the agent learned, the less of what it did on the
 * display anybody saw. Now a replay is a run like any other: STARTED with one sub-goal per tap
 * or typed value, ACTING → SETTLED → SUBGOAL_DONE per action, and exactly one DONE or FAILED with
 * an `outcome` — however it ends, a cancel included. The run id is `flow-…`.
 *
 * No target is sent: the interpreter acts on view ids and never learns where the node is, and the
 * card already draws a run without a focus ring. Typed values are never sent, only that a field
 * was typed into.
 */
class FlowReplayEvents(
    flow: Flow,
    private val sink: AutopilotEventSink,
    private val clock: () -> Long = System::currentTimeMillis,
    val runId: String = newRunId(),
) : FlowActionListener {

    /** `flow.steps` indices of the actions, in order; each is a sub-goal of the card. */
    private val actionSteps: List<Int> = flow.steps.indices.filter { flow.steps[it] is TapStep || flow.steps[it] is TypeStep }

    /** One label per action: "Tap send button", "Type into message field". */
    val subgoals: List<String> = actionSteps.map { label(flow.steps[it]) }

    private val startedMs = clock()
    private var actingMs = startedMs
    private var done = 0
    private var subgoal = 0
    @Volatile private var finished = false

    fun started() = emit(Kind.STARTED)

    override fun onAction(stepIndex: Int, opcode: String, viewId: String, settled: Boolean) {
        val i = actionSteps.indexOf(stepIndex)
        if (i < 0 || finished) return
        subgoal = i
        if (!settled) {
            actingMs = clock()
            emit(Kind.ACTING, action = opcode)
        } else {
            done = i + 1
            val now = clock()
            emit(Kind.SETTLED, action = opcode, timings = StepTimings(stepMs = now - actingMs))
            subgoal = done
            emit(Kind.SUBGOAL_DONE)
        }
    }

    /**
     * How the replay ended. [handsOver]: an abort that lets the task be done another way (the
     * autopilot recompiling it, or the model) — a hand-over, not a failure.
     */
    fun finished(result: FlowRunResult, handsOver: Boolean) {
        when (result) {
            is FlowRunResult.Completed -> end(Kind.DONE, AutopilotOutcome.Outcome.SUCCESS, null, "Done")
            is FlowRunResult.Aborted -> {
                val (outcome, reason, message) = abortOutcome(result, handsOver)
                end(Kind.FAILED, outcome, reason, message)
            }
        }
    }

    /** The turn was cancelled under the replay. */
    fun cancelled() = end(Kind.FAILED, AutopilotOutcome.Outcome.CANCELLED, AutopilotOutcome.REASON_CANCELLED, "Cancelled")

    /** The replay threw before it could say how it ended. */
    fun crashed() = end(Kind.FAILED, AutopilotOutcome.Outcome.FAILED, "internal_error", "Something went wrong on my side")

    private fun end(kind: Kind, outcome: AutopilotOutcome.Outcome, reason: String?, message: String) {
        if (finished) return
        emit(kind, reason = reason, outcome = outcome.wire, message = message)
        finished = true
    }

    private fun emit(
        kind: Kind,
        action: String? = null,
        timings: StepTimings? = null,
        reason: String? = null,
        outcome: String? = null,
        message: String? = null,
    ) {
        val event = AutopilotEvent(
            kind = kind,
            runId = runId,
            step = done,
            subgoalIndex = subgoal,
            subgoals = subgoals,
            action = action,
            timings = timings,
            elapsedMs = clock() - startedMs,
            reason = reason,
            outcome = outcome,
            message = message,
        )
        // Watching a replay must never be why it fails.
        try {
            sink.onEvent(event)
        } catch (_: Exception) {
        }
    }

    companion object {
        fun newRunId(): String = "flow-" + java.lang.Long.toHexString(System.nanoTime() and 0xffffff)

        /** A step as the card shows it: the verb and the view id's own name, never a value. */
        fun label(step: FlowStep): String {
            val (verb, viewId) = when (step) {
                is TapStep -> "Tap" to step.viewId
                is TypeStep -> "Type into" to step.target.viewId
                else -> step.opcode to null
            }
            val name = viewId?.substringAfterLast('/')?.replace('_', ' ')?.trim().orEmpty()
            return if (name.isEmpty()) verb else "$verb $name"
        }

        /** outcome, reason, message — the reason in the executor's vocabulary where it has one. */
        fun abortOutcome(result: FlowRunResult.Aborted, handsOver: Boolean): Triple<AutopilotOutcome.Outcome, String, String> =
            when {
                result.reason == FlowAbortReason.STOPPED ->
                    Triple(AutopilotOutcome.Outcome.STOPPED, AutopilotOutcome.REASON_STOPPED, "Stopped")
                // Done, most likely, and never to be repeated: not a hand-over, whatever else holds.
                result.committed ->
                    Triple(AutopilotOutcome.Outcome.FAILED, "flow_unconfirmed", "Probably done, but I couldn't confirm it — check before trying again")
                handsOver ->
                    Triple(AutopilotOutcome.Outcome.HANDOFF, "flow_stale", "The saved steps no longer fit, so I'm doing it another way")
                result.reason == FlowAbortReason.SENSITIVE_TARGET ->
                    Triple(AutopilotOutcome.Outcome.FAILED, "sensitive", "That step needs you (payment or sign-in)")
                result.reason == FlowAbortReason.APP_NOT_INSTALLED ->
                    Triple(AutopilotOutcome.Outcome.FAILED, "app_not_installed", "That app isn't installed")
                result.reason == FlowAbortReason.DISPLAY_UNAVAILABLE ->
                    Triple(AutopilotOutcome.Outcome.FAILED, "app_unavailable", "Couldn't open the app")
                result.reason == FlowAbortReason.CHECKPOINT_REFUSED ->
                    Triple(AutopilotOutcome.Outcome.FAILED, "checkpoint_refused", "Stopped before the step that can't be undone")
                else ->
                    Triple(AutopilotOutcome.Outcome.FAILED, "flow_" + result.reason.name.lowercase(), "The saved steps didn't fit this screen")
            }
    }
}
