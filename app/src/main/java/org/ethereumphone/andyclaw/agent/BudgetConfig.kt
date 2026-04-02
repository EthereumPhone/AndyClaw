package org.ethereumphone.andyclaw.agent

import android.util.Log
import org.ethereumphone.andyclaw.skills.RoutingBudget

/**
 * Runtime budget configuration derived from a [BudgetPreset].
 * Provides helper methods used by [AgentLoop] to apply
 * output-token-saving optimizations.
 */
class BudgetConfig(val preset: BudgetPreset) {

    companion object {
        private const val TAG = "BudgetConfig"
    }

    val enabled: Boolean get() = preset.id != "stock_off"

    /**
     * Compute the effective max_tokens for a request.
     *
     * Uses the skill router's [routerBudget] hint (low/medium/high) to size the
     * output budget. When the router hint is unavailable (router disabled, cache
     * miss, etc.), returns [modelDefault] unchanged.
     */
    fun effectiveMaxTokens(
        modelDefault: Int,
        iteration: Int,
        routerBudget: RoutingBudget? = null,
    ): Int {
        if (!preset.dynamicMaxTokens) return modelDefault
        if (routerBudget == null) return modelDefault

        val base = when (routerBudget) {
            RoutingBudget.LOW -> (modelDefault / 8).coerceAtLeast(512)
            RoutingBudget.MEDIUM -> (modelDefault / 4).coerceAtLeast(2048)
            RoutingBudget.HIGH -> modelDefault
        }
        // After first iteration (tool loops), reduce budget since model
        // already has context and should be more focused
        val adjusted = if (iteration > 1) (base * 3 / 4).coerceAtLeast(512) else base
        Log.d(TAG, "effectiveMaxTokens: router=$routerBudget, base=$base, adjusted=$adjusted (model=$modelDefault, iter=$iteration)")
        return adjusted
    }

    /**
     * Truncate a tool result string if truncation is enabled.
     */
    fun truncateToolResult(result: String): String {
        if (!preset.toolResultTruncation || preset.toolResultMaxChars <= 0) return result
        if (result.length <= preset.toolResultMaxChars) return result
        val truncated = result.take(preset.toolResultMaxChars)
        Log.d(TAG, "truncateToolResult: ${result.length} -> ${truncated.length} chars")
        return truncated + "\n[... truncated ${result.length - preset.toolResultMaxChars} chars to save tokens]"
    }

    /**
     * Return true if conversation history should be summarized at this point.
     */
    fun shouldSummarizeHistory(messageCount: Int): Boolean {
        if (!preset.historySummarization) return false
        return messageCount > preset.historySummarizationKeepRecent + 2
    }

    /**
     * Return true if context compaction should be triggered.
     *
     * Uses a dual-trigger strategy:
     * 1. **Token-percentage** (primary): fires when context usage exceeds [BudgetPreset.compactionThreshold].
     * 2. **Turn-count** (secondary, à la Google ADK `compaction_interval`): fires every N user turns,
     *    useful as a fallback for providers that don't report accurate token counts.
     */
    fun shouldCompact(usedTokens: Int, maxTokens: Int, turnsSinceLastCompaction: Int = 0): Boolean {
        if (!preset.historySummarization) return false
        val tokenTrigger = maxTokens > 0 && usedTokens.toFloat() / maxTokens >= preset.compactionThreshold
        val intervalTrigger = preset.compactionInterval > 0 && turnsSinceLastCompaction >= preset.compactionInterval
        return tokenTrigger || intervalTrigger
    }
}
