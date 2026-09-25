package org.ethereumphone.andyclaw.flows

import org.ethereumphone.andyclaw.ExecutionEngine.Provenance

/**
 * Whether a replay may cross a `checkpoint:` — the flow's own declaration of where it stops
 * being undoable.
 *
 * It used to *assume* the approval card had run whenever the tool was marked as needing one.
 * The tool list is memoized, though, so after the user turned confirmations back on a replay
 * could cross its checkpoint with no card at all. Now it is proved, not assumed:
 *
 * - the engine attaches a witness to a call the user approved (`UserApproval`), and that is
 *   enough on its own; or
 * - the user has said "don't ask me" (no-confirm), and the request is theirs or one they set up.
 *   A message or a webhook never gets past a checkpoint without a card.
 */
object FlowCheckpointPolicy {

    fun mayCross(approvedThisCall: Boolean, noConfirm: Boolean, provenance: Provenance): Boolean =
        approvedThisCall || (noConfirm && (provenance == Provenance.USER || provenance == Provenance.TRUSTED))
}
