package org.ethereumphone.andyclaw.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saved model selections outlive the catalog: a device keeps whatever id the
 * user picked, and every request resolves it through [AnthropicModels.fromModelId].
 * An id that no longer resolves falls through to the MINIMAX_M3 default, which is
 * an OpenRouter id and fails outright on every other provider.
 */
class AnthropicModelsTest {

    @Test
    fun `retired ids resolve to their successors`() {
        val expected = mapOf(
            "claude-3-5-haiku-latest" to "claude-haiku-4-5",
            "claude-sonnet-4-6" to "claude-sonnet-5",
            "claude-opus-4-6-20250514" to "claude-opus-5",
            "anthropic/claude-sonnet-4.6" to "anthropic/claude-sonnet-5",
            "anthropic/claude-opus-4-6" to "anthropic/claude-opus-5",
            "qwen/qwen3.5-flash-02-23" to "qwen/qwen3.8-flash",
            "kimi-k2-5" to "kimi-k3",
            "deepseek-r1-0528" to "glm-5-3",
            "gpt-4.1-nano" to "gpt-6-luna",
            "grok-41-fast" to "grok-4-7",
            "venice-uncensored" to "venice-uncensored-1-2",
        )
        for ((old, new) in expected) {
            assertEquals(old, new, AnthropicModels.fromModelId(old)?.modelId)
        }
    }

    @Test
    fun `every provider default, router and tier model is in the catalog`() {
        for (provider in LlmProvider.entries) {
            assertNotNull(AnthropicModels.fromModelId(AnthropicModels.defaultForProvider(provider).modelId))
            AnthropicModels.routingModelForProvider(provider)?.let {
                assertNotNull(AnthropicModels.fromModelId(it.modelId))
            }
        }
    }

    @Test
    fun `ids still live elsewhere are not aliased away`() {
        // "gpt-5.4" is ChatGPT OAuth's id; aliasing it would silently re-route those users.
        assertEquals("gpt-5.4", AnthropicModels.fromModelId("gpt-5.4")?.modelId)
    }

    @Test
    fun `temperature is dropped only for models that reject it`() {
        val rejects = listOf(
            "claude-opus-5", "anthropic/claude-opus-4.7", "claude-opus-4-8", "claude-sonnet-5",
            "anthropic/claude-sonnet-5", "claude-fable-5-1", "gpt-6-luna", "gpt-5.4-nano",
            "o3", "openai-gpt-55",
        )
        val accepts = listOf(
            "claude-haiku-4-5", "claude-sonnet-4-6", "anthropic/claude-opus-4.6",
            "claude-sonnet-4-20250514", "claude-3-5-haiku-latest", "kimi-k3", "qwen/qwen3.8-flash",
            "gpt-4.1", "gpt-oss-120b", "olafangensan-glm-4.7-flash-heretic",
        )
        for (id in rejects) assertFalse(id, AnthropicModels.acceptsTemperature(id))
        for (id in accepts) assertTrue(id, AnthropicModels.acceptsTemperature(id))
    }

    @Test
    fun `OpenAI-format request omits temperature for a model that rejects it`() {
        fun keys(model: String) = Json.parseToJsonElement(
            OpenAiFormatAdapter.toOpenAiRequestJson(
                MessagesRequest(model = model, maxTokens = 256, messages = listOf(Message.user("hi")), temperature = 0f),
            ),
        ).jsonObject.keys
        assertFalse("temperature" in keys("gpt-6-luna"))
        assertTrue("temperature" in keys("kimi-k3"))
    }

    @Test
    fun `api openai com gets max_completion_tokens, other servers max_tokens`() {
        val request = MessagesRequest(model = "gpt-6-sol", maxTokens = 256, messages = listOf(Message.user("hi")))
        val openAi = Json.parseToJsonElement(OpenAiFormatAdapter.toOpenAiRequestJson(request, useMaxCompletionTokens = true)).jsonObject
        val other = Json.parseToJsonElement(OpenAiFormatAdapter.toOpenAiRequestJson(request)).jsonObject
        assertTrue("max_completion_tokens" in openAi.keys && "max_tokens" !in openAi.keys)
        assertTrue("max_tokens" in other.keys && "max_completion_tokens" !in other.keys)
    }
}
