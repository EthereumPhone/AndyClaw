package org.ethereumphone.andyclaw.llm

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    /** api.openai.com rejects `max_tokens` for GPT-5+ and o-series; see [OpenAiFormatAdapter.toOpenAiRequestJson]. */
    private val isOpenAiApi: Boolean
        get() = baseUrl.contains("api.openai.com", ignoreCase = true)

    /** Servers documented to take `stream_options`; a user's own server is not asked. */
    private val asksStreamUsage: Boolean
        get() = isOpenAiApi || baseUrl.contains("api.venice.ai", ignoreCase = true)

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
            val openAiJson = OpenAiFormatAdapter.toOpenAiRequestJson(request.copy(stream = false), useMaxCompletionTokens = isOpenAiApi)
            Log.d(TAG, "sendMessage: model=${request.model}, messages=${request.messages.size}")

            val httpRequest = buildRequest(openAiJson)
            val responseBody = client.newCall(httpRequest).executeCancellable { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Log.e(TAG, "sendMessage: HTTP ${response.code} from $providerLabel, error=$errorBody")
                    // The request body is otherwise never logged; on a schema rejection
                    // (e.g. Venice "Unrecognized key(s) in object: '<key>'") this shows
                    // exactly which key was sent. Debug builds only — body has chat content.
                    if (BuildConfig.DEBUG) Log.e(TAG, "sendMessage: rejected request body=$openAiJson")
                    throw AnthropicApiException(response.code, errorBody, response.header("retry-after")?.toIntOrNull(), provider = providerLabel)
                }
                response.body?.string()
                    ?: throw AnthropicApiException(500, "Empty response", provider = providerLabel)
            }
            OpenAiFormatAdapter.fromOpenAiResponseJson(responseBody)
        }

    override suspend fun streamMessage(
        request: MessagesRequest,
        callback: StreamingCallback,
    ) = withContext(Dispatchers.IO) {
        val openAiJson = OpenAiFormatAdapter.toOpenAiRequestJson(
            request.copy(stream = true),
            useMaxCompletionTokens = isOpenAiApi,
            includeUsage = asksStreamUsage,
        )
        Log.d(TAG, "streamMessage: model=${request.model}, messages=${request.messages.size}")

        val httpRequest = buildRequest(openAiJson)
        val job = coroutineContext[Job]
        // Thrown, like AnthropicClient: a failure passed to callback.onError never reached
        // withRetry or the caller, and the turn carried on as an empty reply.
        client.newCall(httpRequest).executeCancellable { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "streamMessage: HTTP ${response.code} from $providerLabel, error=$errorBody")
                if (BuildConfig.DEBUG) Log.e(TAG, "streamMessage: rejected request body=$openAiJson")
                throw AnthropicApiException(response.code, errorBody, response.header("retry-after")?.toIntOrNull(), provider = providerLabel)
            }

            val accumulator = OpenAiStreamAccumulator(callback, providerLabel)
            BufferedReader(InputStreamReader(response.body!!.byteStream())).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (job?.isActive == false) throw CancellationException("LLM stream cancelled")
                    if (line.startsWith("data: ")) {
                        if (accumulator.onData(line.removePrefix("data: "))) break
                    }
                }
            }
            accumulator.finishStream()
        }
    }
}
