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
 * [LlmClient] that sends requests to Tinfoil's TEE-attested inference endpoint.
 *
 * Currently uses a direct HTTPS client to Tinfoil's OpenAI-compatible API.
 * When the tinfoil-bridge AAR is available, this can be swapped to use the
 * Go bridge for full client-side attestation verification.
 *
 * Converts between Anthropic internal format and OpenAI format via [OpenAiFormatAdapter].
 */
class TinfoilClient(
    private val apiKey: () -> String,
    private val baseUrl: String = "https://api.tinfoil.sh/v1",
) : LlmClient {

    companion object {
        private const val TAG = "TinfoilClient"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun sendMessage(request: MessagesRequest): MessagesResponse = withContext(Dispatchers.IO) {
        val openAiJson = OpenAiFormatAdapter.toOpenAiRequestJson(request.copy(stream = false))
        Log.d(TAG, "sendMessage: model=${request.model}")

        val httpRequest = buildRequest(openAiJson)
        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw AnthropicApiException(response.code, errorBody)
        }

        val responseBody = response.body?.string() ?: throw AnthropicApiException(500, "Empty response")
        OpenAiFormatAdapter.fromOpenAiResponseJson(responseBody)
    }

    override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) = withContext(Dispatchers.IO) {
        val openAiJson = OpenAiFormatAdapter.toOpenAiRequestJson(request.copy(stream = true))
        Log.d(TAG, "streamMessage: model=${request.model}")

        val httpRequest = buildRequest(openAiJson)
        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
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
        } finally {
            reader.close()
            response.close()
        }
    }

    private fun buildRequest(body: String): Request {
        val key = apiKey()
        return Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $key")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }
}
