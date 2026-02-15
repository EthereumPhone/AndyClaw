package org.ethereumphone.andyclaw.sessions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.ethereumphone.andyclaw.sessions.db.SessionDao
import org.ethereumphone.andyclaw.sessions.db.entity.SessionEntity
import org.ethereumphone.andyclaw.sessions.db.entity.SessionMessageEntity
import org.ethereumphone.andyclaw.sessions.model.MessageRole
import org.ethereumphone.andyclaw.sessions.model.Session
import org.ethereumphone.andyclaw.sessions.model.SessionMessage
import org.ethereumphone.andyclaw.sessions.model.SessionPatch
import java.util.UUID

/**
 * Repository for the session subsystem.
 *
 * Orchestrates DAO operations, entity ↔ domain mapping, and token
 * accounting. All public methods are suspend-safe and dispatch IO
 * work to [Dispatchers.IO].
 */
class SessionRepository(
    private val dao: SessionDao,
) {

    // ── Sessions: Create ─────────────────────────────────────────────

    /**
     * Create a new chat session for the given agent.
     *
     * Generates a session key automatically using [SessionKey.buildMainSessionKey].
     */
    suspend fun createSession(
        agentId: String,
        model: String? = null,
        title: String = "New Chat",
    ): Session = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val sessionKey = SessionKey.buildMainSessionKey(agentId, id)
        val entity = SessionEntity(
            id = id,
            agentId = agentId,
            title = title,
            model = model,
            createdAt = now,
            updatedAt = now,
            sessionKey = sessionKey,
        )
        dao.insertSession(entity)
        entity.toDomain()
    }

    // ── Sessions: Read ───────────────────────────────────────────────

    suspend fun getSession(sessionId: String): Session? = withContext(Dispatchers.IO) {
        dao.getSession(sessionId)?.toDomain()
    }

    /**
     * Reactive stream of all sessions for an agent, newest first.
     */
    fun observeSessions(agentId: String): Flow<List<Session>> {
        return dao.observeSessionsByAgent(agentId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    /**
     * Reactive stream of all sessions across all agents, newest first.
     */
    fun observeAllSessions(): Flow<List<Session>> {
        return dao.observeAllSessions().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    /**
     * One-shot list of all sessions for an agent.
     */
    suspend fun getSessionsByAgent(agentId: String): List<Session> = withContext(Dispatchers.IO) {
        dao.getSessionsByAgent(agentId).map { it.toDomain() }
    }

    // ── Sessions: Update ─────────────────────────────────────────────

    suspend fun updateSessionTitle(sessionId: String, title: String) = withContext(Dispatchers.IO) {
        dao.updateSessionTitle(sessionId, title, System.currentTimeMillis())
    }

    /**
     * Apply a partial update to a session.
     *
     * Only non-null fields in [patch] are written. Token fields in
     * [SessionPatch] are **set** (not added); use [addTokenUsage] for
     * incremental accounting.
     *
     * @return Updated [Session] or null if not found.
     */
    suspend fun patchSession(sessionId: String, patch: SessionPatch): Session? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // Apply title separately since COALESCE doesn't work for non-nullable columns
        // that the caller might explicitly want to change.
        if (patch.title != null) {
            dao.updateSessionTitle(sessionId, patch.title, now)
        }

        dao.patchSession(
            sessionId = sessionId,
            model = patch.model,
            label = patch.label,
            sessionKey = patch.sessionKey,
            thinkingLevel = patch.thinkingLevel,
            isAborted = patch.isAborted,
            updatedAt = now,
        )

        // Set absolute token values if provided.
        if (patch.inputTokens != null || patch.outputTokens != null || patch.totalTokens != null) {
            val existing = dao.getSession(sessionId) ?: return@withContext null
            dao.setTokenUsage(
                sessionId = sessionId,
                inputTokens = patch.inputTokens ?: existing.inputTokens,
                outputTokens = patch.outputTokens ?: existing.outputTokens,
                totalTokens = patch.totalTokens ?: existing.totalTokens,
                updatedAt = now,
            )
        }

        dao.getSession(sessionId)?.toDomain()
    }

    /**
     * Increment token usage counters for a session.
     *
     * Use this at the end of an agent run to track cumulative consumption.
     */
    suspend fun addTokenUsage(
        sessionId: String,
        inputDelta: Int,
        outputDelta: Int,
        totalDelta: Int,
    ) = withContext(Dispatchers.IO) {
        dao.addTokenUsage(sessionId, inputDelta, outputDelta, totalDelta, System.currentTimeMillis())
    }

    // ── Sessions: Delete / Reset ─────────────────────────────────────

    /**
     * Delete a session and all its messages (cascade).
     */
    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        dao.deleteSession(sessionId)
    }

    /**
     * Reset a session: clear all messages and token counters while
     * keeping the session shell (title, model, key) intact.
     *
     * Equivalent to OpenClaw's `sessions.reset` (new sessionId with
     * preserved settings). Here we keep the same ID but wipe the transcript.
     *
     * @return Updated [Session] or null if not found.
     */
    suspend fun resetSession(sessionId: String): Session? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.resetSessionTransactional(sessionId, now)
        dao.getSession(sessionId)?.toDomain()
    }

    // ── Messages: Create ─────────────────────────────────────────────

    /**
     * Append a message to a session's transcript.
     *
     * Automatically assigns the next [orderIndex] and bumps the
     * session's [updatedAt].
     *
     * @return The persisted [SessionMessage].
     */
    suspend fun addMessage(
        sessionId: String,
        role: MessageRole,
        content: String,
        toolName: String? = null,
        toolCallId: String? = null,
    ): SessionMessage = withContext(Dispatchers.IO) {
        val orderIndex = dao.getMessageCount(sessionId)
        val now = System.currentTimeMillis()
        val entity = SessionMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = role.name,
            content = content,
            toolName = toolName,
            toolCallId = toolCallId,
            timestamp = now,
            orderIndex = orderIndex,
        )
        dao.insertMessage(entity)
        dao.updateSessionTimestamp(sessionId, now)
        entity.toDomain()
    }

    // ── Messages: Read ───────────────────────────────────────────────

    /**
     * Reactive stream of messages for a session, ordered by [orderIndex].
     */
    fun observeMessages(sessionId: String): Flow<List<SessionMessage>> {
        return dao.observeMessages(sessionId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    /**
     * One-shot list of messages for a session.
     */
    suspend fun getMessages(sessionId: String): List<SessionMessage> = withContext(Dispatchers.IO) {
        dao.getMessages(sessionId).map { it.toDomain() }
    }

    /**
     * Number of messages in a session.
     */
    suspend fun getMessageCount(sessionId: String): Int = withContext(Dispatchers.IO) {
        dao.getMessageCount(sessionId)
    }

    // ── Entity ↔ Domain mapping ──────────────────────────────────────

    private fun SessionEntity.toDomain() = Session(
        id = id,
        agentId = agentId,
        title = title,
        model = model,
        createdAt = createdAt,
        updatedAt = updatedAt,
        sessionKey = sessionKey,
        label = label,
        thinkingLevel = thinkingLevel,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
        isAborted = isAborted,
    )

    private fun SessionMessageEntity.toDomain() = SessionMessage(
        id = id,
        sessionId = sessionId,
        role = MessageRole.fromString(role),
        content = content,
        toolName = toolName,
        toolCallId = toolCallId,
        timestamp = timestamp,
        orderIndex = orderIndex,
    )
}
