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
     *
     * [useMaxCompletionTokens] sends the output cap as `max_completion_tokens`,
     * which api.openai.com requires for its reasoning models (GPT-5+, o-series)
     * and accepts for every other model. Other OpenAI-compatible servers
     * (Tinfoil, Venice, llama.cpp) keep getting `max_tokens`.
     *
     * [includeUsage] asks a stream for its token counts, which OpenAI-format streams leave
     * out unless asked: without them the context bar read 0 and compaction never ran. Only
     * for servers known to take the field; a user's own server is not asked.
     */
    fun toOpenAiRequestJson(
        request: MessagesRequest,
        useMaxCompletionTokens: Boolean = false,
        includeUsage: Boolean = false,
    ): String {
        return buildJsonObject {
            put("model", request.model)
            put(if (useMaxCompletionTokens) "max_completion_tokens" else "max_tokens", request.maxTokens)
            put("stream", request.stream)
            if (request.stream && includeUsage) {
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }

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
                    put("parallel_tool_calls", request.parallelToolCalls)
                }
            }
            request.temperature?.takeIf { AnthropicModels.acceptsTemperature(request.model) }?.let {
                put("temperature", it)
            }
            request.reasoning?.let { cfg ->
                put("reasoning", buildJsonObject {
                    put("effort", cfg.effort)
                })
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

                // Tool results → OpenAI tool role messages. Chat Completions takes images only in
                // user messages (a tool message carrying one is a 400 on api.openai.com), so the
                // text stays with its call and the images follow the last tool message: the
                // assistant's tool_calls have to be answered before anything else is said.
                val images = buildJsonArray {
                    for (tr in toolResults) {
                        val parts = tr.contentBlocks?.filterIsInstance<ToolResultContent.Image>().orEmpty()
                        if (parts.isEmpty()) continue
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "Image returned by tool call ${tr.toolUseId}:")
                        })
                        for (part in parts) {
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject {
                                    put("url", "data:${part.source.mediaType};base64,${part.source.data}")
                                })
                            })
                        }
                    }
                }
                for (tr in toolResults) {
                    result.add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", tr.toolUseId)
                        put("content", tr.content)
                    })
                }
                if (images.isNotEmpty()) {
                    result.add(buildJsonObject {
                        put("role", "user")
                        put("content", images)
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
        val message = choice["message"] as? JsonObject
        val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull

        val contentBlocks = mutableListOf<ContentBlock>()

        // Text content
        val textContent = message?.get("content")?.jsonPrimitive?.contentOrNull
        if (!textContent.isNullOrEmpty()) {
            contentBlocks.add(ContentBlock.TextBlock(textContent))
        }

        // Tool calls
        val toolCalls = message?.get("tool_calls") as? JsonArray
        if (toolCalls != null) {
            for (tc in toolCalls) {
                val tcObj = tc.jsonObject
                val tcId = tcObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                val function = tcObj["function"] as? JsonObject
                val fnName = function?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                // Broken or truncated arguments are marked, not run as `{}` (see ToolArguments).
                val rawArgs = function?.get("arguments")
                val input = rawArgs as? JsonObject
                    ?: ToolArguments.parse((rawArgs as? JsonPrimitive)?.contentOrNull)
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

        // Usage (including prompt cache metrics)
        // `as?`: providers send `"usage": null` / `"prompt_tokens_details": null`.
        val usage = (root["usage"] as? JsonObject)?.let { usageFrom(it) }

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

    /**
     * An OpenAI `usage` object in this app's terms. `prompt_tokens` includes the cached part,
     * while [Usage.inputTokens] is the uncached part only, as Anthropic counts it: AgentLoop
     * adds the cache back to size the prompt, so passing `prompt_tokens` through counted
     * every cached token twice.
     */
    internal fun usageFrom(usageObj: JsonObject): Usage {
        fun count(obj: JsonObject?, key: String) =
            (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        val cached = count(usageObj["prompt_tokens_details"] as? JsonObject, "cached_tokens")
        return Usage(
            inputTokens = (count(usageObj, "prompt_tokens") - cached).coerceAtLeast(0),
            outputTokens = count(usageObj, "completion_tokens"),
            cacheReadTokens = cached,
        )
    }

    /**
     * True when the server turned a request down over `stream_options`. The Tinfoil clients
     * then ask again without it, once, and stop asking: a refusal would fail every stream.
     */
    fun rejectsStreamOptions(e: Throwable): Boolean =
        e is AnthropicApiException && e.statusCode == 400 &&
            e.message?.contains("stream_options", ignoreCase = true) == true
}
