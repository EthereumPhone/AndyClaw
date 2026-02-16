package org.ethereumphone.andyclaw.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.ethereumphone.andyclaw.memory.db.Converters
import org.ethereumphone.andyclaw.memory.db.MemoryDao
import org.ethereumphone.andyclaw.memory.db.MemoryDatabase
import org.ethereumphone.andyclaw.memory.db.entity.MemoryChunkEntity
import org.ethereumphone.andyclaw.memory.db.entity.MemoryEntryEntity
import org.ethereumphone.andyclaw.memory.db.entity.MemoryEntryTagCrossRef
import org.ethereumphone.andyclaw.memory.db.entity.MemoryMetaEntity
import org.ethereumphone.andyclaw.memory.db.entity.MemoryTagEntity
import org.ethereumphone.andyclaw.memory.embedding.EmbeddingProvider
import org.ethereumphone.andyclaw.memory.model.MemoryEntry
import org.ethereumphone.andyclaw.memory.model.MemorySource
import org.ethereumphone.andyclaw.memory.search.ChunkingEngine
import java.security.MessageDigest
import java.util.UUID

/**
 * Repository for the memory subsystem.
 *
 * Orchestrates DAO operations, text chunking, embedding generation,
 * and FTS index maintenance. All public methods are suspend-safe and
 * dispatch heavy work to [Dispatchers.IO].
 */
