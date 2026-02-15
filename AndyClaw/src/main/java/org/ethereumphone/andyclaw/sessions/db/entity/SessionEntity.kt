package org.ethereumphone.andyclaw.sessions.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the `sessions` table.
 *
 * Stores metadata about each chat session. Messages are stored in
 * [SessionMessageEntity] and linked via [id] ↔ [SessionMessageEntity.sessionId].
 *
 * Indexed on [agentId] for fast per-agent listing and on [updatedAt]
 * for pruning/capping sorted by recency.
 */
@Entity(
    tableName = "sessions",
    indices = [
        Index("agentId"),
        Index("updatedAt"),
    ],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val title: String,
    val model: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val sessionKey: String? = null,
    val label: String? = null,
    val thinkingLevel: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
    val isAborted: Boolean = false,
)
