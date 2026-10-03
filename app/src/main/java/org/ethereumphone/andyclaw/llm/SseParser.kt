package org.ethereumphone.andyclaw.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * Anthropic Messages SSE → [StreamingCallback].
 *
 * [onEvent] throws rather than reporting through `onError`: an `event: error` is a failed
 * request ([AnthropicApiException], 529 for `overloaded_error`) and an unreadable event is
 * lost content ([IOException]); both go to [withRetry] and AgentLoop's guards like any other
 * failure. The transport must call [finishStream] at end of body — a stream that stops before
 * `message_stop` was cut off, and must not be taken for a complete reply.
 *
 * With [keepThinking], `thinking` and `redacted_thinking` blocks are kept in the response, in
 * order, with their signatures: Anthropic's own API rejects a tool-use turn sent back without
 * them. Off for the gateways in front of it, which have never been sent one back.
 */
class SseParser(
    private val callback: StreamingCallback,
    private val provider: String = "Anthropic",
    private val keepThinking: Boolean = false,
) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val contentBlocks = mutableListOf<ContentBlock>()
    /** Text of the text block currently open; flushed and cleared at its content_block_stop. */
    private val textAccumulator = StringBuilder()
    private val toolJsonAccumulators = mutableMapOf<Int, StringBuilder>()
    private val toolNames = mutableMapOf<Int, String>()
    private val toolIds = mutableMapOf<Int, String>()
    private val thinkingText = mutableMapOf<Int, StringBuilder>()
    private val thinkingSignatures = mutableMapOf<Int, StringBuilder>()
    private val redactedThinking = mutableMapOf<Int, String>()
    private var responseId = ""
    private var model = ""
    private var stopReason: String? = null
    private var usage: Usage? = null
    private var completed = false

    fun onEvent(event: String, data: String) {
        if (data.isBlank()) return
        try {
            when (event) {
                "message_start" -> handleMessageStart(data)
                "content_block_start" -> handleContentBlockStart(data)
                "content_block_delta" -> handleContentBlockDelta(data)
                "content_block_stop" -> handleContentBlockStop(data)
                "message_delta" -> handleMessageDelta(data)
                "message_stop" -> handleMessageStop()
                "error" -> {
                    val obj = json.parseToJsonElement(data) as? JsonObject
                    throw StreamErrors.fromStreamError(obj?.get("error") ?: obj, provider)
                }
                "ping" -> {} // ignore
            }
        } catch (e: AnthropicApiException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("malformed $event event: ${e.message}", e)
        }
    }

    /**
     * End of body. A stream that ended without `message_stop` and without the `message_delta`
     * carrying a `stop_reason` (sent just before it — accepted in case a proxy drops the
     * last event) was cut off: a dropped connection, retried by [withRetry] unless a tool has
     * already started acting on it.
     */
    fun finishStream() {
        if (completed) return
        if (stopReason != null) {
            handleMessageStop()
            return
        }
        throw IOException("$provider stream ended before message_stop")
    }

    private fun handleMessageStart(data: String) {
        val obj = json.parseToJsonElement(data).jsonObject
        val message = obj["message"] as? JsonObject ?: return
        responseId = message["id"]?.jsonPrimitive?.contentOrNull ?: ""
        model = message["model"]?.jsonPrimitive?.contentOrNull ?: ""
        (message["usage"] as? JsonObject)?.let {
            usage = json.decodeFromJsonElement(Usage.serializer(), it)
        }
    }

    private fun handleContentBlockStart(data: String) {
        val obj = json.parseToJsonElement(data).jsonObject
        val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return
        val block = obj["content_block"] as? JsonObject ?: return
        val type = block["type"]?.jsonPrimitive?.contentOrNull

        when (type) {
            "text" -> {
                // text block starting
            }
            "tool_use" -> {
                val id = block["id"]?.jsonPrimitive?.contentOrNull ?: ""
                val name = block["name"]?.jsonPrimitive?.contentOrNull ?: ""
                toolIds[index] = id
                toolNames[index] = name
                toolJsonAccumulators[index] = StringBuilder()
            }
            "thinking" -> if (keepThinking) {
                thinkingText[index] = StringBuilder(block["thinking"]?.jsonPrimitive?.contentOrNull ?: "")
                thinkingSignatures[index] = StringBuilder(block["signature"]?.jsonPrimitive?.contentOrNull ?: "")
            }
            "redacted_thinking" -> if (keepThinking) {
                redactedThinking[index] = block["data"]?.jsonPrimitive?.contentOrNull ?: ""
            }
        }
    }

    private fun handleContentBlockDelta(data: String) {
        val obj = json.parseToJsonElement(data).jsonObject
        val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return
        val delta = obj["delta"] as? JsonObject ?: return
        val type = delta["type"]?.jsonPrimitive?.contentOrNull

        when (type) {
            "text_delta" -> {
                val text = delta["text"]?.jsonPrimitive?.contentOrNull ?: ""
                textAccumulator.append(text)
                callback.onToken(text)
            }
            "input_json_delta" -> {
                val partial = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                toolJsonAccumulators[index]?.append(partial)
            }
            "thinking_delta" -> {
                thinkingText[index]?.append(delta["thinking"]?.jsonPrimitive?.contentOrNull ?: "")
            }
            "signature_delta" -> {
                thinkingSignatures[index]?.append(delta["signature"]?.jsonPrimitive?.contentOrNull ?: "")
            }
        }
    }

    private fun handleContentBlockStop(data: String) {
        val obj = json.parseToJsonElement(data).jsonObject
        val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return

        val thinking = thinkingText.remove(index)
        if (thinking != null) {
            val signature = thinkingSignatures.remove(index)?.toString().orEmpty()
            contentBlocks.add(ContentBlock.ThinkingBlock(thinking = thinking.toString(), signature = signature))
            return
        }
        redactedThinking.remove(index)?.let {
            contentBlocks.add(ContentBlock.RedactedThinkingBlock(data = it))
            return
        }

        if (toolJsonAccumulators.containsKey(index)) {
            val id = toolIds[index] ?: return
            val name = toolNames[index] ?: return
            val input = ToolArguments.parse(toolJsonAccumulators[index]?.toString())
            contentBlocks.add(ContentBlock.ToolUseBlock(id = id, name = name, input = input))
            callback.onToolUse(id, name, input)
            toolJsonAccumulators.remove(index)
            toolIds.remove(index)
            toolNames.remove(index)
        } else {
            flushText()
        }
    }

    /**
     * Closes the open text block. The accumulator is per block: kept across blocks, every
     * later block stop (a thinking block, a second text block) re-added all earlier text.
     * Whitespace-only text is dropped — Anthropic rejects it with a 400 when the assistant
     * turn is sent back.
     */
    private fun flushText() {
        if (textAccumulator.isNotBlank()) {
            contentBlocks.add(ContentBlock.TextBlock(text = textAccumulator.toString()))
        }
        textAccumulator.setLength(0)
    }

    private fun handleMessageDelta(data: String) {
        val obj = json.parseToJsonElement(data).jsonObject
        val delta = obj["delta"] as? JsonObject
        stopReason = delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull
        // The counts here are cumulative and usually only output_tokens; a field left out keeps
        // message_start's value. Replacing the whole object zeroed every prompt count.
        (obj["usage"] as? JsonObject)?.let { counts ->
            fun count(key: String) = (counts[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            val start = usage ?: Usage()
            usage = Usage(
                inputTokens = count("input_tokens") ?: start.inputTokens,
                outputTokens = count("output_tokens") ?: start.outputTokens,
                cacheWriteTokens = count("cache_creation_input_tokens") ?: start.cacheWriteTokens,
                cacheReadTokens = count("cache_read_input_tokens") ?: start.cacheReadTokens,
            )
        }
    }

    private fun handleMessageStop() {
        if (completed) return
        completed = true
        // A text block the server never closed
        flushText()

        val response = MessagesResponse(
            id = responseId,
            type = "message",
            role = "assistant",
            content = contentBlocks.toList(),
            model = model,
            stopReason = stopReason,
            usage = usage,
        )
        callback.onComplete(response)
    }
}
