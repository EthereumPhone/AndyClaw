package org.ethereumphone.andyclaw.agent

import kotlinx.serialization.Serializable

/**
 * Standalone configuration for context compaction.
 * Stored in [SecurePrefs] independently from [BudgetPreset].
 */
@Serializable
data class CompactionConfig(
    /** Master switch for auto-compaction on message send. */
    val enabled: Boolean = false,
    /** Context window usage ratio (0.0–1.0) that triggers compaction. */
    val threshold: Float = 0.85f,
    /** Compact every N user turns regardless of token usage (0 = disabled). */
    val interval: Int = 0,
    /** Messages from summarized window re-included in next summarization for continuity. */
    val overlap: Int = 1,
    /** Number of recent messages kept verbatim after compaction. */
    val keepRecent: Int = 6,
    /** Layer 1: programmatic truncation of old assistant text + whitespace collapse. */
    val microcompactEnabled: Boolean = true,
    /** Layer 2: LLM-based summarization of older messages. */
    val llmSummaryEnabled: Boolean = true,
) {
    /**
     * Return true if auto-compaction should trigger.
     *
     * Dual-trigger strategy:
     * 1. Token-percentage (primary): fires when usage >= [threshold].
     * 2. Turn-count (secondary): fires every [interval] turns.
     */
    fun shouldCompact(usedTokens: Int, maxTokens: Int, turnsSinceLastCompaction: Int = 0): Boolean {
        if (!enabled) return false
        if (!microcompactEnabled && !llmSummaryEnabled) return false
        val tokenTrigger = maxTokens > 0 && usedTokens.toFloat() / maxTokens >= threshold
        val intervalTrigger = interval > 0 && turnsSinceLastCompaction >= interval
        return tokenTrigger || intervalTrigger
    }
}
