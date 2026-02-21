package org.ethereumphone.andyclaw.llm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Bidirectional conversion between Anthropic message format (used internally)
 * and OpenAI chat completion format (used by Tinfoil TEE and local llama.cpp).
 */
@OptIn(ExperimentalSerializationApi::class)
object OpenAiFormatAdapter {

    // ── Request: Anthropic → OpenAI ─────────────────────────────────────

    /**
     * Convert an Anthropic [MessagesRequest] to an OpenAI-compatible
     * chat completion request body (JSON string).
     */
    fun toOpenAiRequestJson(request: MessagesRequest): String {
        return buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxTokens)
            put("stream", request.stream)

            // Build messages array
            val messages = buildJsonArray {
                // System prompt → system message
                request.system?.let { sys ->
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", sys)
                    })
                }

                // Convert each Anthropic message
                for (msg in request.messages) {
                    addAll(convertMessageToOpenAi(msg))
                }
            }
            put("messages", messages)

            // Convert tools: Anthropic tool schema → OpenAI function calling
            request.tools?.let { tools ->
                if (tools.isNotEmpty()) {
                    val openAiTools = buildJsonArray {
                        for (tool in tools) {
                            add(convertToolToOpenAi(tool))
                        }
                    }
                    put("tools", openAiTools)
                }
            }
        }.toString()
    }

    private fun convertMessageToOpenAi(msg: Message): List<JsonObject> {
        return when (msg.content) {
            is MessageContent.Text -> {
                listOf(buildJsonObject {
                    put("role", msg.role)
                    put("content", msg.content.value)
                })
            }
            is MessageContent.Blocks -> {
                val blocks = msg.content.blocks
                val toolResults = blocks.filterIsInstance<ContentBlock.ToolResult>()
                val otherBlocks = blocks.filter { it !is ContentBlock.ToolResult }

                val result = mutableListOf<JsonObject>()

                // Non-tool-result blocks: merge into a single assistant/user message
                if (otherBlocks.isNotEmpty()) {
                    val textParts = mutableListOf<String>()
                    val toolCalls = mutableListOf<JsonObject>()

                    for (block in otherBlocks) {
                        when (block) {
                            is ContentBlock.TextBlock -> textParts.add(block.text)
                            is ContentBlock.ThinkingBlock -> textParts.add(block.thinking)
                            is ContentBlock.RedactedThinkingBlock -> {} // skip
                            is ContentBlock.ToolUseBlock -> {
                                toolCalls.add(buildJsonObject {
                                    put("id", block.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", block.name)
                                        put("arguments", block.input.toString())
                                    })
                                })
                            }
                            is ContentBlock.ToolResult -> {} // handled below
                        }
                    }

                    result.add(buildJsonObject {
                        put("role", msg.role)
                        if (textParts.isNotEmpty()) {
                            put("content", textParts.joinToString("\n"))
                        }
                        if (toolCalls.isNotEmpty()) {
                            put("tool_calls", JsonArray(toolCalls))
                        }
                    })
                }

                // Tool results → OpenAI tool role messages
                for (tr in toolResults) {
                    result.add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", tr.toolUseId)
                        put("content", tr.content)
                    })
                }

                result
            }
        }
    }

    /**
     * Convert Anthropic tool definition to OpenAI function-calling format.
     *
     * Anthropic: `{ name, description, input_schema: { type, properties, required } }`
     * OpenAI:    `{ type: "function", function: { name, description, parameters: { ... } } }`
     */
    private fun convertToolToOpenAi(tool: JsonObject): JsonObject {
        val name = tool["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val description = tool["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val inputSchema = tool["input_schema"]?.jsonObject

        return buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", description)
                if (inputSchema != null) {
                    put("parameters", inputSchema)
                }
            })
        }
    }

    // ── Response: OpenAI → Anthropic ────────────────────────────────────

    /**
     * Parse an OpenAI chat completion response JSON into an Anthropic [MessagesResponse].
     */
    fun fromOpenAiResponseJson(json: String): MessagesResponse {
        val parser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val root = parser.parseToJsonElement(json).jsonObject

        val id = root["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = root["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val choices = root["choices"]?.jsonArray ?: JsonArray(emptyList())

        if (choices.isEmpty()) {
            return MessagesResponse(
                id = id, type = "message", role = "assistant",
                content = emptyList(), model = model, stopReason = "end_turn",
            )
        }

        val choice = choices[0].jsonObject
        val message = choice["message"]?.jsonObject
        val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull

        val contentBlocks = mutableListOf<ContentBlock>()

        // Text content
        val textContent = message?.get("content")?.jsonPrimitive?.contentOrNull
        if (!textContent.isNullOrEmpty()) {
            contentBlocks.add(ContentBlock.TextBlock(textContent))
        }

        // Tool calls
        val toolCalls = message?.get("tool_calls")?.jsonArray
        if (toolCalls != null) {
            for (tc in toolCalls) {
                val tcObj = tc.jsonObject
                val tcId = tcObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                val function = tcObj["function"]?.jsonObject
                val fnName = function?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                val fnArgs = function?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}"
                val input = try {
                    parser.parseToJsonElement(fnArgs).jsonObject
                } catch (_: Exception) {
                    JsonObject(emptyMap())
                }
                contentBlocks.add(ContentBlock.ToolUseBlock(id = tcId, name = fnName, input = input))
            }
        }

        // Map OpenAI finish_reason to Anthropic stop_reason
        val stopReason = when (finishReason) {
            "stop" -> "end_turn"
            "tool_calls" -> "tool_use"
            "length" -> "max_tokens"
            else -> finishReason ?: "end_turn"
        }

        // Usage
        val usageObj = root["usage"]?.jsonObject
        val usage = if (usageObj != null) {
            Usage(
                inputTokens = usageObj["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                outputTokens = usageObj["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            )
        } else null

        return MessagesResponse(
            id = id,
            type = "message",
            role = "assistant",
            content = contentBlocks,
            model = model,
            stopReason = stopReason,
            usage = usage,
        )
    }
}
