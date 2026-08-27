package org.ethereumphone.andyclaw.llm

import android.util.Log

/**
 * Keeps the ambient surface alive when the user's balance runs out.
 *
 * `agent-first-plan.md` §D.3: `user_funds.usd_balance` is one column shared between LLM
 * spend and sponsored gas, and below `MINIMUM_BALANCE_USD` the gateway stops serving. On a
 * device where the agent is a chat feature that is an annoyance. On an agent-first device,
 * where the home screen *is* the agent, it means the phone stops working — the executive
 * summary goes blank and the heartbeat stops thinking, with no explanation the user can act
 * on from the lock screen.
 *
 * So the background paths get a floor: when the gateway refuses for lack of funds, the
 * request is served again by the bundled Qwen 2.5 1.5B, on-device, at zero cost. The
 * cheapest agent is the one that doesn't call a model; the second cheapest is one that
 * calls a model already on the phone.
 *
 * Three deliberate limits.
 *
 * **Only insufficient funds.** A 500, a timeout, a rate limit and a bad request all stay
 * errors. Silently answering a broken request with a 1.5B model would turn every transient
 * gateway fault into a quiet quality regression that nobody could see in a log.
 *
 * **Only the background paths.** [NodeApp] applies this to the summary, the heartbeat and
 * compaction — work the user did not ask for and is not waiting on. Interactive chat keeps
 * failing loudly, because a user who typed a question deserves to be told their balance is
 * empty rather than handed a visibly worse answer and left to wonder why.
 *
 * **Only when a model is actually present.** The GGUF is a couple of gigabytes and may
 * never have been downloaded. [localAvailable] is checked before the retry, and when it is
 * false the original refusal propagates untouched — which is the honest outcome, and the
 * same one as before this class existed.
 */
class ZeroBalanceFallbackClient(
    private val primary: LlmClient,
    private val local: LlmClient,
    /** False when no GGUF is on disk; then there is nothing to fall back to. */
    private val localAvailable: () -> Boolean,
    /** True only while the user is actually on the gateway that can run out of funds. */
    private val usingPremiumGateway: () -> Boolean,
) : LlmClient {

    override suspend fun sendMessage(request: MessagesRequest): MessagesResponse = try {
        primary.sendMessage(request)
    } catch (e: Exception) {
        if (!shouldFallBack(e)) throw e
        Log.w(TAG, "premium gateway is out of funds; serving locally", e)
        local.sendMessage(request)
    }

    override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) {
        if (!usingPremiumGateway() || !localAvailable()) {
            primary.streamMessage(request, callback)
            return
        }
        // A stream can refuse before it emits anything, which is the case worth catching,
        // or fail partway through, which is not: tokens have already reached the caller and
        // restarting on a different model would splice two different answers together.
        val guard = FirstTokenGuard(callback)
        try {
            primary.streamMessage(request, guard)
        } catch (e: Exception) {
            if (guard.emitted || !isInsufficientFunds(e)) throw e
            Log.w(TAG, "premium gateway is out of funds; streaming locally", e)
            local.streamMessage(request, callback)
        }
    }

    /**
     * The local model takes far fewer tools than a cloud one, and the tool list is built
     * before anyone knows which client will serve the request. Reporting the smaller of the
     * two keeps a fallback from arriving at a model with more tools than it can hold.
     */
    override val maxToolCount: Int
        get() {
            val p = primary.maxToolCount
            val l = local.maxToolCount
            return when {
                !localAvailable() || !usingPremiumGateway() -> p
                p < 0 -> l
                l < 0 -> p
                else -> minOf(p, l)
            }
        }

    private fun shouldFallBack(e: Exception): Boolean =
        usingPremiumGateway() && localAvailable() && isInsufficientFunds(e)

    /**
     * Notes whether anything reached the caller.
     *
     * Everything is passed straight through; the only state is the flag. It exists because
     * "the request was refused" and "the request half-succeeded and then broke" look the
     * same from a catch block, and only the first is safe to retry.
     */
    private class FirstTokenGuard(private val delegate: StreamingCallback) : StreamingCallback by delegate {
        @Volatile var emitted: Boolean = false
            private set

        override fun onToken(text: String) {
            emitted = true
            delegate.onToken(text)
        }
    }

    companion object {
        private const val TAG = "ZeroBalanceFallback"

        /**
         * The gateway's refusal, and only that.
         *
         * 403 plus the message the API actually sends. Matching on the status alone would
         * catch an auth failure — a wallet signature the backend rejected — and answering
         * that locally would hide a real problem behind a working-looking agent.
         */
        fun isInsufficientFunds(e: Throwable): Boolean =
            e is AnthropicApiException &&
                e.statusCode == 403 &&
                e.message?.contains("Insufficient balance", ignoreCase = true) == true
    }
}
