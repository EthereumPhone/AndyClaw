package org.ethereumphone.andyclaw.ledger

import org.ethereumphone.andyclaw.frames.FrameRef
import org.ethereumphone.andyclaw.frames.SessionFrameStore

/** One session, assembled for playback: what was asked, what was done, what it looked like. */
data class ReplaySession(
    val sessionId: String,
    /** What the run was asked to do, taken from its turn row. */
    val intent: String,
    val startedMs: Long,
    val endedMs: Long,
    /** Every row of the session, oldest first — the turn and the steps under it. */
    val entries: List<LedgerEntry>,
    /** Frames still on disk, in capture order. */
    val frames: List<FrameRef>,
    /**
     * Frames the ledger names that storage no longer holds.
     *
     * Not an error and not something to hide. Retention evicts whole recordings while the
     * rows that named them stay — the ledger is append-only and the frame store is not — so
     * a replay has to be able to say "this part is gone" rather than quietly playing a
     * shorter version of what happened.
     */
    val missingFrames: List<String>,
) {
    val isEmpty: Boolean get() = entries.isEmpty() && frames.isEmpty()
    val steps: List<LedgerEntry> get() = entries.filter { it.kind == LedgerKind.TOOL }
    val turn: LedgerEntry? get() = entries.firstOrNull { it.kind == LedgerKind.TURN }
}

/**
 * "Watch exactly what I did as you."
 *
 * The two halves of the record are written by different components that know nothing about
 * each other — the engine writes rows as tools run, and `LauncherBindingService` writes
 * frames as they are captured — and they join on the session id alone. This is the join,
 * and having it in one place is what makes "a completed display session can be replayed
 * frame-by-frame from storage" a thing that is true rather than a thing that could be
 * assembled.
 *
 * Rendering it is Phase 4's. This produces the sequence; nothing here draws anything.
 */
class SessionReplay(
    private val ledger: LedgerRepository,
    private val frames: SessionFrameStore,
) {

    suspend fun of(sessionId: String): ReplaySession {
        val entries = ledger.session(sessionId)
        val onDisk = frames.frames(sessionId)
        val byId = onDisk.associateBy { it.id }

        // What the ledger says was captured, in the order the rows recorded it.
        val named = entries.flatMap { it.frames }
        val missing = named.filter { it !in byId }

        return ReplaySession(
            sessionId = sessionId,
            intent = entries.firstOrNull { it.kind == LedgerKind.TURN }?.intent
                ?: entries.firstOrNull()?.intent
                ?: "",
            startedMs = entries.minOfOrNull { it.ts } ?: onDisk.minOfOrNull { it.timestampMs } ?: 0L,
            endedMs = entries.maxOfOrNull { it.ts } ?: onDisk.maxOfOrNull { it.timestampMs } ?: 0L,
            entries = entries,
            frames = onDisk,
            missingFrames = missing,
        )
    }

    /** The bytes of one frame, for a viewer that is stepping through. */
    fun frameBytes(frameId: String): ByteArray? = frames.read(frameId)
}
