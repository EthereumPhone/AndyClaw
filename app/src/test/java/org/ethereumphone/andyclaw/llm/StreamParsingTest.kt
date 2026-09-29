package org.ethereumphone.andyclaw.llm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * The stream parsers decide what counts as a finished reply, what counts as a failure, and
 * which tool calls are safe to run. Each of these used to be too lenient: a cut-off stream
 * was a "successful" half answer, an in-band error was ignored, broken tool arguments ran
 * as `{}`, and a gateway refusal never reached anything that could act on it.
 */
class StreamParsingTest {

    private class Recorder : StreamingCallback {
        val tokens = StringBuilder()
        val tools = mutableListOf<Triple<String, String, JsonObject>>()
        var completed: MessagesResponse? = null
        val errors = mutableListOf<Throwable>()
        override fun onToken(text: String) { tokens.append(text) }
        override fun onToolUse(id: String, name: String, input: JsonObject) { tools += Triple(id, name, input) }
        override fun onComplete(response: MessagesResponse) { completed = response }
        override fun onError(error: Throwable) { errors += error }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.simpleName}, got $t", t)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    // ── OpenAI format ───────────────────────────────────────────────

    private fun chunk(delta: String, finish: String? = null) =
        """{"id":"c1","model":"m","choices":[{"index":0,"delta":$delta,"finish_reason":${finish?.let { "\"$it\"" } ?: "null"}}]}"""

    @Test
    fun `openai stream completes on DONE`() {
        val r = Recorder()
        val acc = OpenAiStreamAccumulator(r)
        assertFalse(acc.onData(chunk("""{"content":"Hel"}""")))
        assertFalse(acc.onData(chunk("""{"content":"lo"}""", finish = "stop")))
        assertTrue(acc.onData("[DONE]"))
        acc.finishStream()
        assertEquals("Hello", r.tokens.toString())
        assertEquals("end_turn", r.completed!!.stopReason)
        assertEquals("Hello", (r.completed!!.content.single() as ContentBlock.TextBlock).text)
        assertTrue(r.errors.isEmpty())
    }

    @Test
    fun `openai stream that ends without DONE or finish_reason is a dropped connection`() {
        val r = Recorder()
        val acc = OpenAiStreamAccumulator(r)
        acc.onData(chunk("""{"content":"half an ans"}"""))
        assertThrows<IOException> { acc.finishStream() }
        assertNull(r.completed)
    }

    @Test
    fun `openai stream with finish_reason but no DONE is complete`() {
        val r = Recorder()
        val acc = OpenAiStreamAccumulator(r)
        acc.onData(chunk("""{"content":"ok"}""", finish = "stop"))
        acc.finishStream()
        assertEquals("ok", (r.completed!!.content.single() as ContentBlock.TextBlock).text)
    }

    @Test
    fun `openai in-band error chunk is thrown with its status`() {
        val r = Recorder()
        val acc = OpenAiStreamAccumulator(r, "Tinfoil")
        acc.onData(chunk("""{"content":"par"}"""))
        assertTrue(acc.onData("""{"error":{"message":"upstream overloaded","type":"overloaded_error"}}"""))
        val e = assertThrows<AnthropicApiException> { acc.finishStream() }
        assertEquals(529, e.statusCode)
        assertNull(r.completed)

        val acc2 = OpenAiStreamAccumulator(Recorder())
        acc2.onData("""{"error":{"code":502,"message":"bad gateway"}}""")
        assertEquals(502, assertThrows<AnthropicApiException> { acc2.finishStream() }.statusCode)
    }

    @Test
    fun `openai unreadable chunk is a failure, not skipped`() {
        val acc = OpenAiStreamAccumulator(Recorder())
        assertTrue(acc.onData("{not json"))
        assertThrows<IOException> { acc.finishStream() }
    }

    @Test
    fun `openai null usage and null tool_calls are tolerated`() {
        val r = Recorder()
        val acc = OpenAiStreamAccumulator(r)
        acc.onData("""{"id":"c","usage":null,"choices":[{"delta":{"content":"a","tool_calls":null},"finish_reason":null}]}""")
        acc.onData("""{"id":"c","choices":[],"usage":{"prompt_tokens":5,"completion_tokens":2,"prompt_tokens_details":null}}""")
        acc.onData("[DONE]")
        acc.finishStream()
        assertEquals(5, r.completed!!.usage!!.inputTokens)
        assertEquals(2, r.completed!!.usage!!.outputTokens)
    }

