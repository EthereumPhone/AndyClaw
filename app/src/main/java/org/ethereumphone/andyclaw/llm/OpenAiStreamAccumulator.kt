package org.ethereumphone.andyclaw.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Translates OpenAI SSE streaming chunks (`data: {ChatCompletionChunk}` / `data: [DONE]`)
 * into [StreamingCallback] calls matching the Anthropic format.
 *
 * OpenAI streams produce `choices[0].delta` with incremental text and tool call fragments.
 * This accumulator collects them and emits matching Anthropic-style events.
 */
class OpenAiStreamAccumulator(private val callback: StreamingCallback) {

    private val json = Json { ignoreUnknownKeys = true }
    private val contentBlocks = mutableListOf<ContentBlock>()
    private val textAccumulator = StringBuilder()
    private val toolCallArgs = mutableMapOf<Int, StringBuilder>()
    private val toolCallIds = mutableMapOf<Int, String>()
    private val toolCallNames = mutableMapOf<Int, String>()
    private var responseId = ""
    private var model = ""
    private var finishReason: String? = null

    /**
     * Feed a single SSE data payload. Call with the raw string after `data: `.
     * Returns true if this was the `[DONE]` sentinel and streaming is complete.
     */
    fun onData(data: String): Boolean {
        val trimmed = data.trim()
        if (trimmed == "[DONE]") {
            finish()
            return true
        }

        try {
            val root = json.parseToJsonElement(trimmed).jsonObject
            responseId = root["id"]?.jsonPrimitive?.contentOrNull ?: responseId
            model = root["model"]?.jsonPrimitive?.contentOrNull ?: model

            val choices = root["choices"]?.jsonArray ?: return false
            if (choices.isEmpty()) return false

            val choice = choices[0].jsonObject
            finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull ?: finishReason
            val delta = choice["delta"]?.jsonObject ?: return false

            // Text content delta
            val textDelta = delta["content"]?.jsonPrimitive?.contentOrNull
            if (!textDelta.isNullOrEmpty()) {
                textAccumulator.append(textDelta)
                callback.onToken(textDelta)
            }

            // Tool call deltas
            val toolCalls = delta["tool_calls"]?.jsonArray
            if (toolCalls != null) {
                for (tc in toolCalls) {
                    val tcObj = tc.jsonObject
                    val index = tcObj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

                    // First chunk for this tool call: has id and function.name
                    val id = tcObj["id"]?.jsonPrimitive?.contentOrNull
                    if (id != null) {
                        toolCallIds[index] = id
                    }

                    val function = tcObj["function"]?.jsonObject
                    if (function != null) {
                        val name = function["name"]?.jsonPrimitive?.contentOrNull
                        if (name != null) {
                            toolCallNames[index] = name
                        }

                        val argDelta = function["arguments"]?.jsonPrimitive?.contentOrNull
                        if (argDelta != null) {
                            toolCallArgs.getOrPut(index) { StringBuilder() }.append(argDelta)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            callback.onError(e)
        }

        return false
    }

    private fun finish() {
        // Finalize text block
        if (textAccumulator.isNotEmpty()) {
            contentBlocks.add(ContentBlock.TextBlock(textAccumulator.toString()))
        }

        // Finalize tool calls
        for (index in toolCallArgs.keys.sorted()) {
            val id = toolCallIds[index] ?: "call_$index"
            val name = toolCallNames[index] ?: ""
            val argsJson = toolCallArgs[index]?.toString() ?: "{}"
            val input = try {
                json.parseToJsonElement(argsJson).jsonObject
            } catch (_: Exception) {
                JsonObject(emptyMap())
            }
            contentBlocks.add(ContentBlock.ToolUseBlock(id = id, name = name, input = input))
            callback.onToolUse(id, name, input)
        }

        // Map finish_reason
        val stopReason = when (finishReason) {
            "stop" -> "end_turn"
            "tool_calls" -> "tool_use"
            "length" -> "max_tokens"
            else -> finishReason ?: "end_turn"
        }

        val response = MessagesResponse(
            id = responseId,
            type = "message",
            role = "assistant",
            content = contentBlocks.toList(),
            model = model,
            stopReason = stopReason,
        )
        callback.onComplete(response)
    }
}
