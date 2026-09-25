package org.ethereumphone.andyclaw.skills.builtin

import kotlinx.coroutines.Job
import org.ethereumphone.andyclaw.agent.currentRunToken

/**
 * One agent run drives the agent display at a time, and only that run puts it away.
 *
 * The display is one screen shared by every run in the process. Without an owner, the end of
 * any run tore it down: a heartbeat set off by a notification, finishing while an autopilot was
 * halfway through the user's task, parked the display and took the app with it. Now a run claims
 * the display with its first display tool call and gives it back when it ends
 * ([AgentDisplaySkill.onRunFinished]), and a second run asking in the meantime is told the
 * display is busy.
 *
 * Ownership follows the owner's [Job]: a run that was cancelled or has finished never blocks
 * the next one, even while its own `finally` is still unwinding — cancelling a chat clears the
 * UI at once, and the user's next message must be able to take the display straight away.
 */
object AgentDisplayLease {

    private class Owner(val token: String, val job: Job?)

    @Volatile private var owner: Owner? = null

    /** Successful claims so far; lets the prewarm tell whether anything used its display. */
    @Volatile var claims = 0L
        private set

    /** Claims the display for [token]. False only while another *live* run holds it. */
    @Synchronized
    fun claim(token: String, job: Job?): Boolean {
        val o = owner
        if (o == null || o.token == token || o.job?.isActive != true) {
            owner = Owner(token, job)
            claims++
            return true
        }
        return false
    }

    /** Ends [token]'s lease. True when it was the owner, so the caller puts the display away. */
    @Synchronized
    fun release(token: String): Boolean = (owner?.token == token).also { if (it) owner = null }

    /** Whether a live run holds the display. */
    fun isHeld(): Boolean = owner?.job?.isActive == true

    fun isOwner(token: String): Boolean = owner?.token == token

    /** Runs that held the display when STOP was pressed, newest last. */
    private val stopped = ArrayDeque<String>()

    /**
     * STOP was pressed: it is meant for whatever is on the agent display, so it stops the run
     * holding it. A STOP while nothing holds the display stops no run — the OS latch still
     * drops any input already queued.
     */
    @Synchronized
    fun noteStop() {
        val o = owner ?: return
        if (o.job?.isActive == false) return
        if (o.token !in stopped) {
            stopped.addLast(o.token)
            while (stopped.size > MAX_REMEMBERED_STOPS) stopped.removeFirst()
        }
    }

    @Synchronized
    fun wasStopped(token: String): Boolean = token in stopped

    /**
     * Claims the display for the calling run. A caller outside any run may use the display only
     * while no live run holds it, and never becomes its owner.
     */
    suspend fun claimForCaller(): Boolean {
        val token = currentRunToken() ?: return !isHeld()
        return claim(token.id, token.job)
    }

    const val BUSY = "The agent display is busy with another task right now. Try again when it is done."
    const val STOPPED = "Stopped by the user. Do not use the agent display again in this turn."

    private const val MAX_REMEMBERED_STOPS = 16
}