    @Test
    fun `openai parallel calls without index or sharing index 0 stay separate`() {
        val r = Recorder()
        val acc = OpenAiStreamAccumulator(r)
        // No index at all, distinguished by id
        acc.onData(chunk("""{"tool_calls":[{"id":"a","function":{"name":"one","arguments":"{\"x\":"}}]}"""))
        acc.onData(chunk("""{"tool_calls":[{"function":{"arguments":"1}"}}]}"""))
        // Same index 0, new id
        acc.onData(chunk("""{"tool_calls":[{"index":0,"id":"b","function":{"name":"two","arguments":"{\"y\":2}"}}]}""", finish = "tool_calls"))
        acc.onData("[DONE]")
        acc.finishStream()
        assertEquals(listOf("a", "b"), r.tools.map { it.first })
        assertEquals("1", r.tools[0].third["x"]!!.jsonPrimitive.content)
        assertEquals("2", r.tools[1].third["y"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai indexed fragments assemble as before`() {
        val r = Recorder()
        val acc = OpenAiStreamAccumulator(r)
        acc.onData(chunk("""{"tool_calls":[{"index":0,"id":"a","function":{"name":"one","arguments":""}},{"index":1,"id":"b","function":{"name":"two","arguments":""}}]}"""))
        acc.onData(chunk("""{"tool_calls":[{"index":1,"function":{"arguments":"{\"q\":1}"}}]}"""))
        acc.onData(chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]}""", finish = "tool_calls"))
        acc.onData("[DONE]")
        acc.finishStream()
        assertEquals(listOf("one", "two"), r.tools.map { it.second })
        assertTrue(r.tools[0].third.isEmpty())
        assertFalse(ToolArguments.isInvalid(r.tools[0].third))
        assertEquals("1", r.tools[1].third["q"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai truncated tool arguments are marked invalid, empty ones are not`() {
        val r = Recorder()
        val acc = OpenAiStreamAccumulator(r)
        acc.onData(chunk("""{"tool_calls":[{"index":0,"id":"a","function":{"name":"send","arguments":"{\"to\":\"0xab"}},{"index":1,"id":"b","function":{"name":"list"}}]}""", finish = "length"))
        acc.onData("[DONE]")
        acc.finishStream()
        assertTrue(ToolArguments.isInvalid(r.tools[0].third))
        assertFalse(ToolArguments.isInvalid(r.tools[1].third))
        assertEquals("max_tokens", r.completed!!.stopReason)
    }

    // ── Anthropic format ────────────────────────────────────────────

    private fun SseParser.feed(vararg events: Pair<String, String>) = events.forEach { (e, d) -> onEvent(e, d) }

    private val start = "message_start" to """{"type":"message_start","message":{"id":"m1","model":"claude","usage":{"input_tokens":3,"output_tokens":0}}}"""
    private fun textStart(i: Int) = "content_block_start" to """{"index":$i,"content_block":{"type":"text","text":""}}"""
    private fun thinkingStart(i: Int) = "content_block_start" to """{"index":$i,"content_block":{"type":"thinking","thinking":""}}"""
    private fun text(i: Int, t: String) = "content_block_delta" to """{"index":$i,"delta":{"type":"text_delta","text":"$t"}}"""
    private fun stop(i: Int) = "content_block_stop" to """{"index":$i}"""
    private fun toolStart(i: Int, id: String, name: String) = "content_block_start" to """{"index":$i,"content_block":{"type":"tool_use","id":"$id","name":"$name","input":{}}}"""
    private fun json(i: Int, part: String) = "content_block_delta" to """{"index":$i,"delta":{"type":"input_json_delta","partial_json":${kotlinx.serialization.json.JsonPrimitive(part)}}}"""
    private val delta = "message_delta" to """{"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}"""
    private val messageStop = "message_stop" to """{"type":"message_stop"}"""

    @Test
    fun `anthropic text blocks are not duplicated and blank ones are dropped`() {
        val r = Recorder()
        val p = SseParser(r)
        p.feed(start, textStart(0), text(0, "first"), stop(0), thinkingStart(1), stop(1),
            textStart(2), text(2, "\\n\\n"), stop(2), textStart(3), text(3, "second"), stop(3), delta, messageStop)
        p.finishStream()
        val texts = r.completed!!.content.filterIsInstance<ContentBlock.TextBlock>().map { it.text }
        assertEquals(listOf("first", "second"), texts)
    }

    @Test
    fun `anthropic error event is thrown, overloaded as 529`() {
        val p = SseParser(Recorder())
        p.feed(start, textStart(0), text(0, "par"))
        val e = assertThrows<AnthropicApiException> {
            p.onEvent("error", """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        }
        assertEquals(529, e.statusCode)
    }

    @Test
    fun `anthropic stream cut before message_stop throws, stop_reason alone completes`() {
        val cut = SseParser(Recorder())
        cut.feed(start, textStart(0), text(0, "half"))
        assertThrows<IOException> { cut.finishStream() }

        val r = Recorder()
        val noStop = SseParser(r)
        noStop.feed(start, textStart(0), text(0, "whole"), stop(0), delta)
        noStop.finishStream()
        assertEquals("whole", (r.completed!!.content.single() as ContentBlock.TextBlock).text)
    }

    @Test
    fun `anthropic truncated tool json is marked invalid, no-arg tool is not`() {
        val r = Recorder()
        val p = SseParser(r)
        p.feed(start, toolStart(0, "t1", "send"), json(0, "{\"to\":\"0x"), stop(0),
            toolStart(1, "t2", "list"), stop(1), delta, messageStop)
        p.finishStream()
        assertTrue(ToolArguments.isInvalid(r.tools[0].third))
        assertFalse(ToolArguments.isInvalid(r.tools[1].third))
        assertTrue(r.tools[1].third.isEmpty())
    }

    // ── Non-streaming OpenAI ────────────────────────────────────────

    @Test
    fun `openai response with null usage and broken arguments`() {
        val resp = OpenAiFormatAdapter.fromOpenAiResponseJson(
            """{"id":"x","model":"m","usage":null,"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[
               {"id":"a","function":{"name":"send","arguments":"{\"to\":"}},
               {"id":"b","function":{"name":"list","arguments":""}}]}}]}"""
        )
        val tools = resp.content.filterIsInstance<ContentBlock.ToolUseBlock>()
        assertNull(resp.usage)
        assertTrue(ToolArguments.isInvalid(tools[0].input))
        assertFalse(ToolArguments.isInvalid(tools[1].input))
    }

    // ── Errors and arguments ────────────────────────────────────────

    @Test
    fun `bridge status errors become API errors the fallback and top-up prompt recognise`() {
        val e = StreamErrors.fromBridgeError("HTTP 403: {\"error\":\"Insufficient balance\"}", "Tinfoil")
        assertTrue(e is AnthropicApiException)
        assertEquals(403, (e as AnthropicApiException).statusCode)
        assertTrue(ZeroBalanceFallbackClient.isInsufficientFunds(e))

        val limited = StreamErrors.fromBridgeError("HTTP 429: slow down\nplease", "Tinfoil")
        assertEquals(429, (limited as AnthropicApiException).statusCode)

        assertTrue(StreamErrors.fromBridgeError("request failed: dial tcp: i/o timeout", "Tinfoil") is IOException)
        assertTrue(StreamErrors.fromBridgeError(null, "Tinfoil") is IOException)
    }

    @Test
    fun `tool arguments parse`() {
        assertTrue(ToolArguments.parse("").isEmpty())
        assertTrue(ToolArguments.parse(null).isEmpty())
        assertTrue(ToolArguments.parse("{}").isEmpty())
        assertTrue(ToolArguments.parse("null").isEmpty())
        assertEquals("1", ToolArguments.parse("{\"a\":1}")["a"]!!.jsonPrimitive.content)
        assertTrue(ToolArguments.isInvalid(ToolArguments.parse("{\"a\":")))
        assertTrue(ToolArguments.isInvalid(ToolArguments.parse("[1,2]")))
        val block = ContentBlock.ToolUseBlock("id", "t", ToolArguments.parse("{\"a\""))
        assertNotNull(ToolArguments.errorResultOrNull(block))
        assertNull(ToolArguments.errorResultOrNull(ContentBlock.ToolUseBlock("id", "t", JsonObject(emptyMap()))))
    }

    // ── Retry ───────────────────────────────────────────────────────

    @Test
    fun `retry-after beyond the cap fails at once instead of sleeping`() = runBlocking {
        var calls = 0
        try {
            withRetry(RetryPolicy(maxRetries = 5)) {
                calls++
                throw AnthropicApiException(429, "quota", retryAfterSeconds = 3600)
            }
            fail("should not succeed")
        } catch (e: CannotRetryException) {
            assertEquals(429, e.statusCode)
        }
        assertEquals(1, calls)
    }

    @Test
    fun `a gateway refusal thrown by a stream falls back to local before any token`() = runBlocking {
        val refusing = object : LlmClient {
            override suspend fun sendMessage(request: MessagesRequest) = error("unused")
            override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) {
                throw StreamErrors.fromBridgeError("HTTP 403: Insufficient balance", "Tinfoil")
            }
        }
        var localCalled = false
        val local = object : LlmClient {
            override suspend fun sendMessage(request: MessagesRequest) = error("unused")
            override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) {
                localCalled = true
            }
        }
        ZeroBalanceFallbackClient(refusing, local, { true }, { true })
            .streamMessage(MessagesRequest(model = "m", maxTokens = 8, messages = emptyList()), Recorder())
        assertTrue(localCalled)
    }
}
