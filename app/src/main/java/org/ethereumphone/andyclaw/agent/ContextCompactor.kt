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
    private val config: CompactionConfig,
    private val postCompactRestoration: PostCompactRestoration? = null,
) {
    companion object {
        private const val TAG = "ContextCompactor"
        private const val MICROCOMPACT_TRUNCATE_CHARS = 300
        private const val SUMMARY_MAX_TOKENS = 8192
        private const val SUMMARY_BEGIN = "<context_summary>"
        private const val SUMMARY_END = "</context_summary>"
        private const val MEMORY_NUDGE = "This is a compacted summary of older messages in this conversation. " +
            "If you need more details about something mentioned here, " +
            "use the search_memory skill to retrieve relevant context from long-term memory."

        private val WHITESPACE_RUN = Regex("[ \\t]{3,}")
        private val BLANK_LINES = Regex("\\n{3,}")

        // Regex for stripping the analysis scratchpad and extracting the summary
        private val ANALYSIS_BLOCK = Regex("<analysis>[\\s\\S]*?</analysis>")
        private val SUMMARY_XML = Regex("<summary>([\\s\\S]*?)</summary>")
        private val MULTI_BLANK = Regex("\\n{3,}")

        /**
         * Structured 9-section summarization prompt ported from Claude Code's compact/prompt.ts.
         * Produces an <analysis> scratchpad (stripped post-hoc) followed by a <summary> block.
         */
        private val SUMMARIZATION_SYSTEM_PROMPT = """
CRITICAL: Respond with TEXT ONLY. Do NOT call any tools.

- Do NOT use any tool calls whatsoever.
- You already have all the context you need in the conversation above.
- Tool calls will be REJECTED and will waste your only turn — you will fail the task.
- Your entire response must be plain text: an <analysis> block followed by a <summary> block.

Your task is to create a detailed summary of the conversation so far, paying close attention to the user's explicit requests and your previous actions.
This summary should be thorough in capturing technical details, code patterns, and architectural decisions that would be essential for continuing development work without losing context.

Before providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts and ensure you've covered all necessary points. In your analysis process:

1. Chronologically analyze each message and section of the conversation. For each section thoroughly identify:
   - The user's explicit requests and intents
   - Your approach to addressing the user's requests
   - Key decisions, technical concepts and code patterns
   - Specific details like:
     - file names
     - full code snippets
     - function signatures
     - file edits
   - Errors that you ran into and how you fixed them
   - Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
2. Double-check for technical accuracy and completeness, addressing each required element thoroughly.

Your summary should include the following sections:

1. Primary Request and Intent: Capture all of the user's explicit requests and intents in detail
2. Key Technical Concepts: List all important technical concepts, technologies, and frameworks discussed.
3. Files and Code Sections: Enumerate specific files and code sections examined, modified, or created. Pay special attention to the most recent messages and include full code snippets where applicable and include a summary of why this file read or edit is important.
4. Errors and fixes: List all errors that you ran into, and how you fixed them. Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
5. Problem Solving: Document problems solved and any ongoing troubleshooting efforts.
6. All user messages: List ALL user messages that are not tool results. These are critical for understanding the users' feedback and changing intent.
7. Pending Tasks: Outline any pending tasks that you have explicitly been asked to work on.
8. Current Work: Describe in detail precisely what was being worked on immediately before this summary request, paying special attention to the most recent messages from both user and assistant. Include file names and code snippets where applicable.
9. Optional Next Step: List the next step that you will take that is related to the most recent work you were doing. IMPORTANT: ensure that this step is DIRECTLY in line with the user's most recent explicit requests, and the task you were working on immediately before this summary request. If your last task was concluded, then only list next steps if they are explicitly in line with the users request. Do not start on tangential requests or really old requests that were already completed without confirming with the user first.
   If there is a next step, include direct quotes from the most recent conversation showing exactly what task you were working on and where you left off. This should be verbatim to ensure there's no drift in task interpretation.

Here's an example of how your output should be structured:

<example>
<analysis>
[Your thought process, ensuring all points are covered thoroughly and accurately]
</analysis>

<summary>
1. Primary Request and Intent:
   [Detailed description]

2. Key Technical Concepts:
   - [Concept 1]
   - [Concept 2]

3. Files and Code Sections:
   - [File Name 1]
      - [Summary of why this file is important]
      - [Summary of the changes made to this file, if any]
      - [Important Code Snippet]

4. Errors and fixes:
    - [Detailed description of error 1]:
      - [How you fixed the error]
      - [User feedback on the error if any]

5. Problem Solving:
   [Description of solved problems and ongoing troubleshooting]

6. All user messages:
    - [Detailed non tool use user message]

7. Pending Tasks:
   - [Task 1]

8. Current Work:
   [Precise description of current work]

9. Optional Next Step:
   [Optional Next step to take]

</summary>
</example>

Please provide your summary based on the conversation so far, following this structure and ensuring precision and thoroughness in your response.

REMINDER: Do NOT call any tools. Respond with text only.
""".trimIndent()

        /**
         * Strips the `<analysis>` scratchpad and extracts the `<summary>` content
         * from the raw LLM compaction output.
         */
        fun formatCompactSummary(rawSummary: String): String {
            // Strip the analysis scratchpad (it's a drafting aid, not part of the summary)
            var result = rawSummary.replace(ANALYSIS_BLOCK, "")

            // Extract summary content from <summary> tags
            val match = SUMMARY_XML.find(result)
            if (match != null) {
                result = match.groupValues[1].trim()
            }

            // Collapse excessive blank lines
            return result.replace(MULTI_BLANK, "\n\n").trim()
        }
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
        val keepRecent = keepRecentOverride ?: config.keepRecent
        val overlap = config.overlap
        val isManual = keepRecentOverride != null

        Log.i(TAG, "=== compact() START === historySize=${history.size}, keepRecent=$keepRecent, " +
            "overlap=$overlap, modelId=$modelId, clientType=${client.javaClass.simpleName}, manual=$isManual")
        Log.d(TAG, "Config: enabled=${config.enabled}, threshold=${config.threshold}, " +
            "interval=${config.interval}, microcompact=${config.microcompactEnabled}, llmSummary=${config.llmSummaryEnabled}")

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

        // Layer 1: Microcompact
        val microcompacted: List<Message>
        val microcompactChanged: Boolean
        if (config.microcompactEnabled) {
            Log.i(TAG, "--- Layer 1: Microcompact ---")
            val microcompactStart = System.currentTimeMillis()
            microcompacted = microcompact(history, effectiveKeepRecent)
            val microcompactMs = System.currentTimeMillis() - microcompactStart
            microcompactChanged = microcompacted !== history
            if (microcompactChanged) {
                val newTotalChars = microcompacted.sumOf { extractText(it).length }
                val savedChars = totalChars - newTotalChars
                Log.i(TAG, "Microcompact: ${savedChars} chars saved (${totalChars} → ${newTotalChars}), took ${microcompactMs}ms")
            } else {
                Log.d(TAG, "Microcompact: no changes needed, took ${microcompactMs}ms")
            }
        } else {
            Log.d(TAG, "Layer 1: Microcompact DISABLED by config")
            microcompacted = history
            microcompactChanged = false
        }

        // Layer 2: LLM Summarization
        if (!config.llmSummaryEnabled) {
            Log.d(TAG, "Layer 2: LLM Summary DISABLED by config")
            val result = if (microcompactChanged) {
                CompactionResult(microcompacted, "", 0, wasCompacted = true)
            } else {
                CompactionResult(history, "", 0, wasCompacted = false)
            }
            Log.i(TAG, "=== compact() END === wasCompacted=${result.wasCompacted} (microcompact only)")
            return result
        }
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
            temperature = 0.0f,
        )

        Log.d(TAG, "Sending summarization request to LLM...")
        val callStart = System.currentTimeMillis()
        val response = client.sendMessage(request)
        val callMs = System.currentTimeMillis() - callStart
        Log.i(TAG, "LLM response received in ${callMs}ms, " +
            "contentBlocks=${response.content.size}, " +
            "stopReason=${response.stopReason}, " +
            "usage=[input=${response.usage?.inputTokens ?: "?"}, output=${response.usage?.outputTokens ?: "?"}]")

        val rawSummary = response.content
            .filterIsInstance<ContentBlock.TextBlock>()
            .joinToString("\n") { it.text }

        if (rawSummary.isBlank()) {
            Log.w(TAG, "LLM returned EMPTY summary (contentBlocks=${response.content.map { it.javaClass.simpleName }})")
            return CompactionResult(history, "", 0, wasCompacted = false)
        }

        // Strip <analysis> scratchpad and extract <summary> content
        val summaryText = formatCompactSummary(rawSummary)
        Log.d(TAG, "formatCompactSummary: raw=${rawSummary.length} chars → formatted=${summaryText.length} chars")

        val compressionRatio = if (summaryInput.isNotEmpty()) {
            String.format("%.1f", summaryInput.length.toFloat() / summaryText.length)
        } else "N/A"
        Log.i(TAG, "Summary generated: ${summaryText.length} chars " +
            "(compression ratio: ${compressionRatio}x, " +
            "${summaryInput.length} input → ${summaryText.length} output)")
        Log.d(TAG, "Summary preview: \"${summaryText.take(200)}${if (summaryText.length > 200) "..." else ""}\"")

        // Build compacted history: summary message + optional restoration + kept recent messages
        val compactedHistory = mutableListOf<Message>()
        compactedHistory.add(Message.user("$SUMMARY_BEGIN\n$MEMORY_NUDGE\n\n$summaryText\n$SUMMARY_END"))

        // Inject post-compact restoration (discovered tools, recent file content)
        val restoration = postCompactRestoration?.buildRestoration(history)
        if (restoration != null) {
            compactedHistory.add(Message.assistant(listOf(ContentBlock.TextBlock("I'll note the following context that was preserved across compaction:"))))
            compactedHistory.add(Message.user(restoration))
            Log.i(TAG, "Post-compact restoration injected: ${restoration.length} chars")
        }

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
