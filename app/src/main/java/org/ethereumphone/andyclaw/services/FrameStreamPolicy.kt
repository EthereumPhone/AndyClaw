package org.ethereumphone.andyclaw.services

import android.os.DeadObjectException
import org.ethereumphone.andyclaw.autopilot.AutopilotEvent

/**
 * How the agent-display preview is streamed to the launcher (IPC-07).
 *
 * - The stream ends only when the launcher is gone. Any `RemoteException` from the oneway
 *   `onDisplayFrame` used to end it for the rest of the turn, and most of those are not a dead
 *   launcher but a full async buffer (`TransactionTooLargeException` behind a big tool result):
 *   the card kept ticking over a frozen picture.
 * - It runs at the autopilot's rate only while an autopilot or flow run is on the display; once
 *   the run is DONE or FAILED the model is thinking or talking, and one frame a second is plenty.
 */
internal object FrameStreamPolicy {

    /** One a second: the rate the stream has always had outside an autopilot run. */
    const val IDLE_INTERVAL_MS = 1000L

    /** While a run drives the display the preview is the show: ~5 fps. */
    const val RUN_INTERVAL_MS = 200L

    /** Whether a failed frame means the launcher is gone for good, not just busy. */
    fun launcherGone(error: Throwable, binderAlive: Boolean): Boolean =
        error is DeadObjectException || !binderAlive

    /** The frame interval an autopilot event asks for, or null to leave it as it is. */
    fun intervalAfter(kind: AutopilotEvent.Kind): Long? = when (kind) {
        AutopilotEvent.Kind.STARTED -> RUN_INTERVAL_MS
        AutopilotEvent.Kind.DONE, AutopilotEvent.Kind.FAILED -> IDLE_INTERVAL_MS
        else -> null
    }
}
