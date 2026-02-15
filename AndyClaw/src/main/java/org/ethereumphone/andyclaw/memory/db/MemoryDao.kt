package org.ethereumphone.andyclaw.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.ethereumphone.andyclaw.memory.db.entity.MemoryChunkEntity
import org.ethereumphone.andyclaw.memory.db.entity.MemoryEntryEntity
import org.ethereumphone.andyclaw.memory.db.entity.MemoryEntryTagCrossRef
import org.ethereumphone.andyclaw.memory.db.entity.MemoryMetaEntity
import org.ethereumphone.andyclaw.memory.db.entity.MemoryTagEntity
import org.ethereumphone.andyclaw.memory.db.entity.MemoryWithChunks
import org.ethereumphone.andyclaw.memory.db.entity.MemoryWithTags

/**
 * Data-access object for the memory subsystem.
 *
 * Covers CRUD for entries, chunks, tags, metadata,
 * and the FTS-backed keyword search.
 */
@Dao
interface MemoryDao {

    // ── Memory entries ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: MemoryEntryEntity)

    @Update
    suspend fun updateEntry(entry: MemoryEntryEntity)

    @Query("DELETE FROM memory_entries WHERE id = :memoryId")
    suspend fun deleteEntry(memoryId: String)

    @Query("SELECT * FROM memory_entries WHERE id = :memoryId")
    suspend fun getEntryById(memoryId: String): MemoryEntryEntity?

    @Query("SELECT * FROM memory_entries WHERE agentId = :agentId ORDER BY updatedAt DESC")
    suspend fun getEntriesByAgent(agentId: String): List<MemoryEntryEntity>

