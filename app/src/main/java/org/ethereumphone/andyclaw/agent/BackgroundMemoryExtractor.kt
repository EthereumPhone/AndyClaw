package org.ethereumphone.andyclaw.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.LlmClient
import org.ethereumphone.andyclaw.llm.LocalLlmClient
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.llm.MessageContent
import org.ethereumphone.andyclaw.llm.MessagesRequest
import org.ethereumphone.andyclaw.memory.MemoryManager
import org.ethereumphone.andyclaw.memory.model.MemorySource
import org.ethereumphone.andyclaw.memory.model.MemoryType

/**
 * Automatically extracts savable memories from conversation history after each
 * agent loop completion.
 *
 * Ported from Claude Code's `extractMemories.ts`. Key difference: uses a single
 * structured-output LLM call instead of a multi-turn forked agent (cheaper on mobile).
 *
 * Features:
 * - Coalesced execution: stashes context if already running, runs trailing after completion
 * - Mutual exclusion with main agent: skips if main agent already wrote memories this turn
 * - Skip for local models (too small for quality extraction)
 */
class BackgroundMemoryExtractor(
    private val client: LlmClient,
    private val memoryManager: MemoryManager,
    private val modelId: String,
) {
    companion object {
        private const val TAG = "BgMemExtract"
        private const val EXTRACTION_MAX_TOKENS = 2048
        /** Minimum new messages required before extraction attempt. */
        private const val MIN_NEW_MESSAGES = 2
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var inProgress = false
    @Volatile private var pendingHistory: List<Message>? = null
    private var lastProcessedMessageCount = 0

    /**
     * Request background extraction. If already in progress, stash for trailing run.
     * Called fire-and-forget from ChatViewModel after each agent loop completion.
     */
    fun extractIfNeeded(
        conversationHistory: List<Message>,
        scope: CoroutineScope,
    ) {
        // Skip for local models — too small for quality extraction
        if (client is LocalLlmClient) return

        val newMessageCount = conversationHistory.size - lastProcessedMessageCount
        if (newMessageCount < MIN_NEW_MESSAGES) return

        // Check if main agent already wrote memories this turn
        if (hasMemoryStoreThisTurn(conversationHistory)) {
            Log.d(TAG, "Main agent already stored memories this turn, skipping extraction")
            lastProcessedMessageCount = conversationHistory.size
            return
        }

        if (inProgress) {
            Log.d(TAG, "Extraction in progress, stashing context for trailing run")
            pendingHistory = conversationHistory.toList()
            return
        }

        scope.launch(Dispatchers.IO) {
            runExtraction(conversationHistory.toList())
        }
    }

    private suspend fun runExtraction(history: List<Message>) {
        inProgress = true
        try {
            val newMessageCount = history.size - lastProcessedMessageCount

            // Build existing memory manifest for dedup
            val existingMemories = try {
                memoryManager.list(limit = 20)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to list existing memories: ${e.message}")
                emptyList()
            }
            val manifest = existingMemories.joinToString("\n") { mem ->
                "- [${mem.type?.name ?: mem.source.name}] ${mem.content.take(100)}"
            }

            val userPrompt = ExtractionPrompts.buildExtractionPrompt(newMessageCount, manifest)

            val request = MessagesRequest(
                model = modelId,
                maxTokens = EXTRACTION_MAX_TOKENS,
                system = ExtractionPrompts.EXTRACTION_SYSTEM_PROMPT,
                messages = history + listOf(Message.user(userPrompt)),
                stream = false,
                temperature = 0.1f,
            )

            Log.i(TAG, "Running extraction: $newMessageCount new messages, ${existingMemories.size} existing memories")
            val response = client.sendMessage(request)

            val responseText = response.content
                .filterIsInstance<ContentBlock.TextBlock>()
                .joinToString("") { it.text }
                .trim()

            val memories = parseExtractionResponse(responseText)
            Log.i(TAG, "Extraction complete: ${memories.size} memories to save")

            for (memory in memories) {
                try {
                    memoryManager.store(
                        content = memory.content,
                        source = MemorySource.CONVERSATION,
                        tags = memory.tags,
                        importance = memory.importance,
                        type = memory.type,
                    )
                    Log.d(TAG, "Saved memory: type=${memory.type}, tags=${memory.tags}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save extracted memory: ${e.message}")
                }
            }

            lastProcessedMessageCount = history.size
        } catch (e: Exception) {
            Log.w(TAG, "Background extraction failed: ${e.message}", e)
        } finally {
            inProgress = false
            // Trailing run for stashed context
            val trailing = pendingHistory
            pendingHistory = null
            if (trailing != null) {
                Log.d(TAG, "Running trailing extraction")
                runExtraction(trailing)
            }
        }
    }

    /**
     * Checks if the main agent called memory_store during the latest turn.
     */
    private fun hasMemoryStoreThisTurn(history: List<Message>): Boolean {
        // Check last few messages for memory_store tool calls
        for (msg in history.takeLast(4)) {
            val blocks = (msg.content as? MessageContent.Blocks)?.blocks ?: continue
            for (block in blocks) {
                if (block is ContentBlock.ToolUseBlock && block.name == "memory_store") {
                    return true
                }
            }
        }
        return false
    }

    /** Parsed memory from the extraction response. */
    data class ExtractedMemory(
        val content: String,
        val type: MemoryType?,
        val tags: List<String>,
        val importance: Float,
    )

    /**
     * Parses the JSON array response from the extraction LLM call.
     */
    private fun parseExtractionResponse(responseText: String): List<ExtractedMemory> {
        // Extract JSON array from response (may have surrounding text)
        val jsonText = extractJsonArray(responseText) ?: return emptyList()

        return try {
            val array = json.parseToJsonElement(jsonText).jsonArray
            array.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val typeStr = obj["type"]?.jsonPrimitive?.contentOrNull
                    val type = typeStr?.let { runCatching { MemoryType.valueOf(it) }.getOrNull() }
                    val tags = obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    val importance = obj["importance"]?.jsonPrimitive?.floatOrNull ?: 0.5f

                    ExtractedMemory(content, type, tags, importance.coerceIn(0f, 1f))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse memory object: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse extraction response: ${e.message}")
            emptyList()
        }
    }

    /** Extracts the first JSON array from a string that may contain surrounding text. */
    private fun extractJsonArray(text: String): String? {
        val start = text.indexOf('[')
        if (start == -1) return null
        val end = text.lastIndexOf(']')
        if (end == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }
}
