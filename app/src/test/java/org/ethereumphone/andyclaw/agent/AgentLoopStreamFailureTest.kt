package org.ethereumphone.andyclaw.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.ethereumphone.andyclaw.llm.AnthropicApiException
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.LlmClient
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.llm.MessageContent
import org.ethereumphone.andyclaw.llm.MessagesRequest
import org.ethereumphone.andyclaw.llm.MessagesResponse
import org.ethereumphone.andyclaw.llm.StreamingCallback
import org.ethereumphone.andyclaw.llm.ToolArguments
import org.ethereumphone.andyclaw.llm.Usage
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** How a turn behaves when the model call fails, is cut off, or is too long. */
class AgentLoopStreamFailureTest {

    @Before
    fun setup() {
        ExecutionEngineFactory.clearRouteMemory()
    }

    private fun done(model: String) = MessagesResponse(
        id = "r", type = "message", role = "assistant",
        content = listOf(ContentBlock.TextBlock("Hello world")), model = model,
        stopReason = "end_turn", usage = Usage(1, 1),
    )

    private class RetryRecorder : RecordingCallbacks() {
        val discarded = mutableListOf<Int>()
        override fun onStreamRetry(discardedChars: Int) { discarded += discardedChars }
    }

    @Test
    fun `a stream retried after dropping mid-reply does not repeat the text`() = runBlocking {
        var calls = 0
        val client = object : LlmClient {
            override suspend fun sendMessage(request: MessagesRequest) = error("unused")
            override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) {
                calls++
                callback.onToken("Hello ")
                if (calls == 1) throw IOException("connection reset")
                callback.onToken("world")
                callback.onComplete(done(request.model))
            }
        }
        val callbacks = RetryRecorder()
        AgentLoop(client, NativeSkillRegistry(), Tier.OPEN, enabledSkillIds = emptySet()).run("hi", emptyList(), callbacks)

