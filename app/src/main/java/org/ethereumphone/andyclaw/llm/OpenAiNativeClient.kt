package org.ethereumphone.andyclaw.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ethereumphone.andyclaw.BuildConfig
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
    /** Re-evaluated on every request so live settings changes (CUSTOM provider's
     *  base URL) take effect without recreating the client. Static-URL providers
     *  use the [String] overload below which wraps a constant. */
    private val baseUrlProvider: () -> String =
        { "https://api.openai.com/v1/chat/completions" },
) : LlmClient {

    /** Convenience constructor for static-URL providers (Venice, OpenAI). */
    constructor(apiKey: () -> String, baseUrl: String) : this(apiKey, { baseUrl })

    private val baseUrl: String
        get() = baseUrlProvider()

    companion object {
        private const val TAG = "OpenAiNativeClient"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Human-readable provider name derived from [baseUrl], used in error labels and logs. */
    private val providerLabel: String = when {
        baseUrl.contains("venice", ignoreCase = true) -> "Venice"
        baseUrl.contains("openai", ignoreCase = true) -> "OpenAI"
        else -> "OpenAI-compatible"
    }

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
                Log.e(TAG, "sendMessage: HTTP ${response.code} from $providerLabel, error=$errorBody")
                // The request body is otherwise never logged; on a schema rejection
                // (e.g. Venice "Unrecognized key(s) in object: '<key>'") this shows
                // exactly which key was sent. Debug builds only — body has chat content.
                if (BuildConfig.DEBUG) Log.e(TAG, "sendMessage: rejected request body=$openAiJson")
                throw AnthropicApiException(response.code, errorBody, provider = providerLabel)
            }

            val responseBody = response.body?.string()
                ?: throw AnthropicApiException(500, "Empty response", provider = providerLabel)
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
            Log.e(TAG, "streamMessage: HTTP ${response.code} from $providerLabel, error=$errorBody")
            if (BuildConfig.DEBUG) Log.e(TAG, "streamMessage: rejected request body=$openAiJson")
            callback.onError(AnthropicApiException(response.code, errorBody, provider = providerLabel))
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
