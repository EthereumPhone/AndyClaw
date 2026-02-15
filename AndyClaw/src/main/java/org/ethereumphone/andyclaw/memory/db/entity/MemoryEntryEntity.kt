package org.ethereumphone.andyclaw.memory.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a single long-term memory.
 *
 * Each row is one logical "memory" that an agent stores.
 * Content is indexed via [MemoryChunkEntity] for search.
 */
@Entity(
    tableName = "memory_entries",
    indices = [
        Index("agentId"),
        Index("source"),
        Index("hash", unique = true),
    ],
)
data class MemoryEntryEntity(
    /** UUID primary key. */
    @PrimaryKey
    val id: String,

    /** Owning agent identifier. */
    val agentId: String,

    /** Full text content. */
    val content: String,

    /** How the memory was created (MANUAL / CONVERSATION / SYSTEM). */
    val source: String,

    /** Importance weight [0, 1]. */
    val importance: Float = 0.5f,

    /** SHA-256 of [content], used for deduplication. */
    val hash: String,

    /** Epoch millis – creation time. */
    val createdAt: Long,

    /** Epoch millis – last content update. */
    val updatedAt: Long,

    /** Epoch millis – last time surfaced in search results. */
    @ColumnInfo(defaultValue = "0")
    val accessedAt: Long = 0L,

    /** Number of times this memory was surfaced. */
    @ColumnInfo(defaultValue = "0")
    val accessCount: Int = 0,
)
