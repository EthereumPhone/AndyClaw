package org.ethereumphone.andyclaw.services

import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.skills.ToolEffect

/**
 * What a home-screen turn does with a call that needs the user's approval.
 *
 * The launcher has no way to ask mid-turn (`ILauncherCallback` has no approval method, and adding
 * one is an ordinal), so its turns used to answer every approval request with yes — less
 * protection than a background heartbeat, on the surface most requests come through. Now a call
 * is **queued** as a pending approval, the exact call, for the launcher to show in the
 * conversation with the same APPROVE → device credential → run-once flow as the home cards, when:
 *  - it is SENSITIVE (payment, auth, the agent's own code): never completed on the way past;
 *  - the run has read someone else's words (a page, a mail, a notification) or is not the user's
 *    own: what it now wants may be that person's idea;
 *  - the turn came from the lock screen: whoever holds a locked phone is not necessarily its owner.
 * Otherwise — the user asked, and nothing but the user has spoken — it runs, as it always has.
 * YOLO approves everything, as in AndyClaw's own chat, except on the lock screen.
 *
 * Only calls that need approval reach this: the provenance gate, `requiresApproval` and a tool's
 * own RequiresApproval decide that. This decides what "ask the user" means when nobody can be
 * asked right now.
 */
object LauncherApprovalPolicy {

    enum class Decision { RUN, QUEUE }

    fun decide(
        effect: ToolEffect,
        provenance: Provenance,
        readThirdPartyContent: Boolean,
        yolo: Boolean,
        fromLockscreen: Boolean = false,
    ): Decision = when {
        fromLockscreen -> Decision.QUEUE
        yolo -> Decision.RUN
        effect == ToolEffect.SENSITIVE -> Decision.QUEUE
        readThirdPartyContent || provenance != Provenance.USER -> Decision.QUEUE
        else -> Decision.RUN
    }

    /** The pending-approval source of a launcher turn; its card says [SOURCE_LABEL]. */
    const val SOURCE = "launcher"
    const val SOURCE_LABEL = "You, on the home screen"

    /** The source of a lock-screen turn (SystemUI's). */
    const val LOCKSCREEN_SOURCE = "lockscreen"
    const val LOCKSCREEN_SOURCE_LABEL = "The lock screen"

    /**
     * How the `onToolResult` summary for a queued call starts, exactly: the launcher refreshes its
     * cards when it sees it, so the approval shows up during the turn.
     */
    const val AWAITING_SUMMARY_PREFIX = "Waiting for your approval"

    fun awaitingSummary(title: String): String = "$AWAITING_SUMMARY_PREFIX: $title"

    /** The summary when the call needed approval and could not be queued (the queue is full). */
    fun notQueuedSummary(title: String): String = "Not run: $title needs your approval, and it couldn't be queued"

    /** What the model is told about a call now waiting for the user's approval. */
    const val QUEUED_FOR_MODEL =
        "Not run yet: this needs the user's approval. The exact call is now waiting for it in this " +
            "conversation, as an approval card; approving it there runs it once. Do not call it again " +
            "in this turn and do not try another way. Tell the user it is waiting for their approval."

    /** What the model is told when a call needed approval and could not even be queued. */
    const val NOT_QUEUED_FOR_MODEL =
        "Not run: this needs the user's approval, and it could not be queued for it (too many requests " +
            "may already be waiting). Do not try it again in this turn. Tell the user it needs their " +
            "approval and to deal with the requests already waiting first."

    /** Whether [message] is one of the texts above, i.e. the engine echoing a refusal already reported. */
    fun isRefusalMessage(message: String): Boolean =
        message == QUEUED_FOR_MODEL || message == NOT_QUEUED_FOR_MODEL
}
