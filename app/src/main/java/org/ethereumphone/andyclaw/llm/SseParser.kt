package org.ethereumphone.andyclaw.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SseParser(private val callback: StreamingCallback) {

    private val json = Json { ignoreUnknownKeys = true }
    private val contentBlocks = mutableListOf<ContentBlock>()
    private val textAccumulator = StringBuilder()
    private val toolJsonAccumulators = mutableMapOf<Int, StringBuilder>()
    private val toolNames = mutableMapOf<Int, String>()
    private val toolIds = mutableMapOf<Int, String>()
    private var responseId = ""
    private var model = ""
    private var stopReason: String? = null
    private var usage: Usage? = null

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
                "ping" -> {} // ignore
            }
        } catch (e: Exception) {
            callback.onError(e)
        }
    }

    private fun handleMessageStart(data: String) {
        val obj = json.parseToJsonElement(data).jsonObject
        val message = obj["message"]?.jsonObject ?: return
        responseId = message["id"]?.jsonPrimitive?.contentOrNull ?: ""
        model = message["model"]?.jsonPrimitive?.contentOrNull ?: ""
        message["usage"]?.let {
            usage = json.decodeFromJsonElement(Usage.serializer(), it)
        }
    }

    private fun handleContentBlockStart(data: String) {
        val obj = json.parseToJsonElement(data).jsonObject
        val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return
        val block = obj["content_block"]?.jsonObject ?: return
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
        }
    }

    private fun handleContentBlockDelta(data: String) {
        val obj = json.parseToJsonElement(data).jsonObject
        val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return
        val delta = obj["delta"]?.jsonObject ?: return
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
        }
    }

    private fun handleContentBlockStop(data: String) {
        val obj = json.parseToJsonElement(data).jsonObject
        val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return

        if (toolJsonAccumulators.containsKey(index)) {
            val id = toolIds[index] ?: return
            val name = toolNames[index] ?: return
            val inputJson = toolJsonAccumulators[index]?.toString() ?: "{}"
            val input = try {
                json.parseToJsonElement(inputJson).jsonObject
            } catch (_: Exception) {
                JsonObject(emptyMap())
            }
            contentBlocks.add(ContentBlock.ToolUseBlock(id = id, name = name, input = input))
            callback.onToolUse(id, name, input)
            toolJsonAccumulators.remove(index)
            toolIds.remove(index)
            toolNames.remove(index)
        } else {
            if (textAccumulator.isNotEmpty()) {
                contentBlocks.add(ContentBlock.TextBlock(text = textAccumulator.toString()))
            }
        }
    }

    private fun handleMessageDelta(data: String) {
        val obj = json.parseToJsonElement(data).jsonObject
        val delta = obj["delta"]?.jsonObject
        stopReason = delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull
        obj["usage"]?.let {
            usage = json.decodeFromJsonElement(Usage.serializer(), it)
        }
    }

    private fun handleMessageStop() {
        // Ensure any remaining text is captured
        if (textAccumulator.isNotEmpty() && contentBlocks.none { it is ContentBlock.TextBlock }) {
            contentBlocks.add(0, ContentBlock.TextBlock(text = textAccumulator.toString()))
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