    @Query(
        """
        SELECT * FROM memory_entries
        WHERE agentId = :agentId AND source = :source
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getEntriesByAgentAndSource(
        agentId: String,
        source: String,
    ): List<MemoryEntryEntity>

    @Query("SELECT * FROM memory_entries WHERE hash = :hash LIMIT 1")
    suspend fun getEntryByHash(hash: String): MemoryEntryEntity?

    @Query(
        """
        SELECT * FROM memory_entries
        WHERE agentId = :agentId
        ORDER BY updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentEntries(agentId: String, limit: Int): List<MemoryEntryEntity>

    /** Reactive stream of all entries for an agent. */
    @Query("SELECT * FROM memory_entries WHERE agentId = :agentId ORDER BY updatedAt DESC")
    fun observeEntriesByAgent(agentId: String): Flow<List<MemoryEntryEntity>>

    @Query(
        """
        UPDATE memory_entries
        SET accessedAt = :accessedAt, accessCount = accessCount + 1
        WHERE id = :memoryId
        """
    )
    suspend fun recordAccess(memoryId: String, accessedAt: Long)

    // ── Memory entries with tags (relations) ────────────────────────

    @Transaction
    @Query("SELECT * FROM memory_entries WHERE id = :memoryId")
    suspend fun getEntryWithTags(memoryId: String): MemoryWithTags?

    @Transaction
    @Query("SELECT * FROM memory_entries WHERE agentId = :agentId ORDER BY updatedAt DESC")
    suspend fun getEntriesWithTagsByAgent(agentId: String): List<MemoryWithTags>

    @Transaction
    @Query(
        """
        SELECT * FROM memory_entries
        WHERE agentId = :agentId
        ORDER BY updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentEntriesWithTags(agentId: String, limit: Int): List<MemoryWithTags>

    @Transaction
    @Query("SELECT * FROM memory_entries WHERE id = :memoryId")
    suspend fun getEntryWithChunks(memoryId: String): MemoryWithChunks?

    // ── Chunks ──────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: MemoryChunkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<MemoryChunkEntity>): List<Long>

    @Query("DELETE FROM memory_chunks WHERE memoryId = :memoryId")
    suspend fun deleteChunksByMemory(memoryId: String)

    @Query("SELECT * FROM memory_chunks WHERE memoryId = :memoryId ORDER BY startOffset ASC")
    suspend fun getChunksByMemory(memoryId: String): List<MemoryChunkEntity>

    @Query("SELECT * FROM memory_chunks WHERE chunkUuid = :chunkUuid")
    suspend fun getChunkByUuid(chunkUuid: String): MemoryChunkEntity?

    @Query("SELECT * FROM memory_chunks WHERE rowId IN (:rowIds)")
    suspend fun getChunksByRowIds(rowIds: List<Long>): List<MemoryChunkEntity>

    /** All chunks that have an embedding, for a given agent's memories. */
    @Query(
        """
        SELECT mc.* FROM memory_chunks mc
        INNER JOIN memory_entries me ON mc.memoryId = me.id
        WHERE me.agentId = :agentId AND mc.embedding IS NOT NULL
        """
    )
    suspend fun getEmbeddedChunksByAgent(agentId: String): List<MemoryChunkEntity>

    /** Chunks that still need embedding vectors. */
    @Query(
        """
        SELECT mc.* FROM memory_chunks mc
        INNER JOIN memory_entries me ON mc.memoryId = me.id
        WHERE me.agentId = :agentId AND mc.embedding IS NULL
        """
    )
    suspend fun getUnembeddedChunksByAgent(agentId: String): List<MemoryChunkEntity>

    @Query(
        """
        UPDATE memory_chunks
        SET embedding = :embedding, embeddingModel = :model, updatedAt = :updatedAt
        WHERE rowId = :rowId
        """
    )
    suspend fun updateChunkEmbedding(
        rowId: Long,
        embedding: ByteArray,
        model: String,
        updatedAt: Long,
    )

    // ── FTS keyword search ──────────────────────────────────────────

    /**
     * Returns rowIds of chunks matching the FTS query.
     *
     * The caller then fetches full [MemoryChunkEntity] rows via
     * [getChunksByRowIds] and can join with entry data.
     */
    @Query("SELECT rowid FROM memory_chunks_fts WHERE memory_chunks_fts MATCH :query")
    suspend fun searchFtsRowIds(query: String): List<Long>

    /**
     * Combined FTS search scoped to a single agent.
     *
     * Joins the FTS index with the chunks and entries tables so only
     * memories belonging to [agentId] are returned.
     */
    @Query(
        """
        SELECT mc.* FROM memory_chunks mc
        INNER JOIN memory_chunks_fts fts ON mc.rowId = fts.rowid
        INNER JOIN memory_entries me ON mc.memoryId = me.id
        WHERE memory_chunks_fts MATCH :query
          AND me.agentId = :agentId
        """
    )
    suspend fun searchChunksFtsByAgent(
        query: String,
        agentId: String,
    ): List<MemoryChunkEntity>

    // ── Tags ────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: MemoryTagEntity): Long

    @Query("SELECT * FROM memory_tags WHERE name = :name")
    suspend fun getTagByName(name: String): MemoryTagEntity?

    @Query("SELECT * FROM memory_tags")
    suspend fun getAllTags(): List<MemoryTagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntryTagCrossRef(crossRef: MemoryEntryTagCrossRef)

    @Query("DELETE FROM memory_entry_tags WHERE memoryId = :memoryId")
    suspend fun deleteTagsForEntry(memoryId: String)

    /**
     * All memory IDs that carry *every* tag in [tagNames].
     *
     * Uses HAVING COUNT to enforce AND semantics (all tags must match).
     */
    @Query(
        """
        SELECT met.memoryId FROM memory_entry_tags met
        INNER JOIN memory_tags mt ON met.tagId = mt.id
        WHERE mt.name IN (:tagNames)
        GROUP BY met.memoryId
        HAVING COUNT(DISTINCT mt.name) = :tagCount
        """
    )
    suspend fun getMemoryIdsWithAllTags(tagNames: List<String>, tagCount: Int): List<String>

    // ── Metadata ────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMeta(meta: MemoryMetaEntity)

    @Query("SELECT value FROM memory_meta WHERE `key` = :key")
    suspend fun getMeta(key: String): String?

    @Query("DELETE FROM memory_meta WHERE `key` = :key")
    suspend fun deleteMeta(key: String)
}
