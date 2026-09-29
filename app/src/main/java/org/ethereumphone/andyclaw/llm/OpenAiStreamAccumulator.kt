package org.ethereumphone.andyclaw.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

/**
 * Translates OpenAI SSE streaming chunks (`data: {ChatCompletionChunk}` / `data: [DONE]`)
 * into [StreamingCallback] calls matching the Anthropic format.
 *
 * OpenAI streams produce `choices[0].delta` with incremental text and tool call fragments.
 * This accumulator collects them and emits matching Anthropic-style events.
 *
 * The transport feeds [onData] and then **must** call [finishStream] when the body ends.
 * [onData] never throws — it runs inside the Go bridge's callback, where an exception has
 * nowhere sane to go — so a failure is recorded, [onData] returns true to stop the read,
 * and [finishStream] throws it. A body that simply stopped, without `[DONE]` or a
 * `finish_reason`, is a dropped connection ([IOException]), not a finished reply: accepting
 * it used to end a turn "successfully" with half an answer.
 */
class OpenAiStreamAccumulator(
    private val callback: StreamingCallback,
    private val provider: String = "OpenAI-compatible",
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val contentBlocks = mutableListOf<ContentBlock>()
    private val textAccumulator = StringBuilder()

    /** One tool call being assembled. */
    private class ToolSlot(val ordinal: Int) {
        var id: String? = null
        var name: String? = null
        val args = StringBuilder()
    }
    private val toolSlots = mutableListOf<ToolSlot>()
    private val slotsById = mutableMapOf<String, ToolSlot>()
    private val slotsByIndex = mutableMapOf<Int, ToolSlot>()
    private var lastSlot: ToolSlot? = null

    private var responseId = ""
    private var model = ""
    private var finishReason: String? = null
    private var usage: Usage? = null
    private var completed = false
    private var failure: Exception? = null

    /**
     * Feed a single SSE data payload. Call with the raw string after `data: `.
     * Returns true when the transport should stop reading: the `[DONE]` sentinel, or a
     * failure that [finishStream] will report.
     */
    fun onData(data: String): Boolean {
        if (completed || failure != null) return true
        val trimmed = data.trim()
        if (trimmed == "[DONE]") {
            finish()
            return true
        }

        try {
            val root = json.parseToJsonElement(trimmed) as? JsonObject
                ?: throw IOException("unexpected stream chunk")

            // A provider that fails mid-stream says so in-band (OpenRouter, vLLM, LiteLLM).
            root["error"]?.let { err ->
                failure = StreamErrors.fromStreamError(err, provider)
                return true
            }

            responseId = (root["id"] as? JsonPrimitive)?.contentOrNull ?: responseId
            model = (root["model"] as? JsonPrimitive)?.contentOrNull ?: model

            // Capture usage if present (OpenAI includes it in the final chunk). Some
            // providers send `"usage": null` on every other chunk — not an object.
            (root["usage"] as? JsonObject)?.let { usageObj ->
                val promptDetails = usageObj["prompt_tokens_details"] as? JsonObject
                val cachedTokens = (promptDetails?.get("cached_tokens") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
                usage = Usage(
                    inputTokens = (usageObj["prompt_tokens"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                    outputTokens = (usageObj["completion_tokens"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                    cacheReadTokens = cachedTokens,
                )
            }

            val choices = root["choices"] as? JsonArray ?: return false
            if (choices.isEmpty()) return false

            val choice = choices[0] as? JsonObject ?: return false
            finishReason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull ?: finishReason
            val delta = choice["delta"] as? JsonObject ?: return false

            // Text content delta
            val textDelta = (delta["content"] as? JsonPrimitive)?.contentOrNull
            if (!textDelta.isNullOrEmpty()) {
                textAccumulator.append(textDelta)
                callback.onToken(textDelta)
            }

            // Tool call deltas (an explicit null from some providers is simply not an array)
            val toolCalls = delta["tool_calls"] as? JsonArray
            if (toolCalls != null) {
                for (tc in toolCalls) {
                    val tcObj = tc as? JsonObject ?: continue
                    val index = (tcObj["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                    val id = (tcObj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                    val slot = slotFor(index, id)

                    val function = tcObj["function"] as? JsonObject
                    if (function != null) {
                        (function["name"] as? JsonPrimitive)?.contentOrNull?.let { slot.name = it }
                        (function["arguments"] as? JsonPrimitive)?.contentOrNull?.let { slot.args.append(it) }
                    }
                }
            }
        } catch (e: Exception) {
            // A chunk we cannot read is content we lost; the reply is not whole.
            failure = e as? IOException ?: IOException("malformed stream chunk: ${e.message}", e)
            return true
        }

        return false
    }

    /**
     * Where a tool-call fragment belongs. Keyed by `index` when the provider sends one, as
     * OpenAI does. Some providers omit `index`, or send every parallel call at index 0 with
     * its own `id`; defaulting to 0 merged their arguments into one unparseable call. So a
     * new `id` always opens a new call, and a fragment with neither continues the last one.
     */
    private fun slotFor(index: Int?, id: String?): ToolSlot {
        val slot = when {
            id != null -> slotsById[id]
                ?: index?.let { slotsByIndex[it] }?.takeIf { it.id == null }
                ?: newSlot()
            index != null -> slotsByIndex[index] ?: newSlot()
            else -> lastSlot ?: newSlot()
        }
        if (id != null) {
            slot.id = id
            slotsById[id] = slot
        }
        if (index != null) slotsByIndex[index] = slot
        lastSlot = slot
        return slot
    }

    private fun newSlot(): ToolSlot = ToolSlot(toolSlots.size).also { toolSlots += it }

    /**
     * The transport's body ended (or [onData] asked it to stop). Throws a recorded failure;
     * otherwise completes the response if it had not been completed already. A provider that
     * never sends `[DONE]` but did send a `finish_reason` has finished; one that sent neither
     * was cut off.
     */
    fun finishStream() {
        failure?.let { throw it }
        if (completed) return
        if (finishReason != null) {
            finish()
            return
        }
        throw IOException("$provider stream ended before the response was complete")
    }

    private fun finish() {
        if (completed) return
        completed = true
        // Finalize text block
        if (textAccumulator.isNotEmpty()) {
            contentBlocks.add(ContentBlock.TextBlock(textAccumulator.toString()))
        }

        // Finalize tool calls, in the order they first appeared
        for (slot in toolSlots) {
            val id = slot.id ?: "call_${slot.ordinal}"
            val name = slot.name ?: ""
            val input = ToolArguments.parse(slot.args.toString())
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
            usage = usage,
        )
        callback.onComplete(response)
    }
}
