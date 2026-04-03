package org.ethereumphone.andyclaw.memory.model

/**
 * Semantic type of a memory entry, ported from Claude Code's 4-type taxonomy.
 *
 * Each type has distinct save/recall guidance that helps the model decide
 * what to remember and when to surface it.
 */
enum class MemoryType {
    /** Information about the user's role, goals, preferences, and knowledge. */
    USER,

    /** Corrections, validated approaches, and guidance on how to approach work. */
    FEEDBACK,

    /** Ongoing work, goals, initiatives, bugs, or incidents within the project. */
    PROJECT,

    /** Pointers to external systems and resources. */
    REFERENCE,
}
