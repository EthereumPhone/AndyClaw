package org.ethereumphone.andyclaw.memory.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A text chunk carved from a [MemoryEntryEntity].
 *
 * Chunks are the unit of indexing: each chunk gets an optional embedding
 * vector and is registered in the FTS index for keyword search.
 *
 * Uses an auto-generated [rowId] so the companion [MemoryChunkFts] table
 * can reference it via SQLite's implicit rowid.
 */
@Entity(
    tableName = "memory_chunks",
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("memoryId"),
        Index("chunkUuid", unique = true),
        Index("hash"),
    ],
)
data class MemoryChunkEntity(
    /** Auto-generated integer PK required for FTS4 content-sync. */
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0L,

    /** External UUID for API-level references. */
    val chunkUuid: String,

    /** FK → [MemoryEntryEntity.id]. */
    val memoryId: String,

    /** The chunk's text content. */
    val text: String,

    /** Character offset of the first char within the parent content. */
    val startOffset: Int,

    /** Character offset past the last char within the parent content. */
    val endOffset: Int,

    /** SHA-256 of [text] for change detection. */
    val hash: String,

    /** Embedding model name (null if not yet embedded). */
    val embeddingModel: String? = null,

    /**
     * Serialised embedding vector (FloatArray → ByteArray via [Converters]).
     * Null until an [EmbeddingProvider] populates it.
     */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray? = null,

    /** Epoch millis – last update. */
    val updatedAt: Long,
) {
    // equals / hashCode must account for ByteArray identity
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryChunkEntity) return false
        return rowId == other.rowId &&
            chunkUuid == other.chunkUuid &&
            memoryId == other.memoryId &&
            text == other.text &&
            startOffset == other.startOffset &&
            endOffset == other.endOffset &&
            hash == other.hash &&
            embeddingModel == other.embeddingModel &&
            embedding.contentEquals(other.embedding) &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = rowId.hashCode()
        result = 31 * result + chunkUuid.hashCode()
        result = 31 * result + memoryId.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + startOffset
        result = 31 * result + endOffset
        result = 31 * result + hash.hashCode()
        result = 31 * result + (embeddingModel?.hashCode() ?: 0)
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
