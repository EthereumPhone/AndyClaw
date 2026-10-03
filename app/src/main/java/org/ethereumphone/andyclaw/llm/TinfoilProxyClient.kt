package org.ethereumphone.andyclaw.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
    private val channel: () -> String = { "" },
) : LlmClient {

    companion object {
        private const val PROVIDER = "Tinfoil"
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
                    channel(),
                )
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage failed", e)
                throw StreamErrors.fromBridgeError(e.message, PROVIDER, e)
            }

            OpenAiFormatAdapter.fromOpenAiResponseJson(responseJson)
        }

    /** Cleared for good if the enclave refuses `stream_options` ([OpenAiFormatAdapter.rejectsStreamOptions]). */
    @Volatile private var asksStreamUsage = true

    override suspend fun streamMessage(
        request: MessagesRequest,
        callback: StreamingCallback,
    ) = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        var received = false

        fun stream(includeUsage: Boolean) {
            val openAiJson = OpenAiFormatAdapter.toOpenAiRequestJson(request.copy(stream = true), includeUsage = includeUsage)
            Log.d(TAG, "streamMessage: model=${request.model}")

            val accumulator = OpenAiStreamAccumulator(callback, PROVIDER)

            // Failures are thrown, never passed to callback.onError: the Go bridge reports a
            // non-2xx as an error ("HTTP 403: …"), and only a thrown AnthropicApiException reaches
            // withRetry (429/5xx), ZeroBalanceFallbackClient and the chat's top-up prompt (403
            // "Insufficient balance"), and reactive compaction (413). Reported through onError the
            // turn carried on as an empty reply, recorded OK.
            try {
                tinfoilbridge.Tinfoilbridge.proxiedChatCompletionStream(
                    openAiJson,
                    proxyUrl,
                    userId(),
                    signature(),
                    channel(),
                    object : tinfoilbridge.StreamCallback {
                        override fun onData(data: String): Boolean {
                            // Returning true is the only way to stop the Go read loop: the turn was
                            // cancelled, so stop reading (and stop paying for) the reply.
                            if (job?.isActive == false) return true
                            received = true
                            return accumulator.onData(data)
                        }

                        override fun onError(err: String) {
                            // The bridge returns the same error from the call; it is thrown there.
                            Log.e(TAG, "streamMessage bridge error: $err")
                        }
                    },
                )
            } catch (e: Exception) {
                ensureActive()
                Log.e(TAG, "streamMessage failed", e)
                throw StreamErrors.fromBridgeError(e.message, PROVIDER, e)
            }
            ensureActive()
            // An error chunk, an unreadable chunk, or a body that ended without [DONE] or a
            // finish_reason is thrown here.
            accumulator.finishStream()
        }

        val askUsage = asksStreamUsage
        try {
            stream(includeUsage = askUsage)
        } catch (e: AnthropicApiException) {
            // Refused before a byte of the reply arrived, so the callback has seen nothing yet.
            if (!askUsage || received || !OpenAiFormatAdapter.rejectsStreamOptions(e)) throw e
            Log.w(TAG, "streamMessage: the enclave refuses stream_options; streaming without usage")
            asksStreamUsage = false
            stream(includeUsage = false)
        }
    }
}
