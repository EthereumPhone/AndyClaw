package org.ethereumphone.andyclaw.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [LlmClient] that sends requests through Tinfoil's TEE-attested inference
 * endpoint with full client-side attestation verification and TLS certificate
 * pinning via the tinfoil-go SDK (bundled as tinfoil-bridge.aar).
 *
 * On first use the Go bridge verifies the remote enclave's attestation:
 *   1. Fetches signed runtime measurements from the enclave
 *   2. Validates the certificate chain to AMD's hardware root key
 *   3. Downloads and verifies the Sigstore transparency log entry
 *   4. Compares source-code measurements against the running enclave
 *   5. Pins the TLS certificate to the attested key
 *
 * All subsequent requests reuse the verified HTTP client with automatic
 * certificate re-verification, ensuring traffic can only reach the
 * genuine Tinfoil enclave.
 *
 * Converts between Anthropic internal format and OpenAI format via [OpenAiFormatAdapter].
 */
class TinfoilClient(
    private val apiKey: () -> String,
) : LlmClient {

    companion object {
        private const val TAG = "TinfoilClient"
    }

    override suspend fun sendMessage(request: MessagesRequest): MessagesResponse =
        withContext(Dispatchers.IO) {
            val openAiJson = OpenAiFormatAdapter.toOpenAiRequestJson(request.copy(stream = false))
            Log.d(TAG, "sendMessage: model=${request.model}")

            val responseJson = try {
                tinfoilbridge.Tinfoilbridge.verifiedChatCompletion(openAiJson, apiKey())
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage failed", e)
                throw AnthropicApiException(500, e.message ?: "Tinfoil bridge error")
            }

            OpenAiFormatAdapter.fromOpenAiResponseJson(responseJson)
        }

    override suspend fun streamMessage(
        request: MessagesRequest,
        callback: StreamingCallback,
    ) = withContext(Dispatchers.IO) {
        val openAiJson = OpenAiFormatAdapter.toOpenAiRequestJson(request.copy(stream = true))
        Log.d(TAG, "streamMessage: model=${request.model}")

        val accumulator = OpenAiStreamAccumulator(callback)

        try {
            tinfoilbridge.Tinfoilbridge.verifiedChatCompletionStream(
                openAiJson,
                apiKey(),
                object : tinfoilbridge.StreamCallback {
                    override fun onData(data: String): Boolean {
                        return accumulator.onData(data)
                    }

                    override fun onError(err: String) {
                        Log.e(TAG, "streamMessage bridge error: $err")
                        callback.onError(Exception(err))
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "streamMessage failed", e)
            callback.onError(e)
        }
    }
}
