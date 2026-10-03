package org.ethereumphone.andyclaw.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each provider is sent and what is kept of its replies: fields a server rejects, blocks
 * it must get back, and the token counts the context bar and compaction run on.
 */
class ProviderWireFormatTest {

    private class Recorder : StreamingCallback {
        var completed: MessagesResponse? = null
        override fun onToken(text: String) {}
        override fun onToolUse(id: String, name: String, input: JsonObject) {}
        override fun onComplete(response: MessagesResponse) { completed = response }
        override fun onError(error: Throwable) { throw AssertionError(error) }
    }

    private val tool = buildJsonObject {
        put("name", "get_time")
        put("description", "Get the time")
        put("input_schema", buildJsonObject { put("type", "object") })
    }

    private fun request(vararg messages: Message, parallel: Boolean = true) = MessagesRequest(
        model = "claude-sonnet-5",
        maxTokens = 64,
        messages = messages.toList(),
        tools = listOf(tool),
        stream = true,
        parallelToolCalls = parallel,
        reasoning = ReasoningConfig(effort = "none"),
    )

    private val direct = AnthropicClient(
        apiKey = { "k" },
        baseUrl = "https://api.anthropic.com/v1/messages",
        anthropicDirect = true,
    )
    private val gateway = AnthropicClient(apiKey = { "k" }, baseUrl = "https://openrouter.ai/api/v1/messages")

    private fun AnthropicClient.body(request: MessagesRequest): JsonObject =
        Json.parseToJsonElement(serializeRequest(request)).jsonObject

    private fun SseParser.feed(vararg events: Pair<String, String>) = events.forEach { (e, d) -> onEvent(e, d) }