class MemoryRepository(
    private val database: MemoryDatabase,
    private val dao: MemoryDao = database.memoryDao(),
    private val chunkingEngine: ChunkingEngine = ChunkingEngine(),
) {
    // ── Store ───────────────────────────────────────────────────────

    /**
     * Persist a new memory, chunk it, and rebuild the FTS index.
     *
     * If content with the same SHA-256 hash already exists for this agent,
     * the existing entry is returned without modification (dedup).
     *
     * @return The stored (or existing) [MemoryEntry].
     */
    suspend fun store(
        agentId: String,
        content: String,
        source: MemorySource = MemorySource.MANUAL,
        tags: List<String> = emptyList(),
        importance: Float = 0.5f,
    ): MemoryEntry = withContext(Dispatchers.IO) {
        val hash = sha256(content)

        // Dedup: if identical content already exists, return it.
        dao.getEntryByHash(hash)?.let { existing ->
            return@withContext existing.toDomain(resolveTags(existing.id))
        }

        val now = System.currentTimeMillis()
        val memoryId = UUID.randomUUID().toString()

        val entry = MemoryEntryEntity(
            id = memoryId,
            agentId = agentId,
            content = content,
            source = source.name,
            importance = importance.coerceIn(0f, 1f),
            hash = hash,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertEntry(entry)

        // Persist tags
        setTagsForEntry(memoryId, tags)

        // Chunk and index
        rechunkEntry(memoryId, content, now)

        entry.toDomain(tags)
    }

    // ── Read ────────────────────────────────────────────────────────

    /**
     * Get a single memory by ID, or null.
     */
    suspend fun get(memoryId: String): MemoryEntry? = withContext(Dispatchers.IO) {
        val entity = dao.getEntryById(memoryId) ?: return@withContext null
        entity.toDomain(resolveTags(memoryId))
    }

    /**
     * List memories for an agent with optional filters.
     */
    suspend fun list(
        agentId: String,
        source: MemorySource? = null,
        tags: List<String>? = null,
        limit: Int = 50,
    ): List<MemoryEntry> = withContext(Dispatchers.IO) {
        // Start with entries by agent (optionally filtered by source)
        val entries = if (source != null) {
            dao.getEntriesByAgentAndSource(agentId, source.name)
        } else {
            dao.getEntriesByAgent(agentId)
        }

        // Tag filter
        val filtered = if (!tags.isNullOrEmpty()) {
            val allowedIds = dao.getMemoryIdsWithAllTags(tags, tags.size).toSet()
            entries.filter { it.id in allowedIds }
        } else {
            entries
        }

        filtered.take(limit).map { it.toDomain(resolveTags(it.id)) }
    }

    /**
     * Reactive stream of all memories for an agent.
     */
    fun observe(agentId: String): Flow<List<MemoryEntry>> {
        return dao.observeEntriesByAgent(agentId).map { entities ->
            entities.map { it.toDomain(resolveTags(it.id)) }
        }
    }

    /**
     * Reactive count of memories for an agent (Room invalidation-tracked).
     * Efficient: uses SQL COUNT(*) instead of loading all entries.
     */
    fun observeCount(agentId: String): Flow<Int> {
        return dao.observeEntryCountByAgent(agentId)
    }

    // ── Update ──────────────────────────────────────────────────────

    /**
     * Update the content of an existing memory.
     *
     * Re-chunks and rebuilds the FTS index. Returns the updated entry,
     * or null if the memory does not exist.
     */
    suspend fun update(
        memoryId: String,
        content: String,
        tags: List<String>? = null,
        importance: Float? = null,
    ): MemoryEntry? = withContext(Dispatchers.IO) {
        val existing = dao.getEntryById(memoryId) ?: return@withContext null
        val now = System.currentTimeMillis()
        val newHash = sha256(content)

        val updated = existing.copy(
            content = content,
            hash = newHash,
            updatedAt = now,
            importance = importance?.coerceIn(0f, 1f) ?: existing.importance,
        )
        dao.updateEntry(updated)

        // Update tags if provided
        if (tags != null) {
            setTagsForEntry(memoryId, tags)
        }

        // Re-chunk if content changed
        if (newHash != existing.hash) {
            rechunkEntry(memoryId, content, now)
        }

        updated.toDomain(resolveTags(memoryId))
    }

    /**
     * Record that a memory was surfaced in search results.
     */
    suspend fun recordAccess(memoryId: String) = withContext(Dispatchers.IO) {
        dao.recordAccess(memoryId, System.currentTimeMillis())
    }

    // ── Delete ──────────────────────────────────────────────────────

    /**
     * Delete a memory and all its chunks/tags (cascade).
     */
    suspend fun delete(memoryId: String) = withContext(Dispatchers.IO) {
        dao.deleteEntry(memoryId) // CASCADE handles chunks + tag cross-refs
        database.rebuildFtsIndex()
    }

    /**
     * Delete all memories for an agent and rebuild the FTS index.
     * Uses a single SQL DELETE (CASCADE removes chunks + tag cross-refs).
     */
    suspend fun deleteAll(agentId: String) = withContext(Dispatchers.IO) {
        dao.deleteAllEntriesByAgent(agentId)
        database.rebuildFtsIndex()
    }

    // ── Embedding management ────────────────────────────────────────

    /**
     * Generate embeddings for all un-embedded chunks of an agent.
     *
     * Processes in batches for efficiency. Rebuilds FTS after completion.
     *
     * @return Number of chunks that were embedded.
     */
    suspend fun embedUnprocessedChunks(
        agentId: String,
        provider: EmbeddingProvider,
        batchSize: Int = 32,
    ): Int = withContext(Dispatchers.IO) {
        val unembedded = dao.getUnembeddedChunksByAgent(agentId)
        if (unembedded.isEmpty()) return@withContext 0

        var count = 0
        unembedded.chunked(batchSize).forEach { batch ->
            val texts = batch.map { it.text }
            val embeddings = try {
                provider.embed(texts)
            } catch (_: Exception) {
                return@forEach // skip this batch on error
            }

            val now = System.currentTimeMillis()
            batch.zip(embeddings).forEach { (chunk, vec) ->
                val bytes = Converters.fromFloatArray(vec) ?: return@forEach
                dao.updateChunkEmbedding(
                    rowId = chunk.rowId,
                    embedding = bytes,
                    model = provider.modelName,
                    updatedAt = now,
                )
                count++
            }
        }

        count
    }

    /**
     * Force a full re-index: re-chunk every memory for the agent
     * and rebuild the FTS index. Embeddings are cleared (must be
     * regenerated via [embedUnprocessedChunks]).
     */
    suspend fun reindex(agentId: String) = withContext(Dispatchers.IO) {
        val entries = dao.getEntriesByAgent(agentId)
        val now = System.currentTimeMillis()
        for (entry in entries) {
            rechunkEntry(entry.id, entry.content, now)
        }
    }

    // ── Metadata ────────────────────────────────────────────────────

    suspend fun setMeta(key: String, value: String) = withContext(Dispatchers.IO) {
        dao.setMeta(MemoryMetaEntity(key, value))
    }

    suspend fun getMeta(key: String): String? = withContext(Dispatchers.IO) {
        dao.getMeta(key)
    }

    // ── Internal helpers ────────────────────────────────────────────

    /**
     * Delete existing chunks for [memoryId], create new chunks from
     * [content], insert them, and rebuild the FTS index.
     */
    private suspend fun rechunkEntry(
        memoryId: String,
        content: String,
        now: Long,
    ) {
        dao.deleteChunksByMemory(memoryId)

        val chunks = chunkingEngine.chunk(content)
        val entities = chunks.map { chunk ->
            MemoryChunkEntity(
                chunkUuid = UUID.randomUUID().toString(),
                memoryId = memoryId,
                text = chunk.text,
                startOffset = chunk.startOffset,
                endOffset = chunk.endOffset,
                hash = sha256(chunk.text),
                updatedAt = now,
            )
        }

        if (entities.isNotEmpty()) {
            dao.insertChunks(entities)
        }

        database.rebuildFtsIndex()
    }

    /**
     * Resolve tags → ensure each tag name exists in [memory_tags],
     * clear old cross-refs, and write new ones.
     */
    private suspend fun setTagsForEntry(memoryId: String, tagNames: List<String>) {
        dao.deleteTagsForEntry(memoryId)
        for (name in tagNames) {
            val normalised = name.trim().lowercase()
            if (normalised.isBlank()) continue
            // Insert-or-ignore, then fetch the id
            dao.insertTag(MemoryTagEntity(name = normalised))
            val tag = dao.getTagByName(normalised) ?: continue
            dao.insertEntryTagCrossRef(MemoryEntryTagCrossRef(memoryId, tag.id))
        }
    }

    /**
     * Read the tag names currently assigned to [memoryId].
     */
    private suspend fun resolveTags(memoryId: String): List<String> {
        return dao.getEntryWithTags(memoryId)
            ?.tags
            ?.map { it.name }
            ?: emptyList()
    }

    // ── Mapping ─────────────────────────────────────────────────────

    private fun MemoryEntryEntity.toDomain(tags: List<String>): MemoryEntry {
        return MemoryEntry(
            id = id,
            agentId = agentId,
            content = content,
            source = runCatching { MemorySource.valueOf(source) }.getOrDefault(MemorySource.MANUAL),
            tags = tags,
            importance = importance,
            createdAt = createdAt,
            updatedAt = updatedAt,
            accessedAt = accessedAt,
            accessCount = accessCount,
        )
    }

    companion object {
        /** SHA-256 hex digest. */
        fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
