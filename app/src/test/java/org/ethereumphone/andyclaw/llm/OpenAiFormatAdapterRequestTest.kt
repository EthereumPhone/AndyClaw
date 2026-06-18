package org.ethereumphone.andyclaw.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the OpenAI/Venice request builder against the "Unrecognized key(s) in
 * object" failure class.
 *
 * Venice's `POST /chat/completions` schema is strict (`additionalProperties:
 * false`), so any top-level key we emit that isn't in its allow-list is rejected
 * with HTTP 400 — surfaced in-app (pre-fix) as the misleading "anthropic api
 * error ... unrecognise key in object". The original culprit was a top-level
 * `verbosity` key (removed from the adapter in commit 28925f2); these tests lock
 * that fix in and catch any future key leak.
 *
 * Pure JVM test — no network, no API key. The allow-list is transcribed from
 * Venice's published OpenAPI spec: https://api.venice.ai/doc/api/swagger.yaml
 * (ChatCompletionRequest, top-level properties).
 */
class OpenAiFormatAdapterRequestTest {

    /** Top-level request properties Venice accepts (additionalProperties: false). */
    private val veniceAllowedTopLevelKeys = setOf(
        "frequency_penalty", "include", "logprobs", "max_completion_tokens",
        "max_temp", "max_tokens", "messages", "metadata", "min_p", "min_temp",
        "model", "n", "parallel_tool_calls", "presence_penalty", "prompt_cache_key",
        "prompt_cache_retention", "reasoning", "reasoning_effort", "repetition_penalty",
        "response_format", "seed", "stop", "stop_token_ids", "store", "stream",
        "stream_options", "temperature", "text", "tool_choice", "tools",
        "top_k", "top_logprobs", "top_p", "user", "venice_parameters",
    )

    private fun topLevelKeys(request: MessagesRequest): Set<String> =
        Json.parseToJsonElement(OpenAiFormatAdapter.toOpenAiRequestJson(request))
            .jsonObject.keys

    private fun sampleTool(): JsonObject = buildJsonObject {
        put("name", "get_current_time")
        put("description", "Get the current time in a timezone")
        put("input_schema", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("timezone", buildJsonObject { put("type", "string") })
            })
        })
    }

    // ── The exact regression: verbosity must never go out top-level ──────

    @Test
    fun `verbosity is never emitted as a top-level key`() {
        val request = MessagesRequest(
            model = "kimi-k2-5",
            maxTokens = 1024,
            messages = listOf(Message.user("hello")),
            verbosity = Verbosity.LOW, // set, as AgentLoop does for concise presets
        )
        assertFalse(
            "Top-level 'verbosity' is rejected by Venice; it is only valid nested under 'text'.",
            "verbosity" in topLevelKeys(request),
        )
    }

    // ── General guard: every emitted top-level key is Venice-legal ───────

    @Test
    fun `kitchen-sink request emits only Venice-allowed top-level keys`() {
        // Exercise every branch of toOpenAiRequestJson at once.
        val request = MessagesRequest(
            model = "kimi-k2-5",
            maxTokens = 2048,
            system = "You are a helpful assistant.",
            messages = listOf(
                Message.user("what time is it in Rome?"),
                Message.assistant(
                    listOf(
                        ContentBlock.TextBlock("Let me check."),
                        ContentBlock.ToolUseBlock(
                            id = "toolu_1",
                            name = "get_current_time",
                            input = buildJsonObject { put("timezone", "Europe/Rome") },
                        ),
                    )
                ),
                Message.toolResult(toolUseId = "toolu_1", content = "14:32"),
            ),
            tools = listOf(sampleTool()),
            stream = true,
            parallelToolCalls = true,
            verbosity = Verbosity.LOW,
            temperature = 0.7f,
            reasoning = ReasoningConfig(effort = "none"),
        )

        val offending = topLevelKeys(request) - veniceAllowedTopLevelKeys
        assertTrue(
            "These top-level keys are not in Venice's schema and would be rejected " +
                "as 'Unrecognized key(s) in object': $offending",
            offending.isEmpty(),
        )
    }

    // ── The two real request paths (SmartRouter routing + AgentLoop main) ─

    @Test
    fun `routing request (reasoning effort none, no tools) is Venice-legal`() {
        // Mirrors SmartRouter.buildRoutingRequest.
        val request = MessagesRequest(
            model = "kimi-k2-5",
            maxTokens = 150,
            system = "Route this request.",
            messages = listOf(Message.user("text mom I'm late")),
            temperature = 0f,
            reasoning = ReasoningConfig(effort = "none"),
        )
        val keys = topLevelKeys(request)
        assertTrue((keys - veniceAllowedTopLevelKeys).isEmpty())

        // reasoning must serialize as { "effort": "none" } — a valid Venice enum value.
        val obj = Json.parseToJsonElement(OpenAiFormatAdapter.toOpenAiRequestJson(request)).jsonObject
        assertEquals(
            "none",
            obj["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun `main agent request (tools + parallel_tool_calls) is Venice-legal`() {
        // Mirrors AgentLoop's main MessagesRequest.
        val request = MessagesRequest(
            model = "kimi-k2-5",
            maxTokens = 4096,
            system = "You are AndyClaw.",
            messages = listOf(Message.user("what time is it in Rome?")),
            tools = listOf(sampleTool()),
            stream = true,
            parallelToolCalls = true,
        )
        val keys = topLevelKeys(request)
        assertTrue("Unexpected keys: ${keys - veniceAllowedTopLevelKeys}", (keys - veniceAllowedTopLevelKeys).isEmpty())
        // Sanity: the keys the agent path actually needs are present.
        assertTrue(keys.containsAll(setOf("model", "max_tokens", "messages", "stream", "tools", "parallel_tool_calls")))
    }

    @Test
    fun `reasoning is omitted entirely when not set`() {
        val request = MessagesRequest(
            model = "kimi-k2-5",
            maxTokens = 256,
            messages = listOf(Message.user("hi")),
        )
        assertFalse("reasoning" in topLevelKeys(request))
    }
}
