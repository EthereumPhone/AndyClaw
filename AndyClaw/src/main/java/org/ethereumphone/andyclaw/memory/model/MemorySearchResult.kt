package org.ethereumphone.andyclaw.memory.model

/**
 * A single hit returned by [MemorySearchManager.search].
 *
 * Comparable to OpenClaw's `MemorySearchResult` but adapted for the
 * Android chunked-memory model.
 */
data class MemorySearchResult(
    /** ID of the parent [MemoryEntry]. */
    val memoryId: String,

    /** The chunk text that matched the query. */
    val snippet: String,

    /** Combined relevance score in [0, 1]. */
    val score: Float,

    /** Source type of the parent memory. */
    val source: MemorySource,

    /** Tags on the parent memory. */
    val tags: List<String>,

    /** ID of the specific chunk that matched. */
    val chunkId: String,
)
