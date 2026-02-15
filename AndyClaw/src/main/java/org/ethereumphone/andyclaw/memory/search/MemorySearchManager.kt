package org.ethereumphone.andyclaw.memory.search

import org.ethereumphone.andyclaw.memory.db.Converters
import org.ethereumphone.andyclaw.memory.db.MemoryDao
import org.ethereumphone.andyclaw.memory.db.entity.MemoryChunkEntity
import org.ethereumphone.andyclaw.memory.embedding.CosineSimilarity
import org.ethereumphone.andyclaw.memory.embedding.EmbeddingProvider
import org.ethereumphone.andyclaw.memory.model.MemorySearchResult
import org.ethereumphone.andyclaw.memory.model.MemorySource

/**
 * Hybrid search engine combining FTS keyword search and vector similarity.
 *
 * Mirrors OpenClaw's `MemoryIndexManager.search()` approach:
 *  1. Run FTS4 keyword search → normalised BM25-style scores
 *  2. Run cosine-similarity vector search → normalised scores
 *  3. Merge and rank with configurable weights
 *
 * Falls back gracefully:
 *  - No embedding provider → keyword-only
 *  - No FTS matches → vector-only
 *  - Neither → empty
 *
 * @property dao               DAO for database access.
 * @property embeddingProvider  Optional provider; null disables vector search.
 * @property vectorWeight       Weight for vector scores in [0, 1].
 * @property keywordWeight      Weight for keyword scores in [0, 1].
 * @property candidateMultiplier Fetch this many extra candidates before final cut.
 */
