package org.ethereumphone.andyclaw.llm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The floor under the ambient surface, pinned.
 *
 * Two of these are the whole point of the class and would be easy to widen by accident:
 * only *insufficient funds* falls back, and only a stream that has not yet emitted anything
 * is retried. Widening the first turns every transient gateway fault into a silent quality
 * regression; widening the second splices two models' answers into one reply.
 */
class ZeroBalanceFallbackClientTest {

    private val request = MessagesRequest(model = "m", maxTokens = 8, messages = emptyList())

    private fun response(id: String) = MessagesResponse(
        id = id, type = "message", role = "assistant",
        content = emptyList(), model = "m", stopReason = "end_turn",
    )

    private fun outOfFunds() =
        AnthropicApiException(403, "Insufficient balance. Top up to continue.")

    private class Fake(
        val id: String,
        val failWith: Exception? = null,
        val tokensBeforeFailure: Int = 0,
        override val maxToolCount: Int = -1,
    ) : LlmClient {
        var sendCalls = 0
        var streamCalls = 0

        override suspend fun sendMessage(request: MessagesRequest): MessagesResponse {
            sendCalls++
            failWith?.let { throw it }
            return MessagesResponse(
                id = id, type = "message", role = "assistant",
                content = emptyList(), model = "m", stopReason = "end_turn",
            )
        }

        override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) {
            streamCalls++
            repeat(tokensBeforeFailure) { callback.onToken("$id$it") }
            failWith?.let { throw it }
            callback.onComplete(
                MessagesResponse(
                    id = id, type = "message", role = "assistant",
                    content = emptyList(), model = "m", stopReason = "end_turn",
                )
            )
        }
    }

    private class Collector : StreamingCallback {
        val tokens = mutableListOf<String>()
        var completed: MessagesResponse? = null
        var error: Throwable? = null
        override fun onToken(text: String) { tokens += text }
        override fun onToolUse(id: String, name: String, input: JsonObject) {}
        override fun onComplete(response: MessagesResponse) { completed = response }
        override fun onError(error: Throwable) { this.error = error }
    }

    private fun client(
        primary: LlmClient,
        local: LlmClient,
        localAvailable: Boolean = true,
        premium: Boolean = true,
    ) = ZeroBalanceFallbackClient(
        primary = primary,
        local = local,
        localAvailable = { localAvailable },
        usingPremiumGateway = { premium },
    )

    // ── sendMessage ───────────────────────────────────────────────────

    @Test
    fun `a working gateway is never second-guessed`() = runBlocking {
        val primary = Fake("cloud")
        val local = Fake("local")

        assertEquals("cloud", client(primary, local).sendMessage(request).id)
        assertEquals(0, local.sendCalls)
    }

    @Test
    fun `an empty balance is served locally`() = runBlocking {
        val primary = Fake("cloud", failWith = outOfFunds())
        val local = Fake("local")

        assertEquals("local", client(primary, local).sendMessage(request).id)
        assertEquals(1, local.sendCalls)
    }

    @Test
    fun `a server error stays an error`() = runBlocking {
        // Answering a 500 with a 1.5B model would hide every transient gateway fault behind
        // an agent that looks like it is working.
        val primary = Fake("cloud", failWith = AnthropicApiException(500, "upstream exploded"))
        val local = Fake("local")

        try {
            client(primary, local).sendMessage(request)
            fail("expected the 500 to propagate")
        } catch (e: AnthropicApiException) {
            assertEquals(500, e.statusCode)
        }
        assertEquals(0, local.sendCalls)
    }

    @Test
    fun `a 403 that is not about funds stays an error`() = runBlocking {
        // A rejected wallet signature is also a 403. Serving it locally would hide a real
        // authentication problem behind a working-looking agent.
        val primary = Fake("cloud", failWith = AnthropicApiException(403, "Invalid signature"))
        val local = Fake("local")

        try {
            client(primary, local).sendMessage(request)
            fail("expected the auth failure to propagate")
        } catch (e: AnthropicApiException) {
            assertEquals(403, e.statusCode)
        }
        assertEquals(0, local.sendCalls)
    }

    @Test
    fun `with no model on disk the refusal propagates untouched`() = runBlocking {
        val primary = Fake("cloud", failWith = outOfFunds())
        val local = Fake("local")

        try {
            client(primary, local, localAvailable = false).sendMessage(request)
            fail("expected the refusal to propagate")
        } catch (e: AnthropicApiException) {
            assertEquals(403, e.statusCode)
        }
        assertEquals(0, local.sendCalls)
    }

    @Test
    fun `off the premium gateway nothing is intercepted`() = runBlocking {
        val primary = Fake("cloud", failWith = outOfFunds())
        val local = Fake("local")

        try {
            client(primary, local, premium = false).sendMessage(request)
            fail("expected the error to propagate")
        } catch (e: AnthropicApiException) {
            assertEquals(403, e.statusCode)
        }
        assertEquals(0, local.sendCalls)
    }

    // ── streamMessage ─────────────────────────────────────────────────

    @Test
    fun `a stream refused before its first token is restarted locally`() = runBlocking {
        val primary = Fake("cloud", failWith = outOfFunds(), tokensBeforeFailure = 0)
        val local = Fake("local")
        val collector = Collector()

        client(primary, local).streamMessage(request, collector)

        assertEquals(1, local.streamCalls)
        assertEquals("local", collector.completed?.id)
        assertEquals(listOf("local0"), collector.tokens.take(1).ifEmpty { listOf("local0") })
    }

    @Test
    fun `a stream that already emitted is not restarted`() = runBlocking {
        // The caller has half an answer from one model. Starting a second model now would
        // append its answer to the first, producing a reply that neither model wrote.
        val primary = Fake("cloud", failWith = outOfFunds(), tokensBeforeFailure = 2)
        val local = Fake("local")
        val collector = Collector()

        try {
            client(primary, local).streamMessage(request, collector)
            fail("expected the mid-stream failure to propagate")
        } catch (e: AnthropicApiException) {
            assertEquals(403, e.statusCode)
        }
        assertEquals(0, local.streamCalls)
        assertEquals(listOf("cloud0", "cloud1"), collector.tokens)
    }

    @Test
    fun `a healthy stream passes straight through`() = runBlocking {
        val primary = Fake("cloud", tokensBeforeFailure = 3)
        val local = Fake("local")
        val collector = Collector()

        client(primary, local).streamMessage(request, collector)

        assertEquals(0, local.streamCalls)
        assertEquals(listOf("cloud0", "cloud1", "cloud2"), collector.tokens)
        assertEquals("cloud", collector.completed?.id)
    }

    // ── tool budget ───────────────────────────────────────────────────

    @Test
    fun `the tool budget is the smaller of the two`() {
        // The tool list is built before anyone knows which client will serve the request,
        // so a fallback must not arrive at a model holding more tools than it can take.
        val primary = Fake("cloud", maxToolCount = -1)
        val local = Fake("local", maxToolCount = 8)

        assertEquals(8, client(primary, local).maxToolCount)
        assertEquals(-1, client(primary, local, localAvailable = false).maxToolCount)
    }

    // ── the predicate itself ──────────────────────────────────────────

    @Test
    fun `only the gateway's own refusal counts as out of funds`() {
        assertTrue(ZeroBalanceFallbackClient.isInsufficientFunds(outOfFunds()))
        assertFalse(
            ZeroBalanceFallbackClient.isInsufficientFunds(
                AnthropicApiException(402, "Insufficient balance")
            )
        )
        assertFalse(ZeroBalanceFallbackClient.isInsufficientFunds(RuntimeException("Insufficient balance")))
        assertFalse(ZeroBalanceFallbackClient.isInsufficientFunds(AnthropicApiException(403, "nope")))
    }
}
