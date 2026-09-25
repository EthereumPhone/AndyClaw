package org.ethereumphone.andyclaw.flows

/**
 * What a replay's outcome means for the flow, and for the task.
 *
 * Two different questions, answered in one place because getting either wrong is expensive:
 *
 * - **Does it count against the flow?** Only aborts that say something about the flow itself —
 *   the screen moved, a node went missing, a button now says Pay. A STOP, a missing value or a
 *   refused checkpoint is the user, not the flow; counting those retired good flows.
 * - **May the task be tried another way?** Never once the flow may have committed: a send that
 *   could not be confirmed is still probably a send, and sending it again through the autopilot
 *   or the model is the double message this exists to prevent. Never after STOP, a refused
 *   checkpoint or a sensitive target either — each of those is a "no", not a "not like this".
 *
 * "May have committed" is the interpreter's call ([FlowRunResult.Aborted.committed]): an action
 * that went out past the checkpoint, an irreversible one, or the flow's last action — and any of
 * those whose outcome is unknown. Only an action that certainly never reached the app leaves it
 * uncommitted.
 */
object FlowRunAccounting {

    private val FAULTS = setOf(
        FlowAbortReason.APP_VERSION_MISMATCH,
        FlowAbortReason.PRECONDITION_FAILED,
        FlowAbortReason.CHECKSUM_MISMATCH,
        FlowAbortReason.STEP_FAILED,
        FlowAbortReason.WAIT_TIMEOUT,
        FlowAbortReason.ASSERT_FAILED,
        FlowAbortReason.BUDGET_EXCEEDED,
        FlowAbortReason.UNSUPPORTED,
        FlowAbortReason.AMBIGUOUS_TARGET,
        FlowAbortReason.SENSITIVE_TARGET,
    )

    private val RETRYABLE_ANOTHER_WAY = FAULTS - FlowAbortReason.SENSITIVE_TARGET

    /** Whether [reason] is the flow's fault: counted toward retirement, and marks it stale. */
    fun isFault(reason: FlowAbortReason): Boolean = reason in FAULTS

    /** Whether [result] should be written into the flow's record at all. */
    fun counts(result: FlowRunResult): Boolean =
        result !is FlowRunResult.Aborted || isFault(result.reason)

    /** Whether, after [result], the task may be done by the autopilot or the model instead. */
    fun mayFallBack(result: FlowRunResult.Aborted): Boolean =
        !result.committed && result.reason in RETRYABLE_ANOTHER_WAY
}