class MemorySearchManager(
    private val dao: MemoryDao,
    private var embeddingProvider: EmbeddingProvider? = null,
    private val vectorWeight: Float = DEFAULT_VECTOR_WEIGHT,
    private val keywordWeight: Float = DEFAULT_KEYWORD_WEIGHT,
    private val candidateMultiplier: Int = DEFAULT_CANDIDATE_MULTIPLIER,
) {
    /**
     * Swap the embedding provider at runtime (e.g. after user configures API key).
     */
    fun setEmbeddingProvider(provider: EmbeddingProvider?) {
        embeddingProvider = provider
    }

    /**
     * Execute a hybrid search against an agent's memories.
     *
     * @param query      Natural-language query string.
     * @param agentId    Scope to this agent's memories.
     * @param maxResults Maximum results to return.
     * @param minScore   Drop results below this combined score.
     * @param filterTags If non-null, only include memories carrying ALL of these tags.
     * @return Ranked list of [MemorySearchResult], best first.
     */
    suspend fun search(
        query: String,
        agentId: String,
        maxResults: Int = DEFAULT_MAX_RESULTS,
        minScore: Float = DEFAULT_MIN_SCORE,
        filterTags: List<String>? = null,
    ): List<MemorySearchResult> {
        val candidateLimit = maxResults * candidateMultiplier

        // Determine which memory IDs pass the tag filter (if any).
        val allowedMemoryIds: Set<String>? = filterTags?.let { tags ->
            if (tags.isEmpty()) null
            else dao.getMemoryIdsWithAllTags(tags, tags.size).toSet()
        }

        // ── 1. Keyword search (FTS4) ───────────────────────────────
        val keywordHits = runKeywordSearch(query, agentId, allowedMemoryIds)

        // ── 2. Vector search (cosine similarity) ───────────────────
        val vectorHits = runVectorSearch(query, agentId, candidateLimit, allowedMemoryIds)

        // ── 3. Merge ───────────────────────────────────────────────
        val merged = mergeResults(keywordHits, vectorHits)

        // ── 4. Filter & rank ───────────────────────────────────────
        return merged
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(maxResults)
    }

    // ────────────────────────────────────────────────────────────────
    // Keyword search
    // ────────────────────────────────────────────────────────────────

    private suspend fun runKeywordSearch(
        query: String,
        agentId: String,
        allowedIds: Set<String>?,
    ): Map<Long, ScoredChunk> {
        // Build an FTS4 MATCH expression.
        // Tokenise the query and OR the terms for broad recall.
        val ftsQuery = buildFtsQuery(query)
        if (ftsQuery.isBlank()) return emptyMap()

        val chunks = try {
            dao.searchChunksFtsByAgent(ftsQuery, agentId)
        } catch (_: Exception) {
            // FTS can throw on malformed queries; degrade gracefully.
            emptyList()
        }

        val filtered = if (allowedIds != null) {
            chunks.filter { it.memoryId in allowedIds }
        } else {
            chunks
        }

        if (filtered.isEmpty()) return emptyMap()

        // Assign simple rank: earlier = better (FTS returns by relevance).
        // Normalise to [0, 1] based on position.
        val maxRank = filtered.size.toFloat()
        return filtered.mapIndexed { index, chunk ->
            chunk.rowId to ScoredChunk(
                chunk = chunk,
                score = (maxRank - index) / maxRank,
            )
        }.toMap()
    }

    /**
     * Builds an FTS4 MATCH expression from a natural-language query.
     *
     * Splits on whitespace, drops short tokens, and ORs them.
     * Example: "user preferences dark mode" → "user OR preferences OR dark OR mode"
     */
    private fun buildFtsQuery(query: String): String {
        return query
            .split(Regex("\\s+"))
            .map { it.replace(Regex("[^\\w]"), "") }
            .filter { it.length >= 2 }
            .joinToString(" OR ")
    }

    // ────────────────────────────────────────────────────────────────
    // Vector search
    // ────────────────────────────────────────────────────────────────

    private suspend fun runVectorSearch(
        query: String,
        agentId: String,
        limit: Int,
        allowedIds: Set<String>?,
    ): Map<Long, ScoredChunk> {
        val provider = embeddingProvider ?: return emptyMap()

        // Embed the query
        val queryVec = try {
            provider.embed(query)
        } catch (_: Exception) {
            return emptyMap()
        }

        // Load all embedded chunks for this agent
        val embeddedChunks = dao.getEmbeddedChunksByAgent(agentId)

        val filtered = if (allowedIds != null) {
            embeddedChunks.filter { it.memoryId in allowedIds }
        } else {
            embeddedChunks
        }

        if (filtered.isEmpty()) return emptyMap()

        // Score each chunk via cosine similarity
        return filtered
            .mapNotNull { chunk ->
                val chunkVec = Converters.toFloatArray(chunk.embedding) ?: return@mapNotNull null
                if (chunkVec.size != queryVec.size) return@mapNotNull null
                val raw = CosineSimilarity.compute(queryVec, chunkVec)
                val score = CosineSimilarity.normalise(raw) // [0, 1]
                chunk.rowId to ScoredChunk(chunk, score)
            }
            .sortedByDescending { it.second.score }
            .take(limit)
            .toMap()
    }

    // ────────────────────────────────────────────────────────────────
    // Merge
    // ────────────────────────────────────────────────────────────────

    private suspend fun mergeResults(
        keywordHits: Map<Long, ScoredChunk>,
        vectorHits: Map<Long, ScoredChunk>,
    ): List<MemorySearchResult> {
        // Union of all candidate rowIds
        val allRowIds = keywordHits.keys + vectorHits.keys
        if (allRowIds.isEmpty()) return emptyList()

        // Build combined scores
        val scored = allRowIds.map { rowId ->
            val kwScore = keywordHits[rowId]?.score ?: 0f
            val vecScore = vectorHits[rowId]?.score ?: 0f
            val combined = keywordWeight * kwScore + vectorWeight * vecScore
            val chunk = keywordHits[rowId]?.chunk ?: vectorHits[rowId]!!.chunk
            chunk to combined
        }

        // Resolve parent entry metadata
        return scored.map { (chunk, score) ->
            val entry = dao.getEntryById(chunk.memoryId)
            val tags = dao.getEntryWithTags(chunk.memoryId)
                ?.tags
                ?.map { it.name }
                ?: emptyList()

            MemorySearchResult(
                memoryId = chunk.memoryId,
                snippet = chunk.text,
                score = score,
                source = entry?.source?.let { runCatching { MemorySource.valueOf(it) }.getOrNull() }
                    ?: MemorySource.MANUAL,
                tags = tags,
                chunkId = chunk.chunkUuid,
            )
        }
    }

    /** Internal holder pairing a chunk with a normalised score. */
    private data class ScoredChunk(
        val chunk: MemoryChunkEntity,
        val score: Float,
    )

    companion object {
        const val DEFAULT_VECTOR_WEIGHT = 0.7f
        const val DEFAULT_KEYWORD_WEIGHT = 0.3f
        const val DEFAULT_MAX_RESULTS = 6
        const val DEFAULT_MIN_SCORE = 0.35f
        const val DEFAULT_CANDIDATE_MULTIPLIER = 4
    }
}
