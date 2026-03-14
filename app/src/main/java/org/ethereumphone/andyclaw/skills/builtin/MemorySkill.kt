package org.ethereumphone.andyclaw.skills.builtin

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.ethereumphone.andyclaw.memory.MemoryManager
import org.ethereumphone.andyclaw.memory.model.MemorySource
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition

/**
 * Skill providing long-term memory capabilities to the agent.
 *
 * Exposes tools for storing, searching, and deleting memories.
 * Mirrors OpenClaw's `memory_search` / `memory_get` tool pattern,
 * adapted for the Android memory subsystem.
 */
class MemorySkill(
    private val memoryManager: MemoryManager,
) : AndyClawSkill {

    companion object {
        private const val TAG = "MemorySkill"
        private const val FALLBACK_MIN_SCORE = 0.10f
    }

    override val id: String = "memory"
    override val name: String = "Memory"

    override val baseManifest = SkillManifest(
        description = "Long-term memory: store facts, preferences, and context that persist across conversations. " +
            "Use memory_search before answering questions that might benefit from past context. " +
            "Use memory_store to save important information the user shares. " +
            "Use memory_list to see all stored memories (useful when the user asks what you remember).",
        tools = listOf(
            ToolDefinition(
                name = "memory_search",
                description = "Search long-term memory for relevant context. Uses hybrid keyword + semantic search. " +
                    "Call this when the user asks about something that might have been discussed before, " +
                    "or when you need context about user preferences, past decisions, or prior conversations.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("query") {
                            put("type", "string")
                            put("description", "Natural language search query")
                        }
                        putJsonObject("max_results") {
                            put("type", "integer")
                            put("description", "Maximum number of results (default: 6)")
                        }
                        putJsonObject("tags") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "string") }
                            put("description", "Filter to memories with ALL of these tags")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("query")) }
                },
            ),
            ToolDefinition(
                name = "memory_list",
                description = "List all stored long-term memories, newest first. " +
                    "Use this when the user asks what you remember, wants a summary of all memories, " +
                    "or when you need a broad overview rather than a targeted search.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("limit") {
                            put("type", "integer")
                            put("description", "Maximum number of memories to return (default: 20)")
                        }
                        putJsonObject("source") {
                            put("type", "string")
                            put("description", "Filter by source: MANUAL, CONVERSATION, or SYSTEM (default: all)")
                        }
                    }
                },
            ),
            ToolDefinition(
                name = "memory_store",
                description = "Store a new long-term memory. Use this to save important facts, user preferences, " +
                    "decisions, or any context that should persist across conversations. " +
                    "Be concise but include enough context for future retrieval.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("content") {
                            put("type", "string")
                            put("description", "The text to remember — be specific and self-contained")
                        }
                        putJsonObject("tags") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "string") }
                            put("description", "Categorisation tags (e.g. 'preference', 'contact', 'project')")
                        }
                        putJsonObject("importance") {
                            put("type", "number")
                            put("description", "Importance weight 0.0–1.0 (default: 0.5). Higher values surface first in search.")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("content")) }
                },
            ),
            ToolDefinition(
                name = "memory_delete",
                description = "Delete a specific memory by its ID. Use when the user asks to forget something " +
                    "or when a memory is outdated/incorrect.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("memory_id") {
                            put("type", "string")
                            put("description", "The unique ID of the memory to delete")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("memory_id")) }
                },
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        Log.d(TAG, "Executing tool: $tool")
        return when (tool) {
            "memory_search" -> executeSearch(params)
            "memory_list" -> executeList(params)
            "memory_store" -> executeStore(params)
            "memory_delete" -> executeDelete(params)
            else -> SkillResult.Error("Unknown memory tool: $tool")
        }
    }

    private suspend fun executeSearch(params: JsonObject): SkillResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: query")
        val maxResults = params["max_results"]?.jsonPrimitive?.int ?: 6
        val tags = params["tags"]?.jsonArray?.map { it.jsonPrimitive.content }

        Log.d(TAG, "memory_search: query=\"$query\", maxResults=$maxResults, tags=$tags")

        return try {
            var results = memoryManager.search(
                query = query,
                maxResults = maxResults,
                tags = tags,
            )

            // Fallback: if the default threshold filtered everything out,
            // retry with a much lower threshold to avoid false negatives.
            if (results.isEmpty()) {
                Log.d(TAG, "memory_search: no results at default threshold, retrying with minScore=$FALLBACK_MIN_SCORE")
                results = memoryManager.search(
                    query = query,
                    maxResults = maxResults,
                    minScore = FALLBACK_MIN_SCORE,
                    tags = tags,
                )
            }

            if (results.isEmpty()) {
                Log.i(TAG, "memory_search: no results for \"$query\" (including fallback)")
                SkillResult.Success("No memories found matching: \"$query\"")
            } else {
                Log.i(TAG, "memory_search: ${results.size} result(s) for \"$query\"")
                val formatted = results.mapIndexed { i, r ->
                    buildString {
                        appendLine("${i + 1}. [id: ${r.memoryId}] (score: ${"%.2f".format(r.score)})")
                        appendLine("   ${r.snippet}")
                        if (r.tags.isNotEmpty()) {
                            appendLine("   tags: ${r.tags.joinToString(", ")}")
                        }
                    }
                }.joinToString("\n")
                SkillResult.Success("Found ${results.size} relevant memories:\n\n$formatted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "memory_search failed: ${e.message}", e)
            SkillResult.Error("Memory search failed: ${e.message}")
        }
    }

    private suspend fun executeList(params: JsonObject): SkillResult {
        val limit = params["limit"]?.jsonPrimitive?.int ?: 20
        val sourceStr = params["source"]?.jsonPrimitive?.content
        val source = sourceStr?.let {
            runCatching { MemorySource.valueOf(it.uppercase()) }.getOrNull()
        }

        Log.d(TAG, "memory_list: limit=$limit, source=$source")

        return try {
            val entries = memoryManager.list(source = source, limit = limit)

            if (entries.isEmpty()) {
                Log.i(TAG, "memory_list: no memories stored")
                SkillResult.Success("No memories stored yet.")
            } else {
                Log.i(TAG, "memory_list: returning ${entries.size} memory/memories")
                val formatted = entries.mapIndexed { i, entry ->
                    buildString {
                        appendLine("${i + 1}. [id: ${entry.id}] (source: ${entry.source}, importance: ${"%.1f".format(entry.importance)})")
                        appendLine("   ${entry.content.take(300)}${if (entry.content.length > 300) "..." else ""}")
                        if (entry.tags.isNotEmpty()) {
                            appendLine("   tags: ${entry.tags.joinToString(", ")}")
                        }
                    }
                }.joinToString("\n")
                SkillResult.Success("${entries.size} stored memory/memories:\n\n$formatted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "memory_list failed: ${e.message}", e)
            SkillResult.Error("Failed to list memories: ${e.message}")
        }
    }

    private suspend fun executeStore(params: JsonObject): SkillResult {
        val content = params["content"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: content")
        val tags = params["tags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val importance = params["importance"]?.jsonPrimitive?.float ?: 0.5f

        Log.d(TAG, "memory_store: contentLength=${content.length}, tags=$tags, importance=$importance")

        return try {
            val entry = memoryManager.store(
                content = content,
                source = MemorySource.MANUAL,
                tags = tags,
                importance = importance,
            )
            Log.i(TAG, "memory_store: stored id=${entry.id}")
            SkillResult.Success("Memory stored (id: ${entry.id}). Tags: ${tags.ifEmpty { listOf("none") }.joinToString(", ")}")
        } catch (e: Exception) {
            Log.e(TAG, "memory_store failed: ${e.message}", e)
            SkillResult.Error("Failed to store memory: ${e.message}")
        }
    }

    private suspend fun executeDelete(params: JsonObject): SkillResult {
        val memoryId = params["memory_id"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: memory_id")

        Log.d(TAG, "memory_delete: id=$memoryId")

        return try {
            val existing = memoryManager.get(memoryId)
            if (existing == null) {
                Log.w(TAG, "memory_delete: not found id=$memoryId")
                SkillResult.Error("Memory not found: $memoryId")
            } else {
                memoryManager.delete(memoryId)
                Log.i(TAG, "memory_delete: deleted id=$memoryId")
                SkillResult.Success("Memory deleted: $memoryId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "memory_delete failed: ${e.message}", e)
            SkillResult.Error("Failed to delete memory: ${e.message}")
        }
    }
}
