package org.ethereumphone.andyclaw.sessions

import android.content.Context
import kotlinx.coroutines.flow.Flow
import org.ethereumphone.andyclaw.sessions.db.SessionDatabase
import org.ethereumphone.andyclaw.sessions.model.MessageRole
import org.ethereumphone.andyclaw.sessions.model.Session
import org.ethereumphone.andyclaw.sessions.model.SessionMessage
import org.ethereumphone.andyclaw.sessions.model.SessionPatch

/**
 * Public entry-point for the AndyClaw session subsystem.
 *
 * Wraps [SessionRepository] (persistence), [SessionMaintenance] (pruning/capping),
 * and [SessionKey] (routing) behind a concise, Android-friendly API.
 *
 * All operations are scoped to [agentId] for multi-agent isolation,
 * mirroring OpenClaw's per-agent session model.
 *
 * Usage:
 * ```kotlin
 * val sessions = SessionManager(context, agentId = "main")
 *
 * // Create & send
 * val session = sessions.createSession(model = "claude-sonnet-4-20250514")
 * sessions.addMessage(session.id, MessageRole.USER, "Hello!")
 *
 * // Observe
 * sessions.observeSessions().collect { list -> updateUi(list) }
 *
 * // Maintenance (e.g. on app launch)
 * sessions.runMaintenance()
 * ```
 *
 * @param context  Android context (application context is extracted internally).
 * @param agentId  Logical agent ID. Sessions are isolated per agent.
 * @param maintenanceConfig  Optional override for pruning/capping thresholds.
 */
class SessionManager(
    context: Context,
    private val agentId: String = SessionKey.DEFAULT_AGENT_ID,
    maintenanceConfig: SessionMaintenance.Config = SessionMaintenance.Config(),
) {
    private val database: SessionDatabase = SessionDatabase.getInstance(context)
    private val dao = database.sessionDao()
    private val repository = SessionRepository(dao)
    private val maintenance = SessionMaintenance(dao, maintenanceConfig)

    // ── Sessions: Create ─────────────────────────────────────────────

    /**
     * Create a new chat session.
     *
     * A unique session key is generated automatically.
     *
     * @param model  LLM model identifier (e.g. "claude-sonnet-4-20250514").
     * @param title  Initial title; auto-generated from first message if left as default.
     * @return The newly created [Session].
     */
    suspend fun createSession(
        model: String? = null,
        title: String = "New Chat",
    ): Session {
        return repository.createSession(agentId, model, title)
    }

    // ── Sessions: Read ───────────────────────────────────────────────

    /**
     * Retrieve a single session by ID, or null.
     */
    suspend fun getSession(sessionId: String): Session? {
        return repository.getSession(sessionId)
    }

    /**
     * Reactive [Flow] of all sessions for this agent, newest first.
     */
    fun observeSessions(): Flow<List<Session>> {
        return repository.observeSessions(agentId)
    }

    /**
     * Reactive [Flow] of all sessions across all agents, newest first.
     */
    fun observeAllSessions(): Flow<List<Session>> {
        return repository.observeAllSessions()
    }

    /**
     * One-shot list of all sessions for this agent.
     */
    suspend fun getSessions(): List<Session> {
        return repository.getSessionsByAgent(agentId)
    }

    // ── Sessions: Update ─────────────────────────────────────────────

    /**
     * Update the title of an existing session.
     */
    suspend fun updateSessionTitle(sessionId: String, title: String) {
        repository.updateSessionTitle(sessionId, title)
    }

    /**
     * Apply a partial update to a session.
     *
     * Only non-null fields in [patch] are written.
     *
     * @return Updated [Session] or null if not found.
     */
    suspend fun patchSession(sessionId: String, patch: SessionPatch): Session? {
        return repository.patchSession(sessionId, patch)
    }

    /**
     * Increment token usage counters after an agent run.
     */
    suspend fun addTokenUsage(
        sessionId: String,
        inputDelta: Int,
        outputDelta: Int,
        totalDelta: Int,
    ) {
        repository.addTokenUsage(sessionId, inputDelta, outputDelta, totalDelta)
    }

    // ── Sessions: Delete / Reset ─────────────────────────────────────

    /**
     * Delete a session and all its messages.
     */
    suspend fun deleteSession(sessionId: String) {
        repository.deleteSession(sessionId)
    }

    /**
     * Reset a session: clear messages and token counters, keeping settings.
     *
     * Equivalent to OpenClaw's `/new` or `sessions.reset`.
     *
     * @return Updated [Session] or null if not found.
     */
    suspend fun resetSession(sessionId: String): Session? {
        return repository.resetSession(sessionId)
    }

    // ── Messages ─────────────────────────────────────────────────────

    /**
     * Append a message to a session's transcript.
     *
     * @return The persisted [SessionMessage].
     */
    suspend fun addMessage(
        sessionId: String,
        role: MessageRole,
        content: String,
        toolName: String? = null,
        toolCallId: String? = null,
    ): SessionMessage {
        return repository.addMessage(sessionId, role, content, toolName, toolCallId)
    }

    /**
     * Reactive [Flow] of messages in a session, ordered chronologically.
     */
    fun observeMessages(sessionId: String): Flow<List<SessionMessage>> {
        return repository.observeMessages(sessionId)
    }

    /**
     * One-shot list of messages in a session.
     */
    suspend fun getMessages(sessionId: String): List<SessionMessage> {
        return repository.getMessages(sessionId)
    }

    /**
     * Number of messages in a session.
     */
    suspend fun getMessageCount(sessionId: String): Int {
        return repository.getMessageCount(sessionId)
    }

    // ── Maintenance ──────────────────────────────────────────────────

    /**
     * Run session maintenance: prune stale sessions, then cap total count.
     *
     * Safe to call frequently; no-ops if nothing needs cleanup.
     *
     * @return Summary of what was removed.
     */
    suspend fun runMaintenance(): SessionMaintenance.MaintenanceResult {
        return maintenance.run(agentId)
    }

    /**
     * Remove sessions older than the configured threshold.
     *
     * @return Number of sessions removed.
     */
    suspend fun pruneStale(): Int {
        return maintenance.pruneStale(agentId)
    }

    /**
     * Cap session count to the configured maximum.
     *
     * @return Number of sessions evicted.
     */
    suspend fun capSessions(): Int {
        return maintenance.capEntries(agentId)
    }

    // ── Session key helpers ──────────────────────────────────────────

    /**
     * Build the canonical main session key for this agent.
     */
    fun mainSessionKey(): String {
        return SessionKey.buildMainSessionKey(agentId)
    }

    /**
     * The normalised agent ID for this manager instance.
     */
    val normalizedAgentId: String
        get() = SessionKey.normalizeAgentId(agentId)

    // ── Lifecycle ────────────────────────────────────────────────────

    /**
     * Close the underlying database.
     *
     * Typically only needed in tests; in production the singleton
     * database lives for the application's lifetime.
     */
    fun close() {
        database.close()
    }
}
