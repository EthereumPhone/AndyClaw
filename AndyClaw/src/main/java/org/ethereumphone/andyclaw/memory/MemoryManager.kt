package org.ethereumphone.andyclaw.memory

import android.content.Context
import kotlinx.coroutines.flow.Flow
import org.ethereumphone.andyclaw.memory.db.MemoryDatabase
import org.ethereumphone.andyclaw.memory.embedding.EmbeddingProvider
import org.ethereumphone.andyclaw.memory.model.MemoryEntry
import org.ethereumphone.andyclaw.memory.model.MemorySearchResult
import org.ethereumphone.andyclaw.memory.model.MemorySource
import org.ethereumphone.andyclaw.memory.search.ChunkingEngine
import org.ethereumphone.andyclaw.memory.search.MemorySearchManager

/**
 * Public entry-point for the AndyClaw memory subsystem.
 *
 * Wraps [MemoryRepository] (persistence) and [MemorySearchManager] (search)
 * behind a concise, Android-friendly API. All operations are scoped to
 * [agentId] for multi-agent isolation (mirroring OpenClaw's per-agent model).
 *
 * Usage:
 * ```kotlin
 * val memory = MemoryManager(context, agentId = "default")
 *
 * // Store
 * memory.store("User prefers dark mode", tags = listOf("preference", "ui"))
 *
 * // Search
 * val results = memory.search("What UI preferences does the user have?")
 *
 * // Optionally enable semantic search
 * memory.setEmbeddingProvider(myOpenAiProvider)
 * memory.reindex() // generate embeddings for existing content
 * ```
 *
 * @param context  Android context (application context is extracted internally).
 * @param agentId  Logical agent ID. Memories are isolated per agent.
 * @param chunkingEngine  Optional custom chunking configuration.
 */
class MemoryManager(
    context: Context,
    private val agentId: String,
    chunkingEngine: ChunkingEngine = ChunkingEngine(),
) {
    private val database: MemoryDatabase = MemoryDatabase.getInstance(context)
    private val dao = database.memoryDao()
    private val repository = MemoryRepository(database, dao, chunkingEngine)
    private val searchManager = MemorySearchManager(dao)

    // ── Store ───────────────────────────────────────────────────────

    /**
     * Persist a new long-term memory.
     *
     * The content is chunked and indexed for future search.
     * Duplicate content (same SHA-256) is silently de-duplicated.
     *
     * @param content    The text to remember.
     * @param source     How the memory originated.
     * @param tags       Optional categorisation tags.
     * @param importance Weight in [0,1]; higher values surface first.
     * @return The stored [MemoryEntry].
     */
    suspend fun store(
        content: String,
        source: MemorySource = MemorySource.MANUAL,
        tags: List<String> = emptyList(),
        importance: Float = 0.5f,
    ): MemoryEntry {
        return repository.store(agentId, content, source, tags, importance)
    }

    // ── Search ──────────────────────────────────────────────────────

    /**
     * Search memories using hybrid keyword + vector similarity.
     *
     * If no [EmbeddingProvider] has been set, falls back to keyword-only
     * (FTS4) search. Results are ranked by combined score.
     *
     * @param query      Natural-language query.
     * @param maxResults Maximum number of results.
     * @param minScore   Minimum combined score threshold [0,1].
     * @param tags       If provided, only memories with ALL these tags match.
     * @return Ranked list of [MemorySearchResult], best first.
     */
    suspend fun search(
        query: String,
        maxResults: Int = MemorySearchManager.DEFAULT_MAX_RESULTS,
        minScore: Float = MemorySearchManager.DEFAULT_MIN_SCORE,
        tags: List<String>? = null,
    ): List<MemorySearchResult> {
        return searchManager.search(query, agentId, maxResults, minScore, tags)
    }

    // ── CRUD ────────────────────────────────────────────────────────

    /**
     * Retrieve a single memory by its ID, or null.
     */
    suspend fun get(memoryId: String): MemoryEntry? {
        return repository.get(memoryId)
    }

    /**
     * List memories with optional filters.
     *
     * @param source Filter by source type (null = all).
     * @param tags   Filter to memories carrying ALL listed tags (null = all).
     * @param limit  Maximum entries returned.
     */
    suspend fun list(
        source: MemorySource? = null,
        tags: List<String>? = null,
        limit: Int = 50,
    ): List<MemoryEntry> {
        return repository.list(agentId, source, tags, limit)
    }

    /**
     * Reactive [Flow] of all memories for this agent, newest first.
     */
    fun observe(): Flow<List<MemoryEntry>> {
        return repository.observe(agentId)
    }

    /**
     * Update an existing memory's content, tags, or importance.
     *
     * Re-chunks and re-indexes automatically. Returns the updated entry
     * or null if [memoryId] doesn't exist.
     */
    suspend fun update(
        memoryId: String,
        content: String,
        tags: List<String>? = null,
        importance: Float? = null,
    ): MemoryEntry? {
        return repository.update(memoryId, content, tags, importance)
    }

    /**
     * Permanently delete a memory and all associated chunks/tags.
     */
    suspend fun delete(memoryId: String) {
        repository.delete(memoryId)
    }

    // ── Embeddings & Indexing ────────────────────────────────────────

    /**
     * Set (or replace) the embedding provider used for semantic search.
     *
     * After setting a new provider, call [reindex] to generate vectors
     * for existing content.
     */
    fun setEmbeddingProvider(provider: EmbeddingProvider) {
        searchManager.setEmbeddingProvider(provider)
    }

    /**
     * Re-chunk all memories and optionally generate embeddings.
     *
     * Call after changing the chunking configuration or embedding provider.
     *
     * @param force If true, re-chunks even unchanged content.
     * @param embeddingProvider If provided, generate embeddings in the same pass.
     * @return Number of chunks that were (re-)embedded, or 0 if no provider.
     */
    suspend fun reindex(
        force: Boolean = false,
        embeddingProvider: EmbeddingProvider? = null,
    ): Int {
        if (force) {
            repository.reindex(agentId)
        }

        val provider = embeddingProvider ?: return 0
        return repository.embedUnprocessedChunks(agentId, provider)
    }

    // ── Metadata ────────────────────────────────────────────────────

    /**
     * Store an arbitrary key-value pair in the memory metadata table.
     *
     * Useful for tracking index version, last sync time, model info, etc.
     */
    suspend fun setMeta(key: String, value: String) {
        repository.setMeta(key, value)
    }

    /**
     * Read a metadata value, or null if not set.
     */
    suspend fun getMeta(key: String): String? {
        return repository.getMeta(key)
    }

    // ── Lifecycle ───────────────────────────────────────────────────

    /**
     * Close the underlying database.
     *
     * Typically only needed in tests; in production the singleton
     * database lives for the application's lifetime.
     */
    fun close() {
        database.close()
    }
}
