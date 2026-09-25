package org.ethereumphone.andyclaw.skills.builtin

/**
 * Which run each display recording belongs to, and whether it was ended early.
 *
 * A sub-agent compiles the session it just drove into a flow, and a flow is replayed later with
 * the user's authority. So only a whole recording made by that run may compile: one another run
 * started, or one that was ended while its run was still working — its last steps, the send
 * among them, never recorded — would compile into a flow that does something else.
 */
object RecordingSessions {

    private val owners = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private val cut: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun started(sessionId: Long, runId: String?) {
        if (runId != null) owners[sessionId] = runId
        if (owners.size > MAX_REMEMBERED) owners.keys.minOrNull()?.let { owners.remove(it); cut.remove(it) }
    }

    /** [sessionId] was ended by anything but its own run finishing. */
    fun cutShort(sessionId: Long) {
        cut += sessionId
    }

    fun compilable(sessionId: Long, runId: String): Boolean = owners[sessionId] == runId && sessionId !in cut

    private const val MAX_REMEMBERED = 32
}