        assertEquals(2, calls)
        assertEquals("Hello world", callbacks.completed)
        assertEquals(listOf("Hello ".length), callbacks.discarded)
    }

    @Test
    fun `a client that reports through onError ends the turn with that error, not an empty reply`() = runBlocking {
        val client = object : LlmClient {
            override suspend fun sendMessage(request: MessagesRequest) = error("unused")
            override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) {
                callback.onError(RuntimeException("model not loaded"))
            }
        }
        val callbacks = RecordingCallbacks()
        AgentLoop(client, NativeSkillRegistry(), Tier.OPEN, enabledSkillIds = emptySet()).run("hi", emptyList(), callbacks)

        assertNull(callbacks.completed)
        assertEquals(1, callbacks.errors.size)
        assertEquals("model not loaded", callbacks.errors.single().message)
    }

    /** Counts every terminal callback, so a second one cannot hide behind the first. */
    private class TerminalCounter : RecordingCallbacks() {
        val terminals = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun onComplete(fullText: String, tokenUsage: TokenUsageSnapshot?) {
            terminals += "complete"
            super.onComplete(fullText, tokenUsage)
        }
        override fun onError(error: Throwable) {
            terminals += "error"
            super.onError(error)
        }
    }

    /**
     * IPC-06: a client that reports a bad event through onError and then streams on to a normal
     * end — SseParser's old shape, and any third-party client's — ends the turn exactly once.
     */
    @Test
    fun `an error reported mid-stream is the one terminal callback, whatever the stream does next`() = runBlocking {
        val client = object : LlmClient {
            override suspend fun sendMessage(request: MessagesRequest) = error("unused")
            override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) {
                callback.onToken("Hel")
                callback.onError(RuntimeException("bad event"))
                callback.onToken("lo")
                callback.onComplete(done(request.model))
            }
        }
        val callbacks = TerminalCounter()
        AgentLoop(client, NativeSkillRegistry(), Tier.OPEN, enabledSkillIds = emptySet()).run("hi", emptyList(), callbacks)

        assertEquals(listOf("error"), callbacks.terminals.toList())
        assertEquals("bad event", callbacks.errors.single().message)
    }

    @Test
    fun `a client that errors and returns ends the turn once`() = runBlocking {
        val client = object : LlmClient {
            override suspend fun sendMessage(request: MessagesRequest) = error("unused")
            override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) {
                callback.onError(AnthropicApiException(401, "invalid key"))
            }
        }
        val callbacks = TerminalCounter()
        AgentLoop(client, NativeSkillRegistry(), Tier.OPEN, enabledSkillIds = emptySet()).run("hi", emptyList(), callbacks)
        assertEquals(listOf("error"), callbacks.terminals.toList())
    }

    @Test
    fun `truncated tool arguments are answered with an error and never run`() = runBlocking {
        var ran = false
        val registry = NativeSkillRegistry().apply {
            register(testSkill("s", testTool("send")) { _, _ -> ran = true; SkillResult.Success("sent") })
        }
        val broken = ToolArguments.parse("{\"to\":\"0xab")
        val client = ScriptedLlmClient(listOf(listOf(toolUse("t1", "send", broken))))
        AgentLoop(client, registry, Tier.OPEN, enabledSkillIds = setOf("s")).run("go", emptyList(), RecordingCallbacks())

        assertFalse(ran)
        val result = client.requests[1].messages
            .flatMap { (it.content as? MessageContent.Blocks)?.blocks.orEmpty() }
            .filterIsInstance<ContentBlock.ToolResult>()
            .single()
        assertEquals("t1", result.toolUseId)
        assertTrue(result.isError)
        assertEquals(ToolArguments.INVALID_MESSAGE, result.content)
    }

    @Test
    fun `a no-argument tool call still runs`() = runBlocking {
        var ran = false
        val registry = NativeSkillRegistry().apply {
            register(testSkill("s", testTool("list")) { _, _ -> ran = true; SkillResult.Success("ok") })
        }
        val client = ScriptedLlmClient(listOf(listOf(toolUse("t1", "list", ToolArguments.parse("")))))
        AgentLoop(client, registry, Tier.OPEN, enabledSkillIds = setOf("s")).run("go", emptyList(), RecordingCallbacks())
        assertTrue(ran)
    }

    @Test
    fun `reactive compaction that cannot make the prompt fit stops instead of looping`() = runBlocking {
        var streams = 0
        var summaries = 0
        val client = object : LlmClient {
            override suspend fun sendMessage(request: MessagesRequest): MessagesResponse {
                summaries++
                return MessagesResponse(
                    id = "s", type = "message", role = "assistant",
                    content = listOf(ContentBlock.TextBlock("<summary>short</summary>")),
                    model = request.model, stopReason = "end_turn",
                )
            }
            override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) {
                streams++
                // The oversize is in what compaction keeps: every request is rejected.
                throw AnthropicApiException(413, "prompt is too long")
            }
        }
        val history = (1..20).flatMap {
            listOf(Message.user("question $it " + "x".repeat(400)), Message.assistant(listOf(ContentBlock.TextBlock("answer $it " + "y".repeat(400)))))
        }
        val callbacks = RecordingCallbacks()
        AgentLoop(
            client, NativeSkillRegistry(), Tier.OPEN, enabledSkillIds = emptySet(),
            compactionConfig = CompactionConfig(enabled = true),
        ).run("hi", history, callbacks)

        assertNull(callbacks.completed)
        assertEquals(1, callbacks.errors.size)
        assertTrue(callbacks.errors.single().message!!.contains("too long"))
        assertTrue("summary calls must be bounded, were $summaries", summaries <= ReactiveCompaction.MAX_REACTIVE_ATTEMPTS_PER_RUN)
        assertTrue("model calls must be bounded, were $streams", streams <= ReactiveCompaction.MAX_REACTIVE_ATTEMPTS_PER_RUN + 1)
    }

    @Test
    fun `compaction that does not shrink the history is a failure`() = runBlocking {
        val compactor = object {
            suspend fun run(): ContextCompactor.CompactionResult? {
                val grows = object : LlmClient {
                    override suspend fun sendMessage(request: MessagesRequest) = MessagesResponse(
                        id = "s", type = "message", role = "assistant",
                        content = listOf(ContentBlock.TextBlock("z".repeat(50_000))),
                        model = request.model, stopReason = "end_turn",
                    )
                    override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) = error("unused")
                }
                val history = (1..8).map { Message.user("m$it") }.toMutableList()
                return ReactiveCompaction(
                    ContextCompactor(grows, CompactionConfig(microcompactEnabled = false)), "m",
                ).tryReactiveCompact(history, AutoCompactTrackingState())
            }
        }
        assertNull(compactor.run())
    }

    @Test
    fun `size estimate counts tool inputs and results, not only text`() {
        val small = listOf(Message.user("hi"))
        val big = listOf(
            Message.assistant(listOf(ContentBlock.ToolUseBlock("a", "t", JsonObject(mapOf("k" to kotlinx.serialization.json.JsonPrimitive("v".repeat(1000))))))),
            Message("user", MessageContent.Blocks(listOf(ContentBlock.ToolResult("a", "r".repeat(1000))))),
        )
        assertTrue(ContextCompactor.estimateSize(big) > 2000)
        assertEquals(2L, ContextCompactor.estimateSize(small))
    }
}
