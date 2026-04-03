package org.ethereumphone.andyclaw.agent

import android.util.Log
import org.ethereumphone.andyclaw.llm.AnthropicApiException

/**
 * Tracks auto-compaction state across agent loop iterations.
 * Ported from Claude Code's `AutoCompactTrackingState`.
 */
data class AutoCompactTrackingState(
    var compacted: Boolean = false,
    var turnCounter: Int = 0,
    var consecutiveFailures: Int = 0,
)

/**
 * Handles reactive compaction when the API rejects a request because the
 * prompt is too long (HTTP 413 or 400 with "prompt is too long").
 *
 * Includes a circuit breaker that stops retrying after [MAX_CONSECUTIVE_FAILURES]
 * consecutive compaction failures.
 *
 * Ported from Claude Code's `autoCompact.ts`.
 */
class ReactiveCompaction(
    private val compactor: ContextCompactor,
    private val modelId: String,
) {
    companion object {
        private const val TAG = "ReactiveCompaction"
        /** Stop retrying after this many consecutive compaction failures. */
        const val MAX_CONSECUTIVE_FAILURES = 3
        /** When reacting to a prompt-too-long error, keep fewer recent messages
         *  to maximize the amount we compact away. */
        private const val REACTIVE_KEEP_RECENT = 4
    }

    /**
     * Returns true if the exception indicates the prompt was too long.
     */
    fun isPromptTooLong(exception: AnthropicApiException): Boolean {
        if (exception.statusCode == 413) return true
        if (exception.statusCode == 400) {
            val msg = exception.message ?: return false
            return msg.contains("prompt is too long", ignoreCase = true) ||
                msg.contains("prompt_too_long", ignoreCase = true)
        }
        return false
    }

    /**
     * Attempts reactive compaction on the conversation history.
     *
     * @param history Mutable conversation history — will be replaced with
     *   compacted version on success.
     * @param tracking Shared tracking state across loop iterations.
     * @return The compaction result on success, or null if:
     *   - The circuit breaker has tripped (too many consecutive failures)
     *   - Compaction itself fails
     */
    suspend fun tryReactiveCompact(
        history: MutableList<org.ethereumphone.andyclaw.llm.Message>,
        tracking: AutoCompactTrackingState,
    ): ContextCompactor.CompactionResult? {
        // Circuit breaker: stop if we've failed too many times in a row
        if (tracking.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            Log.w(TAG, "Circuit breaker tripped: ${tracking.consecutiveFailures} consecutive failures, skipping compaction")
            return null
        }

        Log.i(TAG, "Attempting reactive compaction (consecutive failures: ${tracking.consecutiveFailures})")
        return try {
            val result = compactor.compact(
                history = history.toList(),
                modelId = modelId,
                keepRecentOverride = REACTIVE_KEEP_RECENT,
            )
            if (result.wasCompacted) {
                tracking.consecutiveFailures = 0
                tracking.compacted = true
                Log.i(TAG, "Reactive compaction succeeded: removed ${result.removedMessageCount} messages, " +
                    "summary ${result.summaryText.length} chars")
                result
            } else {
                tracking.consecutiveFailures++
                Log.w(TAG, "Reactive compaction returned wasCompacted=false (failure ${tracking.consecutiveFailures})")
                null
            }
        } catch (e: Exception) {
            tracking.consecutiveFailures++
            Log.e(TAG, "Reactive compaction failed (failure ${tracking.consecutiveFailures}): ${e.message}", e)
            null
        }
    }
}
