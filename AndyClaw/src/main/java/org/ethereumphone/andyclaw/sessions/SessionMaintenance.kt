package org.ethereumphone.andyclaw.sessions

import android.util.Log
import org.ethereumphone.andyclaw.sessions.db.SessionDao

/**
 * Session store maintenance: pruning stale sessions and capping total count.
 *
 * Mirrors OpenClaw's session-store maintenance logic (`store.ts`)
 * adapted for Android's Room-based persistence.
 *
 * Typical usage: run maintenance after writing a session, or on a periodic
 * schedule (e.g. once per app launch or once per day).
 *
 * ```kotlin
 * val maintenance = SessionMaintenance(dao)
 * maintenance.run(agentId = "main")
 * ```
 */
class SessionMaintenance(
    private val dao: SessionDao,
    private val config: Config = Config(),
) {

    /**
     * Configuration for session maintenance.
     *
     * Defaults align with OpenClaw's built-in values.
     */
    data class Config(
        /** Max age in millis before a session is pruned (default 30 days). */
        val pruneAfterMs: Long = DEFAULT_PRUNE_AFTER_MS,
        /** Maximum number of sessions to keep per agent (default 500). */
        val maxEntries: Int = DEFAULT_MAX_ENTRIES,
        /** Whether to actually delete or just log warnings (like OpenClaw's "warn" mode). */
        val mode: Mode = Mode.ENFORCE,
    )

    enum class Mode {
        /** Actually prune and cap. */
        ENFORCE,
        /** Only log warnings; don't delete anything. */
        WARN,
    }

    data class MaintenanceResult(
        val pruned: Int,
        val capped: Int,
    )

    /**
     * Run full maintenance for the given agent: prune stale, then cap.
     */
    suspend fun run(agentId: String): MaintenanceResult {
        val pruned = pruneStale(agentId)
        val capped = capEntries(agentId)
        return MaintenanceResult(pruned = pruned, capped = capped)
    }

    /**
     * Remove sessions whose [updatedAt] is older than the configured threshold.
     *
     * @return Number of sessions removed (0 if mode is [Mode.WARN]).
     */
    suspend fun pruneStale(agentId: String): Int {
        val cutoffMs = System.currentTimeMillis() - config.pruneAfterMs
        if (config.mode == Mode.WARN) {
            // In warn mode, just report what would be pruned.
            // We can't easily count without a separate query, so skip.
            return 0
        }
        val pruned = dao.pruneStale(agentId, cutoffMs)
        if (pruned > 0) {
            Log.i(TAG, "Pruned $pruned stale sessions (agentId=$agentId, maxAge=${config.pruneAfterMs}ms)")
        }
        return pruned
    }

    /**
     * Cap the session count to [Config.maxEntries] by evicting the oldest.
     *
     * @return Number of sessions removed (0 if already within cap or mode is [Mode.WARN]).
     */
    suspend fun capEntries(agentId: String): Int {
        val count = dao.countSessionsByAgent(agentId)
        if (count <= config.maxEntries) return 0

        if (config.mode == Mode.WARN) {
            Log.w(TAG, "Session count ($count) exceeds cap (${config.maxEntries}) for agentId=$agentId")
            return 0
        }

        val toEvict = dao.getSessionIdsExceedingCap(agentId, config.maxEntries)
        if (toEvict.isEmpty()) return 0

        // Bulk delete in chunks of 100 to stay within SQLite variable limits.
        toEvict.chunked(100).forEach { chunk ->
            dao.deleteSessionsByIds(chunk)
        }

        Log.i(TAG, "Capped sessions: removed ${toEvict.size} (agentId=$agentId, max=${config.maxEntries})")
        return toEvict.size
    }

    companion object {
        private const val TAG = "SessionMaintenance"
        /** 30 days in milliseconds. */
        const val DEFAULT_PRUNE_AFTER_MS = 30L * 24 * 60 * 60 * 1000
        /** Maximum sessions per agent. */
        const val DEFAULT_MAX_ENTRIES = 500
    }
}
