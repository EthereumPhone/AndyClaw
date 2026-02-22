package org.ethereumphone.andyclaw.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [LlmClient] for privileged (ethOS) devices that combines:
 *
 * - **Go bridge with EHBP** for the LLM call — full TEE attestation
 *   verification and end-to-end encrypted bodies via the Encrypted HTTP
 *   Body Protocol (HPKE). The proxy server never sees plaintext.
 * - **Server-side billing** — the proxy injects the Tinfoil API key and
 *   reads usage metrics from response headers for balance deduction.
 *
 * Flow:
 * 1. Go bridge verifies enclave attestation and obtains HPKE public key
 * 2. Request body is HPKE-encrypted client-side → sent through proxy
 * 3. Proxy adds Tinfoil API key, forwards encrypted blob to enclave
 * 4. Enclave decrypts inside TEE, processes, encrypts response
 * 5. Proxy reads usage from headers, bills user, forwards encrypted response
 * 6. Go bridge decrypts response client-side
 *
 * The API key never leaves the server. The proxy sees only encrypted blobs
 * and metadata headers.
 */
class TinfoilProxyClient(
    private val userId: () -> String = { "" },
    private val signature: () -> String = { "" },
    private val proxyUrl: String = "https://api.markushaas.com/api/premium-llm-tinfoil",
) : LlmClient {

    companion object {
        private const val TAG = "TinfoilProxyClient"
    }

    override suspend fun sendMessage(request: MessagesRequest): MessagesResponse =
        withContext(Dispatchers.IO) {
            val openAiJson = OpenAiFormatAdapter.toOpenAiRequestJson(request.copy(stream = false))
            Log.d(TAG, "sendMessage: model=${request.model}")

            val responseJson = try {
                tinfoilbridge.Tinfoilbridge.proxiedChatCompletion(
                    openAiJson,
                    proxyUrl,
                    userId(),
                    signature(),
                )
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage failed", e)
                throw AnthropicApiException(500, e.message ?: "Tinfoil EHBP bridge error")
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
            tinfoilbridge.Tinfoilbridge.proxiedChatCompletionStream(
                openAiJson,
                proxyUrl,
                userId(),
                signature(),
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
