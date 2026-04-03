package org.ethereumphone.andyclaw.agent

import android.util.Log
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.llm.MessageContent
import org.ethereumphone.andyclaw.skills.ToolSearchService

/**
 * Rebuilds critical context after compaction to prevent "context amnesia".
 *
 * Ported from Claude Code's post-compact attachment restoration which re-injects:
 * - Discovered tool names (so the model doesn't forget what tools are available)
 * - Recent file content / substantial tool results (so the model retains working memory)
 *
 * Budget-capped to avoid re-inflating the context window.
 */
class PostCompactRestoration(
    private val toolSearchService: ToolSearchService?,
) {
    companion object {
        private const val TAG = "PostCompactRestore"
        /** Maximum number of substantial tool results to restore. */
        const val MAX_FILES_TO_RESTORE = 5
        /** Maximum characters per restored tool result (~5K tokens). */
        const val MAX_CHARS_PER_FILE = 20_000
        /** Total budget for restoration text (~50K tokens). */
        const val TOTAL_BUDGET_CHARS = 200_000
        /** Minimum tool result length to be considered "substantial". */
        private const val MIN_SUBSTANTIAL_LENGTH = 200
    }

    /**
     * Builds a restoration text to inject after compaction.
     *
     * @param preCompactHistory The full history before compaction (used to extract
     *   recent substantial tool results).
     * @return A restoration string to inject as a user message, or null if nothing to restore.
     */
    fun buildRestoration(preCompactHistory: List<Message>): String? {
        val sections = mutableListOf<String>()

        // 1. Discovered tools
        val discovered = toolSearchService?.getDiscoveredToolNames()
        if (!discovered.isNullOrEmpty()) {
            sections.add(
                "## Discovered Tools\n" +
                "The following tools were discovered and are available: ${discovered.joinToString(", ")}"
            )
            Log.d(TAG, "Restoring ${discovered.size} discovered tool names")
        }

        // 2. Recent substantial tool results (file reads, shell output, etc.)
        val recentContent = extractRecentToolContent(preCompactHistory)
        if (recentContent.isNotEmpty()) {
            val fileSection = buildString {
                appendLine("## Recently Read Content")
                for ((toolName, content) in recentContent) {
                    appendLine("### $toolName")
                    appendLine(content)
                    appendLine()
                }
            }
            sections.add(fileSection)
            Log.d(TAG, "Restoring ${recentContent.size} recent tool results")
        }

        if (sections.isEmpty()) {
            Log.d(TAG, "Nothing to restore after compaction")
            return null
        }

        val restoration = sections.joinToString("\n\n")

        // Budget cap
        val result = if (restoration.length > TOTAL_BUDGET_CHARS) {
            Log.i(TAG, "Restoration truncated: ${restoration.length} → $TOTAL_BUDGET_CHARS chars")
            restoration.take(TOTAL_BUDGET_CHARS) + "\n[... truncated to token budget]"
        } else {
            restoration
        }

        Log.i(TAG, "Built restoration: ${result.length} chars")
        return result
    }

    /**
     * Walks the history in reverse and extracts the most recent substantial
     * tool result texts (file reads, shell output, etc.).
     */
    private fun extractRecentToolContent(history: List<Message>): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()

        for (msg in history.asReversed()) {
            if (results.size >= MAX_FILES_TO_RESTORE) break

            val blocks = (msg.content as? MessageContent.Blocks)?.blocks ?: continue

            // Find tool_use blocks to get tool names (they appear before their results)
            val toolNames = blocks.filterIsInstance<ContentBlock.ToolUseBlock>()
                .associate { it.id to it.name }

            for (block in blocks) {
                if (results.size >= MAX_FILES_TO_RESTORE) break
                if (block !is ContentBlock.ToolResult) continue

                val text = block.content
                if (text.length < MIN_SUBSTANTIAL_LENGTH) continue

                val toolName = toolNames[block.toolUseId] ?: "tool_result"
                val capped = if (text.length > MAX_CHARS_PER_FILE) {
                    text.take(MAX_CHARS_PER_FILE) + "\n[... truncated]"
                } else {
                    text
                }
                results.add(toolName to capped)
            }
        }

        return results
    }
}
