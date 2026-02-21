package org.ethereumphone.andyclaw.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [LlmClient] that runs inference locally via llama.cpp JNI.
 *
 * Converts between Anthropic internal format and OpenAI format via [OpenAiFormatAdapter],
 * then delegates to [LlamaCpp] for actual inference.
 *
 * Small models (Qwen3-4B) have limited tool-calling capability,
 * so [maxToolCount] is set to 5.
 */
class LocalLlmClient(
    private val llamaCpp: LlamaCpp,
) : LlmClient {

    companion object {
        private const val TAG = "LocalLlmClient"
    }

    override val maxToolCount: Int = 5

    override suspend fun sendMessage(request: MessagesRequest): MessagesResponse = withContext(Dispatchers.IO) {
        check(llamaCpp.isModelLoaded) { "Local model not loaded. Download and load the model first." }

        val openAiJson = OpenAiFormatAdapter.toOpenAiRequestJson(request.copy(stream = false))
        Log.d(TAG, "sendMessage: model=${request.model}")

        val responseJson = llamaCpp.complete(openAiJson)
        OpenAiFormatAdapter.fromOpenAiResponseJson(responseJson)
    }

    override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) = withContext(Dispatchers.IO) {
        if (!llamaCpp.isModelLoaded) {
            callback.onError(IllegalStateException("Local model not loaded. Download and load the model first."))
            return@withContext
        }

        val openAiJson = OpenAiFormatAdapter.toOpenAiRequestJson(request.copy(stream = true))
        Log.d(TAG, "streamMessage: model=${request.model}")

        llamaCpp.completeStream(openAiJson, object : LlamaStreamCallback {
            override fun onToken(token: String) {
                // For the JNI bridge, tokens come as raw text, not SSE chunks.
                // Forward directly to the StreamingCallback.
                callback.onToken(token)
            }

            override fun onComplete(responseJson: String) {
                // Parse the complete response and forward
                try {
                    val response = OpenAiFormatAdapter.fromOpenAiResponseJson(responseJson)
                    callback.onComplete(response)
                } catch (e: Exception) {
                    callback.onError(e)
                }
            }

            override fun onError(error: String) {
                callback.onError(RuntimeException("Local inference error: $error"))
            }
        })
    }
}
