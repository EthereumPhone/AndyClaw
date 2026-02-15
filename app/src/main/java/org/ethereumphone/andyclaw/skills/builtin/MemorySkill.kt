package org.ethereumphone.andyclaw.skills.builtin

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

    override val id: String = "memory"
    override val name: String = "Memory"

    override val baseManifest = SkillManifest(
        description = "Long-term memory: store facts, preferences, and context that persist across conversations. " +
            "Use memory_search before answering questions that might benefit from past context. " +
            "Use memory_store to save important information the user shares.",
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
        return when (tool) {
            "memory_search" -> executeSearch(params)
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

        return try {
            val results = memoryManager.search(
                query = query,
                maxResults = maxResults,
                tags = tags,
            )

            if (results.isEmpty()) {
                SkillResult.Success("No memories found matching: \"$query\"")
            } else {
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
            SkillResult.Error("Memory search failed: ${e.message}")
        }
    }

    private suspend fun executeStore(params: JsonObject): SkillResult {
        val content = params["content"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: content")
        val tags = params["tags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val importance = params["importance"]?.jsonPrimitive?.float ?: 0.5f

        return try {
            val entry = memoryManager.store(
                content = content,
                source = MemorySource.MANUAL,
                tags = tags,
                importance = importance,
            )
            SkillResult.Success("Memory stored (id: ${entry.id}). Tags: ${tags.ifEmpty { listOf("none") }.joinToString(", ")}")
        } catch (e: Exception) {
            SkillResult.Error("Failed to store memory: ${e.message}")
        }
    }

    private suspend fun executeDelete(params: JsonObject): SkillResult {
        val memoryId = params["memory_id"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: memory_id")

        return try {
            val existing = memoryManager.get(memoryId)
            if (existing == null) {
                SkillResult.Error("Memory not found: $memoryId")
            } else {
                memoryManager.delete(memoryId)
                SkillResult.Success("Memory deleted: $memoryId")
            }
        } catch (e: Exception) {
            SkillResult.Error("Failed to delete memory: ${e.message}")
        }
    }
}
