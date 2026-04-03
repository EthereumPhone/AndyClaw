package org.ethereumphone.andyclaw.agent

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.LlmClient
import org.ethereumphone.andyclaw.llm.MessagesRequest
import org.ethereumphone.andyclaw.llm.Message as LlmMessage
import org.ethereumphone.andyclaw.memory.model.MemoryEntry

/**
 * Optional LLM-based reranker for memory search results.
 *
 * After hybrid search (FTS4 + vector) produces candidates, this sends them
 * to a cheap LLM call that reads the snippets + user context and selects
 * which ones are actually relevant.
 *
 * Designed to be cheap:
 * - Small max_tokens (128 — just returns a JSON array of IDs)
 * - Minimal system prompt
 * - Only processes pre-filtered candidates (not the full memory store)
 *
 * Cost: ~500-800 input tokens + 128 output tokens per query.
 */
class MemoryReranker(
    private val client: LlmClient,
    private val modelId: String,
) {
    companion object {
        private const val TAG = "MemoryReranker"
        private const val MAX_OUTPUT_TOKENS = 128

        private val SYSTEM_PROMPT = """
You select which memories are relevant to the user's current query.

You receive: the user's message, recent conversation context, and a numbered list of memory candidates.

Respond with ONLY a JSON array of the relevant memory numbers (1-indexed).
Example: [1, 3, 5]

Rules:
- Only include memories that are clearly relevant to what the user is doing RIGHT NOW
- If none are relevant, return []
- Do NOT explain your reasoning — just the JSON array
""".trimIndent()

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    /**
     * Reranks memory candidates using an LLM call.
     *
     * @param candidates Pre-filtered memory entries from hybrid search.
     * @param userMessage The current user message.
     * @param conversationContext Brief recent conversation context.
     * @param maxResults Maximum memories to keep after reranking.
     * @return Filtered list of relevant entries (subset of [candidates], in original order).
     *   Falls back to returning [candidates] unchanged if the LLM call fails.
     */
    suspend fun rerank(
        candidates: List<MemoryEntry>,
        userMessage: String,
        conversationContext: String,
        maxResults: Int,
    ): List<MemoryEntry> {
        if (candidates.isEmpty()) return candidates

        // Build the prompt with numbered candidates
        val prompt = buildString {
            appendLine("## User's message")
            appendLine(userMessage.take(300))
            appendLine()
            if (conversationContext.isNotBlank()) {
                appendLine("## Recent context")
                appendLine(conversationContext.take(300))
                appendLine()
            }
            appendLine("## Memory candidates")
            for ((i, entry) in candidates.withIndex()) {
                val typeLabel = entry.type?.name ?: entry.source.name
                val age = MemoryPromptBuilder.daysSince(entry.updatedAt)
                val ageStr = if (age == 0) "today" else "${age}d ago"
                appendLine("${i + 1}. [$typeLabel, $ageStr] ${entry.content.take(200)}")
            }
            appendLine()
            appendLine("Which memories are relevant? Return a JSON array of numbers.")
        }

        return try {
            val request = MessagesRequest(
                model = modelId,
                maxTokens = MAX_OUTPUT_TOKENS,
                system = SYSTEM_PROMPT,
                messages = listOf(LlmMessage.user(prompt)),
                stream = false,
                temperature = 0.0f,
            )

            Log.d(TAG, "Reranking ${candidates.size} candidates (prompt ${prompt.length} chars)")
            val startMs = System.currentTimeMillis()
            val response = client.sendMessage(request)
            val elapsedMs = System.currentTimeMillis() - startMs

            val responseText = response.content
                .filterIsInstance<ContentBlock.TextBlock>()
                .joinToString("") { it.text }
                .trim()

            val selectedIndices = parseIndices(responseText, candidates.size)
            Log.i(TAG, "Reranked in ${elapsedMs}ms: ${candidates.size} → ${selectedIndices.size} " +
                "(selected: $selectedIndices, usage: in=${response.usage?.inputTokens}, out=${response.usage?.outputTokens})")

            if (selectedIndices.isEmpty()) {
                // LLM said nothing is relevant — trust it
                emptyList()
            } else {
                selectedIndices
                    .mapNotNull { idx -> candidates.getOrNull(idx) }
                    .take(maxResults)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reranking failed, falling back to unranked: ${e.message}")
            candidates.take(maxResults) // graceful fallback
        }
    }

    /**
     * Parses a JSON array of 1-indexed integers from the LLM response.
     * Handles messy output (surrounding text, brackets, etc).
     */
    private fun parseIndices(text: String, maxIndex: Int): List<Int> {
        // Extract JSON array from response
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start == -1 || end == -1 || end <= start) return emptyList()

        return try {
            json.parseToJsonElement(text.substring(start, end + 1))
                .jsonArray
                .mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
                .filter { it in 1..maxIndex }
                .map { it - 1 } // convert 1-indexed to 0-indexed
                .distinct()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse reranker output: ${e.message}")
            emptyList()
        }
    }
}
