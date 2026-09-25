package org.ethereumphone.andyclaw.skills.builtin

import kotlinx.coroutines.Job
import org.ethereumphone.andyclaw.agent.AgentRunToken
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

    /** Told when a run takes the display and when it gives it back (the recording). */
    interface Listener {
        fun onClaimed(token: AgentRunToken)
        fun onReleased(runId: String)
    }

    @Volatile var listener: Listener? = null

    private class Owner(val token: String, val job: Job?)

    @Volatile private var owner: Owner? = null

    /** Successful claims so far; lets the prewarm tell whether anything used its display. */
    @Volatile var claims = 0L
        private set

    /**
     * Set while a display is being put away ([releaseAfter], [parkIfUnclaimed]). A claim meanwhile
     * waits: granted at once, it would be handed a display that is then parked under it.
     */
    @Volatile private var parking: String? = null

    private enum class Claim { FRESH, AGAIN, REFUSED }

    /**
     * Claims the display for [token]. False while another *live* run holds it, while it is being
     * put away, and for a run that is itself over — a cancelled run's code can outlive it
     * (`execute_code`), and taking the display back after its own end released it would leave a
     * live display nothing ever puts away.
     */
    fun claim(token: String, job: Job?): Boolean = claimInternal(token, job) != Claim.REFUSED

    @Synchronized
    private fun claimInternal(token: String, job: Job?): Claim {
        if (job != null && !job.isActive) return Claim.REFUSED
        if (parking != null) return Claim.REFUSED
        val o = owner
        return when {
            o != null && o.token == token -> { claims++; Claim.AGAIN }
            o == null || o.job?.isActive != true -> { owner = Owner(token, job); claims++; Claim.FRESH }
            else -> Claim.REFUSED
        }
    }

    /** Ends [token]'s lease. True when it was the owner, so the caller puts the display away. */
    fun release(token: String): Boolean {
        val released = synchronized(this) { (owner?.token == token).also { if (it) owner = null } }
        if (released) runCatching { listener?.onReleased(token) }
        return released
    }

    /**
     * Puts [token]'s display away with [putAway] and ends its lease, as one step for anyone
     * claiming: releasing first let another run claim in between and have the display parked
     * under it. True when [token] was the owner.
     */
    fun releaseAfter(token: String, putAway: () -> Unit): Boolean {
        synchronized(this) {
            if (owner?.token != token) return false
            parking = token
        }
        try {
            putAway()
        } finally {
            synchronized(this) {
                parking = null
                if (owner?.token == token) owner = null
            }
            runCatching { listener?.onReleased(token) }
        }
        return true
    }

    /** Parks a prewarmed display with [putAway] only if nothing has claimed it since [claimsBefore]. */
    fun parkIfUnclaimed(claimsBefore: Long, putAway: () -> Unit): Boolean {
        synchronized(this) {
            if (claims != claimsBefore || owner?.job?.isActive == true || parking != null) return false
            parking = PREWARM
        }
        try {
            putAway()
        } finally {
            synchronized(this) { parking = null }
        }
        return true
    }

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
        // A display being put away is not free yet: wait for that to finish, briefly.
        var waited = 0L
        while (parking != null && waited < PARK_WAIT_MS) {
            kotlinx.coroutines.delay(PARK_POLL_MS)
            waited += PARK_POLL_MS
        }
        val token = currentRunToken() ?: return !isHeld() && parking == null
        // Whether this is the run's first claim is decided under the lock, with the claim: two
        // of its calls claiming at once each used to see "first" and start a recording apiece.
        return when (claimInternal(token.id, token.job)) {
            Claim.FRESH -> {
                runCatching { listener?.onClaimed(token) }
                true
            }
            Claim.AGAIN -> true
            Claim.REFUSED -> false
        }
    }

    const val BUSY = "The agent display is busy with another task right now. Try again when it is done."
    const val STOPPED = "Stopped by the user. Do not use the agent display again in this turn."

    private const val MAX_REMEMBERED_STOPS = 16
    private const val PREWARM = "prewarm"
    private const val PARK_WAIT_MS = 2_000L
    private const val PARK_POLL_MS = 25L
}
