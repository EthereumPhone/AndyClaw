package org.ethereumphone.andyclaw.memory.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A reusable tag that can be associated with many memories.
 */
@Entity(
    tableName = "memory_tags",
    indices = [Index("name", unique = true)],
)
data class MemoryTagEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** Human-readable tag name (lowercase, trimmed). */
    val name: String,
)
