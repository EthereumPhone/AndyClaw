package org.ethereumphone.andyclaw.agent

import kotlinx.serialization.Serializable

/**
 * Controls which output-token-saving optimizations are active.
 * Stock presets provide common configurations; custom presets
 * allow fine-grained control over each optimization.
 */
@Serializable
data class BudgetPreset(
    /** Unique identifier (UUID for custom, "stock_*" for built-ins). */
    val id: String,
    /** Human-readable name shown in the UI. */
    val name: String,
    /** Stock presets can be reverted to defaults; custom ones can be deleted. */
    val isStock: Boolean,
    /** Dynamically lower max_tokens based on query complexity. */
    val dynamicMaxTokens: Boolean,
    /** Add conciseness instructions to system prompt. */
    val concisePrompt: Boolean,
    /** Encourage parallel tool calls in the system prompt. */
    val parallelToolCalls: Boolean,
    /** Instruct model to skip preamble text before tool calls. */
    val noPreambleToolCalls: Boolean,
    /** Summarize older conversation history to reduce context. */
    val historySummarization: Boolean,
    /** Cap thinking/reasoning tokens for thinking models. */
    val thinkingBudget: Boolean,
    /** Truncate large tool results before feeding back to LLM. */
    val toolResultTruncation: Boolean,
    /** Max chars for tool result truncation (0 = no limit). */
    val toolResultMaxChars: Int = 3000,
    /** Max messages to keep verbatim before summarizing older ones. */
    val historySummarizationKeepRecent: Int = 6,
    /** Context window usage ratio that triggers compaction (0.0–1.0). */
    val compactionThreshold: Float = 0.85f,
    /** Compact every N user turns regardless of token usage (0 = disabled). */
    val compactionInterval: Int = 0,
    /** Messages from summarized window end re-included in next summarization for continuity. */
    val compactionOverlap: Int = 1,
) {
    companion object {
        const val defaultPresetId: String = "stock_balanced"

        fun defaults(): List<BudgetPreset> = listOf(
            BudgetPreset(
                id = "stock_off",
                name = "Off",
                isStock = true,
                dynamicMaxTokens = false,
                concisePrompt = false,
                parallelToolCalls = false,
                noPreambleToolCalls = false,
                historySummarization = false,
                thinkingBudget = false,
                toolResultTruncation = false,
            ),
            BudgetPreset(
                id = "stock_balanced",
                name = "Balanced",
                isStock = true,
                dynamicMaxTokens = true,
                concisePrompt = true,
                parallelToolCalls = true,
                noPreambleToolCalls = true,
                historySummarization = false,
                thinkingBudget = false,
                toolResultTruncation = true,
                toolResultMaxChars = 3000,
            ),
            BudgetPreset(
                id = "stock_aggressive",
                name = "Aggressive",
                isStock = true,
                dynamicMaxTokens = true,
                concisePrompt = true,
                parallelToolCalls = true,
                noPreambleToolCalls = true,
                historySummarization = true,
                thinkingBudget = true,
                toolResultTruncation = true,
                toolResultMaxChars = 2000,
                historySummarizationKeepRecent = 4,
                compactionThreshold = 0.80f,
                compactionInterval = 10,
                compactionOverlap = 2,
            ),
        )
    }
}
