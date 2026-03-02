package org.ethereumphone.andyclaw.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class AnthropicClient(
    private val userId: () -> String = { "" },
    private val signature: () -> String = { "" },
    private val apiKey: () -> String = { "" },
    private val baseUrl: String = "https://api.markushaas.com/api/premium-llm-andy",
) : LlmClient {
    companion object {
        private const val API_VERSION = "2023-06-01"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun buildRequest(body: String): Request {
        val uid = userId()
        val sig = signature()
        val key = apiKey()
        val builder = Request.Builder()
            .url(baseUrl)
            .addHeader("content-type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
        if (uid.isNotBlank() && sig.isNotBlank()) {
            builder.addHeader("X-User-Id", uid)
            builder.addHeader("X-Signature", sig)
        } else if (key.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer $key")
            builder.addHeader("anthropic-version", API_VERSION)
        }
        return builder.build()
    }

    override suspend fun sendMessage(request: MessagesRequest): MessagesResponse = withContext(Dispatchers.IO) {
        val nonStreamRequest = request.copy(stream = false)
        val body = serializeRequest(nonStreamRequest)
        Log.i("AGENT_VIRTUAL_SCREEN", "AnthropicClient.sendMessage: payloadSize=${body.length} chars (${body.toByteArray().size} bytes), model=${request.model}, messages=${request.messages.size}")
        val httpRequest = buildRequest(body)

        val response = client.newCall(httpRequest).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw AnthropicApiException(response.code, errorBody)
        }
        val responseBody = response.body?.string() ?: throw AnthropicApiException(500, "Empty response")
        json.decodeFromString<MessagesResponse>(responseBody)
    }

    override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) = withContext(Dispatchers.IO) {
        val streamRequest = request.copy(stream = true)
        val body = serializeRequest(streamRequest)
        val bodyBytes = body.toByteArray().size
        Log.i("AGENT_VIRTUAL_SCREEN", "AnthropicClient.streamMessage: payloadSize=${body.length} chars ($bodyBytes bytes / ${bodyBytes / 1024} KB), model=${request.model}, messages=${request.messages.size}")
        if (bodyBytes > 500_000) {
            Log.w("AGENT_VIRTUAL_SCREEN", "AnthropicClient.streamMessage: WARNING payload > 500KB, likely to hit size limits!")
        }
        val httpRequest = buildRequest(body)

        val response = client.newCall(httpRequest).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            Log.e("AGENT_VIRTUAL_SCREEN", "AnthropicClient.streamMessage: HTTP ${response.code}, errorBody=${errorBody.take(500)}")
            callback.onError(AnthropicApiException(response.code, errorBody))
            return@withContext
        }

        val parser = SseParser(callback)
        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
        try {
            var currentEvent = ""
            var dataBuffer = StringBuilder()

            reader.forEachLine { line ->
                when {
                    line.startsWith("event: ") -> {
                        currentEvent = line.removePrefix("event: ").trim()
                    }
                    line.startsWith("data: ") -> {
                        dataBuffer.append(line.removePrefix("data: "))
                    }
                    line.isBlank() -> {
                        if (currentEvent.isNotEmpty() && dataBuffer.isNotEmpty()) {
                            parser.onEvent(currentEvent, dataBuffer.toString())
                        }
                        currentEvent = ""
                        dataBuffer = StringBuilder()
                    }
                }
            }
        } finally {
            reader.close()
            response.close()
        }
    }

    /**
     * Serialize a single [ContentBlock], handling multimodal [ContentBlock.ToolResult]
     * that carries [ToolResultContent] blocks (text + images).
     */
    private fun serializeBlock(block: ContentBlock): kotlinx.serialization.json.JsonElement {
        if (block is ContentBlock.ToolResult && block.contentBlocks != null) {
            val imageBlocks = block.contentBlocks.filterIsInstance<ToolResultContent.Image>()
            val totalBase64 = imageBlocks.sumOf { it.source.data.length }
            Log.d("AGENT_VIRTUAL_SCREEN", "serializeBlock: multimodal ToolResult toolUseId=${block.toolUseId}, parts=${block.contentBlocks.size}, images=${imageBlocks.size}, totalBase64Chars=$totalBase64")
            return kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("tool_result"))
                put("tool_use_id", kotlinx.serialization.json.JsonPrimitive(block.toolUseId))
                if (block.isError) put("is_error", kotlinx.serialization.json.JsonPrimitive(true))
                put("content", kotlinx.serialization.json.JsonArray(
                    block.contentBlocks.map { part ->
                        when (part) {
                            is ToolResultContent.Text -> kotlinx.serialization.json.buildJsonObject {
                                put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                                put("text", kotlinx.serialization.json.JsonPrimitive(part.text))
                            }
                            is ToolResultContent.Image -> kotlinx.serialization.json.buildJsonObject {
                                put("type", kotlinx.serialization.json.JsonPrimitive("image"))
                                put("source", kotlinx.serialization.json.buildJsonObject {
                                    put("type", kotlinx.serialization.json.JsonPrimitive(part.source.type))
                                    put("media_type", kotlinx.serialization.json.JsonPrimitive(part.source.mediaType))
                                    put("data", kotlinx.serialization.json.JsonPrimitive(part.source.data))
                                })
                            }
                        }
                    }
                ))
            }
        }
        return json.encodeToJsonElement(ContentBlock.serializer(), block)
    }

    private fun serializeRequest(request: MessagesRequest): String {
        // Count images still present in the conversation for diagnostics
        var imageCount = 0
        var totalImgBase64 = 0L
        for (msg in request.messages) {
            val blocks = (msg.content as? MessageContent.Blocks)?.blocks ?: continue
            for (block in blocks) {
                if (block is ContentBlock.ToolResult && block.contentBlocks != null) {
                    for (part in block.contentBlocks) {
                        if (part is ToolResultContent.Image) {
                            imageCount++
                            totalImgBase64 += part.source.data.length
                        }
                    }
                }
            }
        }
        Log.d("AGENT_VIRTUAL_SCREEN", "serializeRequest: ${request.messages.size} messages, $imageCount images, totalImgBase64=${totalImgBase64} chars (~${totalImgBase64 * 3 / 4 / 1024} KB decoded)")

        val messagesJson = request.messages.map { msg ->
            val contentElement = when (msg.content) {
                is MessageContent.Text -> kotlinx.serialization.json.JsonPrimitive(msg.content.value)
                is MessageContent.Blocks -> kotlinx.serialization.json.JsonArray(
                    msg.content.blocks.map { block -> serializeBlock(block) }
                )
            }
            kotlinx.serialization.json.buildJsonObject {
                put("role", kotlinx.serialization.json.JsonPrimitive(msg.role))
                put("content", contentElement)
            }
        }

        return kotlinx.serialization.json.buildJsonObject {
            put("model", kotlinx.serialization.json.JsonPrimitive(request.model))
            put("max_tokens", kotlinx.serialization.json.JsonPrimitive(request.maxTokens))
            request.system?.let { put("system", kotlinx.serialization.json.JsonPrimitive(it)) }
            put("messages", kotlinx.serialization.json.JsonArray(messagesJson))
            request.tools?.let { put("tools", kotlinx.serialization.json.JsonArray(it)) }
            put("stream", kotlinx.serialization.json.JsonPrimitive(request.stream))
        }.toString()
    }
}

class AnthropicApiException(val statusCode: Int, message: String) : Exception("Anthropic API error ($statusCode): $message")
