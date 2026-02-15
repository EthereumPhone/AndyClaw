package org.ethereumphone.andyclaw.sessions.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.ethereumphone.andyclaw.sessions.db.entity.SessionEntity
import org.ethereumphone.andyclaw.sessions.db.entity.SessionMessageEntity

/**
 * Data-access object for the session subsystem.
 *
 * Covers CRUD for sessions and messages, maintenance queries
 * (pruning, capping), and reactive observation via Flow.
 */
@Dao
interface SessionDao {

    // ── Sessions ─────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE agentId = :agentId ORDER BY updatedAt DESC")
    fun observeSessionsByAgent(agentId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE agentId = :agentId ORDER BY updatedAt DESC")
    suspend fun getSessionsByAgent(agentId: String): List<SessionEntity>

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    suspend fun getAllSessions(): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("UPDATE sessions SET title = :title, updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateSessionTitle(sessionId: String, title: String, updatedAt: Long)

    @Query("UPDATE sessions SET updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateSessionTimestamp(sessionId: String, updatedAt: Long)

    @Query(
        """
        UPDATE sessions SET
            model = COALESCE(:model, model),
            label = COALESCE(:label, label),
            sessionKey = COALESCE(:sessionKey, sessionKey),
            thinkingLevel = COALESCE(:thinkingLevel, thinkingLevel),
            isAborted = COALESCE(:isAborted, isAborted),
            updatedAt = :updatedAt
        WHERE id = :sessionId
        """
    )
    suspend fun patchSession(
        sessionId: String,
        model: String?,
        label: String?,
        sessionKey: String?,
        thinkingLevel: String?,
        isAborted: Boolean?,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE sessions SET
            inputTokens = inputTokens + :inputDelta,
            outputTokens = outputTokens + :outputDelta,
            totalTokens = totalTokens + :totalDelta,
            updatedAt = :updatedAt
        WHERE id = :sessionId
        """
    )
    suspend fun addTokenUsage(
        sessionId: String,
        inputDelta: Int,
        outputDelta: Int,
        totalDelta: Int,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE sessions SET
            inputTokens = :inputTokens,
            outputTokens = :outputTokens,
            totalTokens = :totalTokens,
            updatedAt = :updatedAt
        WHERE id = :sessionId
        """
    )
    suspend fun setTokenUsage(
        sessionId: String,
        inputTokens: Int,
        outputTokens: Int,
        totalTokens: Int,
        updatedAt: Long,
    )

    // ── Session reset (new sessionId-like reset: wipe messages, keep shell) ──

    @Query(
        """
        UPDATE sessions SET
            updatedAt = :updatedAt,
            inputTokens = 0,
            outputTokens = 0,
            totalTokens = 0,
            isAborted = 0
        WHERE id = :sessionId
        """
    )
    suspend fun resetSession(sessionId: String, updatedAt: Long)

    // ── Messages ─────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: SessionMessageEntity)

    @Query("SELECT * FROM session_messages WHERE sessionId = :sessionId ORDER BY orderIndex ASC")
    fun observeMessages(sessionId: String): Flow<List<SessionMessageEntity>>

    @Query("SELECT * FROM session_messages WHERE sessionId = :sessionId ORDER BY orderIndex ASC")
    suspend fun getMessages(sessionId: String): List<SessionMessageEntity>

    @Query("SELECT COUNT(*) FROM session_messages WHERE sessionId = :sessionId")
    suspend fun getMessageCount(sessionId: String): Int

    @Query("DELETE FROM session_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    // ── Maintenance ──────────────────────────────────────────────────

    /**
     * Count sessions for an agent.
     */
    @Query("SELECT COUNT(*) FROM sessions WHERE agentId = :agentId")
    suspend fun countSessionsByAgent(agentId: String): Int

    /**
     * Delete sessions older than the given cutoff timestamp.
     *
     * Messages are cascade-deleted automatically.
     *
     * @return The number of sessions removed.
     */
    @Query("DELETE FROM sessions WHERE agentId = :agentId AND updatedAt < :cutoffMs")
    suspend fun pruneStale(agentId: String, cutoffMs: Long): Int

    /**
     * IDs of sessions that should be evicted to cap the store at [maxEntries].
     *
     * Returns the IDs of the oldest sessions beyond the cap, ordered
     * by updatedAt ascending (oldest first).
     */
    @Query(
        """
        SELECT id FROM sessions
        WHERE agentId = :agentId
        ORDER BY updatedAt DESC
        LIMIT -1 OFFSET :maxEntries
        """
    )
    suspend fun getSessionIdsExceedingCap(agentId: String, maxEntries: Int): List<String>

    /**
     * Bulk-delete sessions by ID list.
     *
     * Messages are cascade-deleted.
     */
    @Query("DELETE FROM sessions WHERE id IN (:sessionIds)")
    suspend fun deleteSessionsByIds(sessionIds: List<String>)

    /**
     * Transactional reset: wipe messages and reset token counters,
     * keeping the session shell intact.
     */
    @Transaction
    suspend fun resetSessionTransactional(sessionId: String, updatedAt: Long) {
        deleteMessages(sessionId)
        resetSession(sessionId, updatedAt)
    }
}
