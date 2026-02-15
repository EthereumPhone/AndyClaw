package org.ethereumphone.andyclaw.memory.db.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/**
 * A [MemoryEntryEntity] with its associated [MemoryTagEntity] list.
 */
data class MemoryWithTags(
    @Embedded val entry: MemoryEntryEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = MemoryEntryTagCrossRef::class,
            parentColumn = "memoryId",
            entityColumn = "tagId",
        ),
    )
    val tags: List<MemoryTagEntity>,
)

/**
 * A [MemoryEntryEntity] with all of its [MemoryChunkEntity] rows.
 */
data class MemoryWithChunks(
    @Embedded val entry: MemoryEntryEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "memoryId",
    )
    val chunks: List<MemoryChunkEntity>,
)
