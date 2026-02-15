package org.ethereumphone.andyclaw.memory.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Many-to-many join between [MemoryEntryEntity] and [MemoryTagEntity].
 */
@Entity(
    tableName = "memory_entry_tags",
    primaryKeys = ["memoryId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MemoryTagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tagId")],
)
data class MemoryEntryTagCrossRef(
    val memoryId: String,
    val tagId: Long,
)
