package org.ethereumphone.andyclaw.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
        private const val PROVIDER = "Tinfoil"
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
                throw StreamErrors.fromBridgeError(e.message, PROVIDER, e)
            }

            OpenAiFormatAdapter.fromOpenAiResponseJson(responseJson)
        }

    override suspend fun streamMessage(
        request: MessagesRequest,
        callback: StreamingCallback,
    ) = withContext(Dispatchers.IO) {
        val openAiJson = OpenAiFormatAdapter.toOpenAiRequestJson(request.copy(stream = true))
        Log.d(TAG, "streamMessage: model=${request.model}")

        val accumulator = OpenAiStreamAccumulator(callback, PROVIDER)
        val job = coroutineContext[Job]

        // Failures are thrown, never passed to callback.onError: the Go bridge reports a
        // non-2xx as an error ("HTTP 403: …"), and only a thrown AnthropicApiException reaches
        // withRetry (429/5xx), ZeroBalanceFallbackClient and the chat's top-up prompt (403
        // "Insufficient balance"), and reactive compaction (413). Reported through onError the
        // turn carried on as an empty reply, recorded OK.
        try {
            tinfoilbridge.Tinfoilbridge.verifiedChatCompletionStream(
                openAiJson,
                apiKey(),
                object : tinfoilbridge.StreamCallback {
                    override fun onData(data: String): Boolean {
                        // Returning true is the only way to stop the Go read loop: the turn was
                        // cancelled, so stop reading (and stop paying for) the reply.
                        if (job?.isActive == false) return true
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
}
