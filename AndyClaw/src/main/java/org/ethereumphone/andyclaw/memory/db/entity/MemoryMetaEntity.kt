package org.ethereumphone.andyclaw.memory.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Simple key-value metadata store (index version, embedding model, etc.).
 *
 * Mirrors OpenClaw's `meta` table.
 */
@Entity(tableName = "memory_meta")
data class MemoryMetaEntity(
    @PrimaryKey
    val key: String,

    val value: String,
)
