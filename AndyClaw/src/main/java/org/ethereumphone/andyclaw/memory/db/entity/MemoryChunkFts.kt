package org.ethereumphone.andyclaw.memory.db.entity

import androidx.room.Entity
import androidx.room.Fts4

/**
 * FTS4 virtual table that mirrors the [text] column of [MemoryChunkEntity].
 *
 * Using `contentEntity` keeps this table in external-content mode:
 * the actual text lives in [memory_chunks] and the FTS index
 * references it via rowid. After bulk modifications call
 * [MemoryDatabase.rebuildFtsIndex] to re-sync.
 */
@Fts4(contentEntity = MemoryChunkEntity::class)
@Entity(tableName = "memory_chunks_fts")
data class MemoryChunkFts(
    /** Indexed text – must match the column name in the content entity. */
    val text: String,
)
