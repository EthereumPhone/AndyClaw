package org.ethereumphone.andyclaw.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * [LlmClient] that calls any OpenAI-compatible Chat Completions API.
 *
 * Uses [OpenAiFormatAdapter] to convert from the internal Anthropic message
 * format and [OpenAiStreamAccumulator] for streaming responses.
 *
 * Works with OpenAI, Venice AI, and any other OpenAI-compatible endpoint
 * by changing [baseUrl].
 */
class OpenAiNativeClient(
    private val apiKey: () -> String,
    private val baseUrl: String = "https://api.openai.com/v1/chat/completions",
) : LlmClient {

    companion object {
        private const val TAG = "OpenAiNativeClient"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun buildRequest(body: String): Request {
        return Request.Builder()
            .url(baseUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer ${apiKey().trim()}")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    override suspend fun sendMessage(request: MessagesRequest): MessagesResponse =
        withContext(Dispatchers.IO) {
            val openAiJson = OpenAiFormatAdapter.toOpenAiRequestJson(request.copy(stream = false))
            Log.d(TAG, "sendMessage: model=${request.model}, messages=${request.messages.size}")

            val httpRequest = buildRequest(openAiJson)
            val response = client.newCall(httpRequest).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "sendMessage: HTTP ${response.code}, error=$errorBody")
                throw AnthropicApiException(response.code, errorBody)
            }

            val responseBody = response.body?.string()
                ?: throw AnthropicApiException(500, "Empty response")
            OpenAiFormatAdapter.fromOpenAiResponseJson(responseBody)
        }

    override suspend fun streamMessage(
        request: MessagesRequest,
        callback: StreamingCallback,
    ) = withContext(Dispatchers.IO) {
        val openAiJson = OpenAiFormatAdapter.toOpenAiRequestJson(request.copy(stream = true))
        Log.d(TAG, "streamMessage: model=${request.model}, messages=${request.messages.size}")

        val httpRequest = buildRequest(openAiJson)
        val response = client.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            Log.e(TAG, "streamMessage: HTTP ${response.code}, error=$errorBody")
            callback.onError(AnthropicApiException(response.code, errorBody))
            return@withContext
        }

        val accumulator = OpenAiStreamAccumulator(callback)
        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
        try {
            reader.forEachLine { line ->
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ")
                    accumulator.onData(data)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "streamMessage: streaming error", e)
            callback.onError(e)
        } finally {
            reader.close()
            response.close()
        }
    }
}
