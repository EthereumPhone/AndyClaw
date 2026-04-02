package org.ethereumphone.andyclaw.agent

import android.util.Log
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.LlmClient
import org.ethereumphone.andyclaw.llm.LocalLlmClient
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.llm.MessageContent
import org.ethereumphone.andyclaw.llm.MessagesRequest

/**
 * Two-layer context compaction engine.
 *
 * **Layer 1 – Microcompact** (no LLM cost): truncate old assistant text,
 * collapse redundant whitespace.
 *
 * **Layer 2 – LLM Summarization**: send older messages to the LLM with
 * a preservation-nudge prompt, replace them with a structured summary.
 * Skipped for local models (too small for quality summarization).
 *
 * Inspired by Claude Code's compaction strategy and Google ADK's
 * `EventsCompactionConfig` (overlap for continuity between compression cycles).
 */
class ContextCompactor(
    private val client: LlmClient,
    private val budgetConfig: BudgetConfig,
) {
    companion object {
        private const val TAG = "ContextCompactor"
        private const val MICROCOMPACT_TRUNCATE_CHARS = 300
        private const val SUMMARY_MAX_TOKENS = 1024
        private const val SUMMARY_BEGIN = "<context_summary>"
        private const val SUMMARY_END = "</context_summary>"
        private const val MEMORY_NUDGE = "This is a compacted summary of older messages in this conversation. " +
            "If you need more details about something mentioned here, " +
            "use the search_memory skill to retrieve relevant context from long-term memory."

        private val WHITESPACE_RUN = Regex("[ \\t]{3,}")
        private val BLANK_LINES = Regex("\\n{3,}")

        private val SUMMARIZATION_SYSTEM_PROMPT = """
Summarize this conversation in plain text. Be SHORTER than the original.

Rules:
- Write plain sentences, no markdown, no bullet points, no headers, no asterisks.
- Include EVERY distinct question the user asked and the answer they received.
- Preserve exact values: times, dates, numbers, names, URLs, paths, IDs, code.
- Do not add interpretation, commentary, or meta-text like "Summary:" or "Status: Completed".
- Do not invent information that wasn't in the conversation.
- If the user asked about multiple things, mention all of them.
""".trimIndent()
    }

    data class CompactionResult(
        val compactedHistory: List<Message>,
        val summaryText: String,
        val removedMessageCount: Int,
        val wasCompacted: Boolean,
    )

    /**
     * Attempt to compact the conversation history.
     *
     * @param history The current conversation history (user/assistant messages).
     * @param modelId The model ID to use for the summarization LLM call.
     * @return A [CompactionResult]; check [CompactionResult.wasCompacted] before using.
     */
    /**
     * @param keepRecentOverride If non-null, overrides the preset's keepRecent value.
     *        Used by manual /compact to bypass automatic preset rules.
     */
    suspend fun compact(
        history: List<Message>,
        modelId: String,
        keepRecentOverride: Int? = null,
    ): CompactionResult {
        val keepRecent = keepRecentOverride ?: budgetConfig.preset.historySummarizationKeepRecent
        val overlap = budgetConfig.preset.compactionOverlap
        val preset = budgetConfig.preset
        val isManual = keepRecentOverride != null

        Log.i(TAG, "=== compact() START === historySize=${history.size}, keepRecent=$keepRecent, " +
            "overlap=$overlap, modelId=$modelId, clientType=${client.javaClass.simpleName}, manual=$isManual")
        Log.d(TAG, "Preset: id=${preset.id}, threshold=${preset.compactionThreshold}, " +
            "interval=${preset.compactionInterval}, historySummarization=${preset.historySummarization}")

        // Log history composition
        val roleCounts = history.groupingBy { it.role }.eachCount()
        Log.d(TAG, "History composition: $roleCounts")
        val totalChars = history.sumOf { extractText(it).length }
        Log.d(TAG, "History total chars: $totalChars (~${totalChars / 4} tokens est)")

        // Minimum history check: manual compaction skips this entirely,
        // auto-compact requires keepRecent + 2.
        if (!isManual && history.size <= keepRecent + 2) {
            Log.i(TAG, "SKIP: history too short (${history.size} <= ${keepRecent + 2}), nothing to compact")
            return CompactionResult(history, "", 0, wasCompacted = false)
        }
        if (history.size < 2) {
            Log.i(TAG, "SKIP: need at least 2 messages to compact")
            return CompactionResult(history, "", 0, wasCompacted = false)
        }

        // For manual compaction, clamp keepRecent so there's at least 1 message to summarize
        val effectiveKeepRecent = keepRecent.coerceAtMost(history.size - 1)
        if (effectiveKeepRecent != keepRecent) {
            Log.d(TAG, "Clamped keepRecent: $keepRecent → $effectiveKeepRecent (historySize=${history.size})")
        }

        // Layer 1: Microcompact — always runs
        Log.i(TAG, "--- Layer 1: Microcompact ---")
        val microcompactStart = System.currentTimeMillis()
        val microcompacted = microcompact(history, effectiveKeepRecent)
        val microcompactMs = System.currentTimeMillis() - microcompactStart
        val microcompactChanged = microcompacted !== history
        if (microcompactChanged) {
            val newTotalChars = microcompacted.sumOf { extractText(it).length }
            val savedChars = totalChars - newTotalChars
            Log.i(TAG, "Microcompact: ${savedChars} chars saved (${totalChars} → ${newTotalChars}), took ${microcompactMs}ms")
        } else {
            Log.d(TAG, "Microcompact: no changes needed, took ${microcompactMs}ms")
        }

        // Layer 2: LLM Summarization — skip for local models
        if (client is LocalLlmClient) {
            Log.i(TAG, "SKIP Layer 2: local model (${client.javaClass.simpleName}) — microcompact only")
            val result = if (microcompactChanged) {
                CompactionResult(microcompacted, "", 0, wasCompacted = true)
            } else {
                CompactionResult(history, "", 0, wasCompacted = false)
            }
            Log.i(TAG, "=== compact() END === wasCompacted=${result.wasCompacted} (microcompact only)")
            return result
        }

        Log.i(TAG, "--- Layer 2: LLM Summarization ---")
        return try {
            val summarizeStart = System.currentTimeMillis()
            val result = summarize(microcompacted, modelId, effectiveKeepRecent, overlap)
            val summarizeMs = System.currentTimeMillis() - summarizeStart
            Log.i(TAG, "=== compact() END === wasCompacted=${result.wasCompacted}, " +
                "removedMessages=${result.removedMessageCount}, " +
                "summaryLength=${result.summaryText.length} chars, " +
                "resultHistorySize=${result.compactedHistory.size}, " +
                "llmCallMs=${summarizeMs}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "LLM summarization FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            val result = if (microcompactChanged) {
                Log.i(TAG, "Falling back to microcompact-only result")
                CompactionResult(microcompacted, "", 0, wasCompacted = true)
            } else {
                Log.i(TAG, "No fallback available, returning original history")
                CompactionResult(history, "", 0, wasCompacted = false)
            }
            Log.i(TAG, "=== compact() END (error fallback) === wasCompacted=${result.wasCompacted}")
            result
        }
    }

    /**
     * Layer 1: Truncate old assistant messages and collapse whitespace.
     */
    private fun microcompact(history: List<Message>, keepRecent: Int): List<Message> {
        if (history.size <= keepRecent) return history

        val boundary = history.size - keepRecent
        val result = history.toMutableList()
        var changed = false
        var truncatedCount = 0
        var whitespaceCount = 0

        for (i in 0 until boundary) {
            val msg = result[i]
            val originalLen = extractText(msg).length
            val compacted = microcompactMessage(msg)
            if (compacted !== msg) {
                val newLen = extractText(compacted).length
                result[i] = compacted
                changed = true
                if (newLen < originalLen) {
                    if (msg.role == "assistant" && originalLen > MICROCOMPACT_TRUNCATE_CHARS) truncatedCount++
                    else whitespaceCount++
                    Log.d(TAG, "Microcompact msg[$i] role=${msg.role}: ${originalLen} → ${newLen} chars (-${originalLen - newLen})")
                }
            }
        }

        Log.d(TAG, "Microcompact stats: processed ${boundary}/${history.size} messages, " +
            "truncated=$truncatedCount, whitespace_cleaned=$whitespaceCount, changed=$changed")
        return if (changed) result else history
    }

    private fun microcompactMessage(msg: Message): Message {
        return when (val content = msg.content) {
            is MessageContent.Text -> {
                val compacted = compactText(content.value, msg.role == "assistant")
                if (compacted != content.value) {
                    msg.copy(content = MessageContent.Text(compacted))
                } else msg
            }
            is MessageContent.Blocks -> {
                var blockChanged = false
                val compactedBlocks = content.blocks.map { block ->
                    if (block is ContentBlock.TextBlock) {
                        val compacted = compactText(block.text, msg.role == "assistant")
                        if (compacted != block.text) {
                            blockChanged = true
                            ContentBlock.TextBlock(compacted)
                        } else block
                    } else block
                }
                if (blockChanged) msg.copy(content = MessageContent.Blocks(compactedBlocks))
                else msg
            }
        }
    }

    private fun compactText(text: String, truncate: Boolean): String {
        var result = text
            .replace(WHITESPACE_RUN, " ")
            .replace(BLANK_LINES, "\n\n")
        if (truncate && result.length > MICROCOMPACT_TRUNCATE_CHARS) {
            result = result.take(MICROCOMPACT_TRUNCATE_CHARS) + "\n[... truncated]"
        }
        return result
    }

    /**
     * Layer 2: LLM-based summarization of older messages.
     */
    private suspend fun summarize(
        history: List<Message>,
        modelId: String,
        keepRecent: Int,
        overlap: Int,
    ): CompactionResult {
        val boundary = history.size - keepRecent
        val toSummarize = history.subList(0, boundary)
        val toKeep = history.subList(boundary, history.size)

        Log.d(TAG, "Summarize split: toSummarize=${toSummarize.size} messages, toKeep=${toKeep.size} messages")

        if (toSummarize.size <= 1) {
            Log.i(TAG, "SKIP summarize: only ${toSummarize.size} message(s) in summarize window")
            return CompactionResult(history, "", 0, wasCompacted = false)
        }

        // Log what we're summarizing
        for ((i, msg) in toSummarize.withIndex()) {
            val text = extractText(msg)
            Log.d(TAG, "  toSummarize[$i] role=${msg.role} len=${text.length} preview=\"${text.take(80)}${if (text.length > 80) "..." else ""}\"")
        }
        for ((i, msg) in toKeep.withIndex()) {
            val text = extractText(msg)
            Log.d(TAG, "  toKeep[$i] role=${msg.role} len=${text.length} preview=\"${text.take(80)}${if (text.length > 80) "..." else ""}\"")
        }

        // Build the content to summarize, including any prior summary
        val summaryInput = buildString {
            // Check for prior summary (rolling summarization)
            val priorSummary = toSummarize.firstOrNull()?.let { msg ->
                val text = extractText(msg)
                if (text.startsWith(SUMMARY_BEGIN)) text else null
            }
            if (priorSummary != null) {
                Log.i(TAG, "Rolling summarization: found prior summary (${priorSummary.length} chars)")
                appendLine("=== Previous Summary ===")
                appendLine(priorSummary
                    .removePrefix(SUMMARY_BEGIN).removeSuffix(SUMMARY_END).trim())
                appendLine()
                appendLine("=== New Messages Since Last Summary ===")
                // Skip the summary message itself when building transcript
                for (msg in toSummarize.drop(1)) {
                    appendLine(formatMessageForSummary(msg))
                }
            } else {
                Log.d(TAG, "No prior summary found, summarizing full transcript")
                appendLine("=== Conversation Transcript ===")
                for (msg in toSummarize) {
                    appendLine(formatMessageForSummary(msg))
                }
            }

            // Include overlap messages as bridge context
            if (overlap > 0 && toKeep.isNotEmpty()) {
                val overlapMsgs = toKeep.take(overlap.coerceAtMost(toKeep.size))
                Log.d(TAG, "Including ${overlapMsgs.size} overlap message(s) for continuity")
                appendLine()
                appendLine("=== Recent Context (for continuity) ===")
                for (msg in overlapMsgs) {
                    appendLine(formatMessageForSummary(msg))
                }
            }
        }

        Log.i(TAG, "LLM summarization request: model=$modelId, " +
            "inputChars=${summaryInput.length} (~${summaryInput.length / 4} tokens est), " +
            "maxTokens=$SUMMARY_MAX_TOKENS, systemPromptChars=${SUMMARIZATION_SYSTEM_PROMPT.length}")

        val request = MessagesRequest(
            model = modelId,
            maxTokens = SUMMARY_MAX_TOKENS,
            system = SUMMARIZATION_SYSTEM_PROMPT,
            messages = listOf(Message.user(summaryInput)),
            stream = false,
            temperature = 0.2f,
        )

        Log.d(TAG, "Sending summarization request to LLM...")
        val callStart = System.currentTimeMillis()
        val response = client.sendMessage(request)
        val callMs = System.currentTimeMillis() - callStart
        Log.i(TAG, "LLM response received in ${callMs}ms, " +
            "contentBlocks=${response.content.size}, " +
            "stopReason=${response.stopReason}, " +
            "usage=[input=${response.usage?.inputTokens ?: "?"}, output=${response.usage?.outputTokens ?: "?"}]")

        val summaryText = response.content
            .filterIsInstance<ContentBlock.TextBlock>()
            .joinToString("\n") { it.text }

        if (summaryText.isBlank()) {
            Log.w(TAG, "LLM returned EMPTY summary (contentBlocks=${response.content.map { it.javaClass.simpleName }})")
            return CompactionResult(history, "", 0, wasCompacted = false)
        }

        val compressionRatio = if (summaryInput.isNotEmpty()) {
            String.format("%.1f", summaryInput.length.toFloat() / summaryText.length)
        } else "N/A"
        Log.i(TAG, "Summary generated: ${summaryText.length} chars " +
            "(compression ratio: ${compressionRatio}x, " +
            "${summaryInput.length} input → ${summaryText.length} output)")
        Log.d(TAG, "Summary preview: \"${summaryText.take(200)}${if (summaryText.length > 200) "..." else ""}\"")

        // Build compacted history: summary message + kept recent messages
        val compactedHistory = mutableListOf<Message>()
        compactedHistory.add(Message.user("$SUMMARY_BEGIN\n$MEMORY_NUDGE\n\n$summaryText\n$SUMMARY_END"))
        compactedHistory.addAll(toKeep)

        Log.i(TAG, "Compacted history built: ${compactedHistory.size} messages " +
            "(1 summary + ${toKeep.size} kept), removed ${toSummarize.size} messages")

        return CompactionResult(
            compactedHistory = compactedHistory,
            summaryText = summaryText,
            removedMessageCount = toSummarize.size,
            wasCompacted = true,
        )
    }

    private fun formatMessageForSummary(msg: Message): String {
        val role = msg.role.uppercase()
        val text = extractText(msg)
        return "[$role]: $text"
    }

    private fun extractText(msg: Message): String {
        return when (val content = msg.content) {
            is MessageContent.Text -> content.value
            is MessageContent.Blocks -> content.blocks
                .filterIsInstance<ContentBlock.TextBlock>()
                .joinToString("\n") { it.text }
        }
    }
}
