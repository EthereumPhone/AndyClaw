package org.ethereumphone.andyclaw.sessions.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the `session_messages` table.
 *
 * Each message belongs to exactly one [SessionEntity]; cascade-deleting
 * a session removes all its messages automatically.
 *
 * Messages are ordered by [orderIndex] within a session.
 */
@Entity(
    tableName = "session_messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index(value = ["sessionId", "orderIndex"]),
    ],
)
data class SessionMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    /** Stored as the [MessageRole] enum name (uppercase). */
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val timestamp: Long,
    val orderIndex: Int,
)
