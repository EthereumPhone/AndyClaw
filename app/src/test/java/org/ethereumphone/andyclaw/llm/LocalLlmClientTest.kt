package org.ethereumphone.andyclaw.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LocalLlmClient] tool call parsing and prompt formatting.
 * Runs on JVM — no device needed.
 */
class LocalLlmClientTest {

    private lateinit var client: LocalLlmClient

    @Before
    fun setUp() {
        client = LocalLlmClient(LlamaCpp(), null)
    }

    // ── Tool call parsing ───────────────────────────────────────────

    @Test
    fun `parses valid Hermes-style tool call`() {
        val text = "<tool_call>\n{\"name\": \"get_current_time\", \"arguments\": {}}\n</tool_call>"
        val tools = listOf(buildTool("get_current_time", "Get the current time"))

        val blocks = client.parseToolCalls(text, tools)

        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is ContentBlock.ToolUseBlock)
        assertEquals("get_current_time", (blocks[0] as ContentBlock.ToolUseBlock).name)
    }

    @Test
    fun `parses tool call with arguments`() {
        val text = "<tool_call>\n{\"name\": \"set_alarm\", \"arguments\": {\"time\": \"07:00\", \"label\": \"Wake up\"}}\n</tool_call>"
        val tools = listOf(buildTool("set_alarm", "Set an alarm"))

        val blocks = client.parseToolCalls(text, tools)

        assertEquals(1, blocks.size)
        val block = blocks[0] as ContentBlock.ToolUseBlock
        assertEquals("set_alarm", block.name)
        assertEquals("07:00", block.input["time"]?.jsonPrimitive?.content)
        assertEquals("Wake up", block.input["label"]?.jsonPrimitive?.content)
    }

    @Test
    fun `preserves text before tool call`() {
        val text = "Let me check.\n<tool_call>\n{\"name\": \"get_current_time\", \"arguments\": {}}\n</tool_call>"
        val tools = listOf(buildTool("get_current_time", "Get the current time"))

        val blocks = client.parseToolCalls(text, tools)

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is ContentBlock.TextBlock)
        assertEquals("Let me check.", (blocks[0] as ContentBlock.TextBlock).text)
        assertTrue(blocks[1] is ContentBlock.ToolUseBlock)
    }

    @Test
    fun `returns text block when no tool calls`() {
        val text = "Hello! How can I help?"
        val tools = listOf(buildTool("get_current_time", "Get the current time"))

        val blocks = client.parseToolCalls(text, tools)

        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is ContentBlock.TextBlock)
    }

    @Test
    fun `rejects unknown tool names`() {
        val text = "<tool_call>\n{\"name\": \"hack_nasa\", \"arguments\": {}}\n</tool_call>"
        val tools = listOf(buildTool("get_current_time", "Get the current time"))

        val blocks = client.parseToolCalls(text, tools)

        assertEquals(1, blocks.size)
        assertTrue("Should fall back to text", blocks[0] is ContentBlock.TextBlock)
    }

    @Test
    fun `handles malformed JSON`() {
        val text = "<tool_call>\n{not valid json}\n</tool_call>"
        val tools = listOf(buildTool("get_current_time", "Get the current time"))

        val blocks = client.parseToolCalls(text, tools)

        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is ContentBlock.TextBlock)
    }

    @Test
    fun `parses raw JSON tool call without wrapper tags`() {
        val text = """{"name": "get_current_time", "arguments": {}}"""
        val tools = listOf(buildTool("get_current_time", "Get the current time"))

        val blocks = client.parseToolCalls(text, tools)

        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is ContentBlock.ToolUseBlock)
        assertEquals("get_current_time", (blocks[0] as ContentBlock.ToolUseBlock).name)
    }

    @Test
    fun `parses tool_name key variant`() {
        val text = """{"tool_name": "set_alarm", "arguments": {"time": "07:00"}}"""
        val tools = listOf(buildTool("set_alarm", "Set an alarm"))

        val blocks = client.parseToolCalls(text, tools)

        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is ContentBlock.ToolUseBlock)
        assertEquals("set_alarm", (blocks[0] as ContentBlock.ToolUseBlock).name)
    }

    @Test
    fun `parses raw JSON with text before`() {
        val text = """Let me check the time.
{"name": "get_current_time", "arguments": {}}"""
        val tools = listOf(buildTool("get_current_time", "Get the current time"))

        val blocks = client.parseToolCalls(text, tools)

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is ContentBlock.TextBlock)
        assertTrue(blocks[1] is ContentBlock.ToolUseBlock)
    }

    @Test
    fun `returns text when no tools provided`() {
        val text = "<tool_call>\n{\"name\": \"get_current_time\", \"arguments\": {}}\n</tool_call>"

        val blocks = client.parseToolCalls(text, null)

        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is ContentBlock.TextBlock)
    }

    // ── Prompt formatting ───────────────────────────────────────────

    @Test
    fun `formatChatML injects tool schemas`() {
        val tools = listOf(
            buildTool("get_current_time", "Get the current date and time"),
            buildTool("set_alarm", "Set an alarm"),
        )
        val request = MessagesRequest(
            model = "test",
            maxTokens = 256,
            system = "You are Andy.",
            messages = listOf(Message.user("What time is it?")),
            tools = tools,
        )

        val prompt = client.formatChatML(request)

        assertTrue("Should contain tool name", prompt.contains("## get_current_time"))
        assertTrue("Should contain description", prompt.contains("Get the current date and time"))
        assertTrue("Should contain tool_call tag", prompt.contains("<tool_call>"))
        assertTrue("Should contain system text", prompt.contains("You are Andy."))
        assertTrue("Should contain user message", prompt.contains("What time is it?"))
    }

    @Test
    fun `formatChatML works without tools`() {
        val request = MessagesRequest(
            model = "test",
            maxTokens = 256,
            system = "You are Andy.",
            messages = listOf(Message.user("Hello")),
        )

        val prompt = client.formatChatML(request)

        assertFalse("No tool section", prompt.contains("# Tools"))
        assertTrue("Has system", prompt.contains("You are Andy."))
        assertTrue("Has user msg", prompt.contains("Hello"))
    }

    @Test
    fun `formatChatML prompt size is reasonable`() {
        val tools = listOf(
            buildTool("get_current_time", "Get the current date and time"),
            buildTool("set_alarm", "Set an alarm"),
            buildTool("execute_code", "Run code on the device"),
        )
        val request = MessagesRequest(
            model = "test",
            maxTokens = 256,
            system = "You are Andy, a helpful AI assistant on this phone.\nYou have tools available. Use them when the user asks you to do something.\nAlways use a tool instead of guessing. Be concise.\n",
            messages = listOf(Message.user("What time is it?")),
            tools = tools,
        )

        val prompt = client.formatChatML(request)
        // Rough estimate: ~3.5 chars/token. Target: <512 tokens
        val estimatedTokens = prompt.length / 3.5
        assertTrue(
            "Prompt should be <512 tokens (estimated: ${estimatedTokens.toInt()}, chars: ${prompt.length})",
            estimatedTokens < 512
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun buildTool(name: String, description: String): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {}
        }
    }
}
