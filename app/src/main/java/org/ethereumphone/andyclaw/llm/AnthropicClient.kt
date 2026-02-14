package org.ethereumphone.andyclaw.llm

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
    private val apiKey: () -> String,
    private val baseUrl: String = "https://openrouter.ai/api",
) {
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

    suspend fun sendMessage(request: MessagesRequest): MessagesResponse = withContext(Dispatchers.IO) {
        val nonStreamRequest = request.copy(stream = false)
        val body = serializeRequest(nonStreamRequest)
        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/messages")
            .addHeader("Authorization", "Bearer ${apiKey()}")
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(httpRequest).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw AnthropicApiException(response.code, errorBody)
        }
        val responseBody = response.body?.string() ?: throw AnthropicApiException(500, "Empty response")
        json.decodeFromString<MessagesResponse>(responseBody)
    }

    suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) = withContext(Dispatchers.IO) {
        val streamRequest = request.copy(stream = true)
        val body = serializeRequest(streamRequest)
        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/messages")
            .addHeader("Authorization", "Bearer ${apiKey()}")
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(httpRequest).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
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

    private fun serializeRequest(request: MessagesRequest): String {
        val messagesJson = request.messages.map { msg ->
            val contentElement = when (msg.content) {
                is MessageContent.Text -> kotlinx.serialization.json.JsonPrimitive(msg.content.value)
                is MessageContent.Blocks -> kotlinx.serialization.json.JsonArray(
                    msg.content.blocks.map { json.encodeToJsonElement(ContentBlock.serializer(), it) }
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
