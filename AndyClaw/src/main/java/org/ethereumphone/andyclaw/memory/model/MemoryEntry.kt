package org.ethereumphone.andyclaw.memory.model

/**
 * Public-facing domain model for a stored memory.
 *
 * This is the object returned from [MemoryManager] APIs.
 * Internally backed by Room entities, but callers never see Room annotations.
 */
data class MemoryEntry(
    /** Unique identifier (UUID). */
    val id: String,

    /** Agent this memory belongs to. */
    val agentId: String,

    /** Full text content of the memory. */
    val content: String,

    /** How this memory was created. */
    val source: MemorySource,

    /** Human-readable tags for categorisation. */
    val tags: List<String>,

    /** Importance weight in [0, 1]. Higher = more likely to surface. */
    val importance: Float,

    /** Epoch millis when the memory was first created. */
    val createdAt: Long,

    /** Epoch millis of the last content update. */
    val updatedAt: Long,

    /** Epoch millis of the last time this memory was surfaced in search. */
    val accessedAt: Long,

    /** How many times this memory has been surfaced. */
    val accessCount: Int,
)