    /** A thinking model's tool-use turn as Anthropic streams it. */
    private val thinkingTurn = arrayOf(
        "message_start" to """{"type":"message_start","message":{"id":"m1","model":"claude","usage":{"input_tokens":1200,"cache_read_input_tokens":800,"cache_creation_input_tokens":40,"output_tokens":1}}}""",
        "content_block_start" to """{"index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
        "content_block_delta" to """{"index":0,"delta":{"type":"thinking_delta","thinking":"Need the "}}""",
        "content_block_delta" to """{"index":0,"delta":{"type":"thinking_delta","thinking":"time."}}""",
        "content_block_delta" to """{"index":0,"delta":{"type":"signature_delta","signature":"EqQBCgIYAhIM"}}""",
        "content_block_stop" to """{"index":0}""",
        "content_block_start" to """{"index":1,"content_block":{"type":"redacted_thinking","data":"EmwKAhgB"}}""",
        "content_block_stop" to """{"index":1}""",
        "content_block_start" to """{"index":2,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"index":2,"delta":{"type":"text_delta","text":"Checking."}}""",
        "content_block_stop" to """{"index":2}""",
        "content_block_start" to """{"index":3,"content_block":{"type":"tool_use","id":"t1","name":"get_time","input":{}}}""",
        "content_block_stop" to """{"index":3}""",
        "message_delta" to """{"delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":57}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    // ── Anthropic's own API ─────────────────────────────────────────

    @Test
    fun `anthropic's own API is never sent the gateways' fields`() {
        // "Extra inputs are not permitted": every chat request carries tools, so every one failed.
        val sent = direct.body(request(Message.user("hi")))
        assertFalse("parallel_tool_calls" in sent)
        assertFalse("reasoning" in sent)
        assertFalse("tool_choice" in sent)

        val oneAtATime = direct.body(request(Message.user("hi"), parallel = false))["tool_choice"]!!.jsonObject
        assertEquals("auto", oneAtATime["type"]!!.jsonPrimitive.content)
        assertEquals("true", oneAtATime["disable_parallel_tool_use"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the gateways keep getting parallel_tool_calls and reasoning`() {
        val sent = gateway.body(request(Message.user("hi")))
        assertTrue("parallel_tool_calls" in sent)
        assertTrue("reasoning" in sent)
    }

    @Test
    fun `a tool-use turn keeps its thinking blocks and sends them back unchanged`() {
        val r = Recorder()
        SseParser(r, keepThinking = true).apply { feed(*thinkingTurn); finishStream() }
        val blocks = r.completed!!.content
        assertEquals(
            listOf(
                ContentBlock.ThinkingBlock("Need the time.", signature = "EqQBCgIYAhIM"),
                ContentBlock.RedactedThinkingBlock("EmwKAhgB"),
                ContentBlock.TextBlock("Checking."),
            ),
            blocks.take(3),
        )
        assertTrue(blocks[3] is ContentBlock.ToolUseBlock)

        val sent = direct.body(
            request(Message.user("time?"), Message.assistant(blocks), Message.toolResult("t1", "12:00"))
        )
        val assistant = sent["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        val thinking = assistant[0].jsonObject
        assertEquals("thinking", thinking["type"]!!.jsonPrimitive.content)
        assertEquals("Need the time.", thinking["thinking"]!!.jsonPrimitive.content)
        assertEquals("EqQBCgIYAhIM", thinking["signature"]!!.jsonPrimitive.content)
        val redacted = assistant[1].jsonObject
        assertEquals("redacted_thinking", redacted["type"]!!.jsonPrimitive.content)
        assertEquals("EmwKAhgB", redacted["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a gateway is not sent thinking blocks from a history kept across a provider switch`() {
        val history = request(
            Message.user("time?"),
            Message.assistant(listOf(ContentBlock.ThinkingBlock("x", signature = "s"), ContentBlock.TextBlock("Checking."))),
            Message.user("and now?"),
        )
        val assistant = gateway.body(history)["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("text"), assistant.map { it.jsonObject["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun `the gateways' replies are parsed as before, without thinking blocks`() {
        val r = Recorder()
        SseParser(r).apply { feed(*thinkingTurn); finishStream() }
        val blocks = r.completed!!.content
        assertEquals(2, blocks.size)
        assertEquals(ContentBlock.TextBlock("Checking."), blocks[0])
        assertTrue(blocks[1] is ContentBlock.ToolUseBlock)
    }

    @Test
    fun `message_delta's output count leaves message_start's prompt counts alone`() {
        val r = Recorder()
        SseParser(r).apply { feed(*thinkingTurn); finishStream() }
        val usage = r.completed!!.usage!!
        assertEquals(1200, usage.inputTokens)
        assertEquals(800, usage.cacheReadTokens)
        assertEquals(40, usage.cacheWriteTokens)
        assertEquals(57, usage.outputTokens)
    }

    // ── OpenAI format ───────────────────────────────────────────────

    @Test
    fun `stream usage is asked for only when the caller says the server takes it`() {
        val req = MessagesRequest(model = "gpt", maxTokens = 8, messages = listOf(Message.user("hi")), stream = true)
        fun sent(r: MessagesRequest, includeUsage: Boolean) =
            Json.parseToJsonElement(OpenAiFormatAdapter.toOpenAiRequestJson(r, includeUsage = includeUsage)).jsonObject

        val options = sent(req, includeUsage = true)["stream_options"]!!.jsonObject
        assertEquals("true", options["include_usage"]!!.jsonPrimitive.content)
        assertFalse("stream_options" in sent(req, includeUsage = false))
        assertFalse("stream_options" in sent(req.copy(stream = false), includeUsage = true))
    }

    @Test
    fun `cached prompt tokens are not counted twice`() {
        // AgentLoop sizes the prompt as input + cache read; OpenAI's prompt_tokens already has the cache in it.
        val r = Recorder()
        val acc = OpenAiStreamAccumulator(r)
        acc.onData("""{"id":"c","choices":[{"delta":{"content":"a"},"finish_reason":"stop"}]}""")
        acc.onData("""{"id":"c","choices":[],"usage":{"prompt_tokens":1000,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":600}}}""")
        acc.onData("[DONE]")
        acc.finishStream()
        val usage = r.completed!!.usage!!
        assertEquals(400, usage.inputTokens)
        assertEquals(600, usage.cacheReadTokens)
        assertEquals(5, usage.outputTokens)
    }

    @Test
    fun `a screenshot goes in a user message after every tool result, not in the tool message`() {
        // Chat Completions accepts images only in user messages, and an assistant's tool_calls
        // must be answered by tool messages before anything else.
        val image = ToolResultContent.Image(ImageSource(mediaType = "image/jpeg", data = "QUJD"))
        val req = MessagesRequest(
            model = "gpt",
            maxTokens = 8,
            messages = listOf(
                Message.user("look"),
                Message.assistant(
                    listOf(
                        ContentBlock.ToolUseBlock("a", "agent_display_screenshot", JsonObject(emptyMap())),
                        ContentBlock.ToolUseBlock("b", "get_time", JsonObject(emptyMap())),
                    )
                ),
                Message(
                    "user",
                    MessageContent.Blocks(
                        listOf(
                            ContentBlock.ToolResult(
                                "a", "Screenshot taken",
                                contentBlocks = listOf(ToolResultContent.Text("Screenshot taken"), image),
                            ),
                            ContentBlock.ToolResult("b", "12:00"),
                        )
                    ),
                ),
            ),
        )
        val messages = Json.parseToJsonElement(OpenAiFormatAdapter.toOpenAiRequestJson(req))
            .jsonObject["messages"]!!.jsonArray

        assertEquals(
            listOf("user", "assistant", "tool", "tool", "user"),
            messages.map { it.jsonObject["role"]!!.jsonPrimitive.content },
        )
        assertEquals("Screenshot taken", messages[2].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("12:00", messages[3].jsonObject["content"]!!.jsonPrimitive.content)
        val parts = messages[4].jsonObject["content"]!!.jsonArray
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }
}
