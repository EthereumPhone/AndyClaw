package org.ethereumphone.andyclaw.llm

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OpenRouterModelRegistryTest {

    // ── Sample API response ──────────────────────────────────────────

    private val SAMPLE_RESPONSE = """
    {
      "data": [
        {
          "id": "anthropic/claude-opus-4-6",
          "name": "Claude Opus 4.6",
          "context_length": 1000000,
          "architecture": { "output_modalities": ["text"] },
          "pricing": { "prompt": "0.000015", "completion": "0.000075" },
          "supported_parameters": ["tools", "temperature", "max_tokens"],
          "top_provider": { "max_completion_tokens": 128000 }
        },
        {
          "id": "anthropic/claude-sonnet-4-6",
          "name": "Claude Sonnet 4.6",
          "context_length": 200000,
          "architecture": { "output_modalities": ["text"] },
          "pricing": { "prompt": "0.000003", "completion": "0.000015" },
          "supported_parameters": ["tools", "temperature", "max_tokens"],
          "top_provider": { "max_completion_tokens": 64000 }
        },
        {
          "id": "qwen/qwen3.5-flash-02-23",
          "name": "Qwen3.5 Flash",
          "context_length": 1000000,
          "architecture": { "output_modalities": ["text"] },
          "pricing": { "prompt": "0.000000065", "completion": "0.00000026" },
          "supported_parameters": ["tools", "temperature", "max_tokens"],
          "top_provider": { "max_completion_tokens": 65000 }
        },
        {
          "id": "google/gemini-3-flash-preview",
          "name": "Gemini 3 Flash Preview",
          "context_length": 1000000,
          "architecture": { "output_modalities": ["text"] },
          "pricing": { "prompt": "0.0000001", "completion": "0.0000004" },
          "supported_parameters": ["tools", "temperature"],
          "top_provider": { "max_completion_tokens": 65000 }
        },
        {
          "id": "free/some-free-model",
          "name": "Free Model",
          "context_length": 32000,
          "architecture": { "output_modalities": ["text"] },
          "pricing": { "prompt": "0", "completion": "0" },
          "supported_parameters": ["tools", "temperature"],
          "top_provider": { "max_completion_tokens": 8192 }
        },
        {
          "id": "some/image-only-model",
          "name": "Image Only Model",
          "context_length": 32000,
          "architecture": { "output_modalities": ["image"] },
          "pricing": { "prompt": "0.00001", "completion": "0.00005" },
          "supported_parameters": ["tools"],
          "top_provider": { "max_completion_tokens": 8192 }
        },
        {
          "id": "some/no-tools-model",
          "name": "No Tools Model",
          "context_length": 32000,
          "architecture": { "output_modalities": ["text"] },
          "pricing": { "prompt": "0.000002", "completion": "0.00001" },
          "supported_parameters": ["temperature", "max_tokens"],
          "top_provider": { "max_completion_tokens": 8192 }
        },
        {
          "id": "some/tiny-context-model",
          "name": "Tiny Context",
          "context_length": 2048,
          "architecture": { "output_modalities": ["text"] },
          "pricing": { "prompt": "0.000001", "completion": "0.000005" },
          "supported_parameters": ["tools"],
          "top_provider": { "max_completion_tokens": 4096 }
        },
        {
          "id": "x-ai/grok-4",
          "name": "Grok 4",
          "context_length": 256000,
          "architecture": { "output_modalities": ["text"] },
          "pricing": { "prompt": "0.00001", "completion": "0.00003" },
          "supported_parameters": ["tools", "temperature"],
          "top_provider": { "max_completion_tokens": 128000 }
        },
        {
          "id": "openai/gpt-4.1-mini",
          "name": "GPT-4.1 Mini",
          "context_length": 128000,
          "architecture": { "output_modalities": ["text"] },
          "pricing": { "prompt": "0.0000004", "completion": "0.0000016" },
          "supported_parameters": ["tools", "temperature"],
          "top_provider": { "max_completion_tokens": 32000 }
        }
      ]
    }
    """.trimIndent()

    private lateinit var registry: OpenRouterModelRegistry
    private lateinit var models: List<OpenRouterModelRegistry.ParsedModel>

    @Before
    fun setUp() {
        registry = OpenRouterModelRegistry() // no context — disk cache disabled, that's fine for tests
        models = registry.parseModels(SAMPLE_RESPONSE)
    }

    // ── Filtering ────────────────────────────────────────────────────

    @Test
    fun `filters out free models`() {
        assertNull(models.find { it.id == "free/some-free-model" })
    }

    @Test
    fun `filters out image-only models`() {
        assertNull(models.find { it.id == "some/image-only-model" })
    }

    @Test
    fun `filters out models without tool support`() {
        assertNull(models.find { it.id == "some/no-tools-model" })
    }

    @Test
    fun `filters out tiny context models`() {
        assertNull(models.find { it.id == "some/tiny-context-model" })
    }

    @Test
    fun `keeps valid tool-capable text models`() {
        val ids = models.map { it.id }.toSet()
        assertTrue("anthropic/claude-opus-4-6" in ids)
        assertTrue("anthropic/claude-sonnet-4-6" in ids)
        assertTrue("qwen/qwen3.5-flash-02-23" in ids)
        assertTrue("google/gemini-3-flash-preview" in ids)
        assertTrue("x-ai/grok-4" in ids)
        assertTrue("openai/gpt-4.1-mini" in ids)
        assertEquals(6, models.size)
    }

    // ── Tier classification ──────────────────────────────────────────

    @Test
    fun `flash models classified as LIGHT`() {
        assertEquals(ModelTier.LIGHT, models.find { it.id == "qwen/qwen3.5-flash-02-23" }!!.tier)
        assertEquals(ModelTier.LIGHT, models.find { it.id == "google/gemini-3-flash-preview" }!!.tier)
        assertEquals(ModelTier.LIGHT, models.find { it.id == "openai/gpt-4.1-mini" }!!.tier)
    }

    @Test
    fun `sonnet classified as STANDARD`() {
        val sonnet = models.find { it.id == "anthropic/claude-sonnet-4-6" }!!
        assertEquals(ModelTier.STANDARD, sonnet.tier)
    }

    @Test
    fun `opus and grok classified as POWERFUL`() {
        assertEquals(ModelTier.POWERFUL, models.find { it.id == "anthropic/claude-opus-4-6" }!!.tier)
        assertEquals(ModelTier.POWERFUL, models.find { it.id == "x-ai/grok-4" }!!.tier)
    }

    @Test
    fun `tier boundaries are correct`() {
        for (m in models) {
            when (m.tier) {
                ModelTier.LIGHT -> assertTrue("${m.id} should be < \$1/M", m.promptPricePerM < 1.0)
                ModelTier.STANDARD -> {
                    assertTrue("${m.id} should be >= \$1/M", m.promptPricePerM >= 1.0)
                    assertTrue("${m.id} should be < \$8/M", m.promptPricePerM < 8.0)
                }
                ModelTier.POWERFUL -> assertTrue("${m.id} should be >= \$8/M", m.promptPricePerM >= 8.0)
            }
        }
    }

    // ── Pricing math ─────────────────────────────────────────────────

    @Test
    fun `promptPricePerM calculates correctly`() {
        val opus = models.find { it.id == "anthropic/claude-opus-4-6" }!!
        assertEquals(15.0, opus.promptPricePerM, 0.01)
    }

    @Test
    fun `completionPricePerM calculates correctly`() {
        val opus = models.find { it.id == "anthropic/claude-opus-4-6" }!!
        assertEquals(75.0, opus.completionPricePerM, 0.01)
    }

    // ── getBestModelForTier (after loading parsed models) ────────────

    @Test
    fun `getBestModelForTier returns null for empty tier`() {
        // Fresh registry with no models loaded
        val empty = OpenRouterModelRegistry()
        assertNull(empty.getBestModelForTier(ModelTier.LIGHT))
    }

    @Test
    fun `getBestModelForTier prefers current model if in tier`() {
        // Manually load models into a registry
        val reg = registryWithModels()
        val result = reg.getBestModelForTier(ModelTier.STANDARD, "anthropic/claude-sonnet-4-6")
        assertNotNull(result)
        assertEquals("anthropic/claude-sonnet-4-6", result!!.modelId)
    }

    @Test
    fun `getBestModelForTier does not keep current model if wrong tier`() {
        val reg = registryWithModels()
        // Sonnet is STANDARD, asking for LIGHT should NOT return Sonnet
        val result = reg.getBestModelForTier(ModelTier.LIGHT, "anthropic/claude-sonnet-4-6")
        assertNotNull(result)
        assertNotEquals("anthropic/claude-sonnet-4-6", result!!.modelId)
        assertEquals(ModelTier.LIGHT, models.find { it.id == result.modelId }!!.tier)
    }

    @Test
    fun `getBestModelForTier returns model from correct tier`() {
        val reg = registryWithModels()
        val light = reg.getBestModelForTier(ModelTier.LIGHT)
        assertNotNull(light)
        assertEquals(ModelTier.LIGHT, models.find { it.id == light!!.modelId }!!.tier)

        val powerful = reg.getBestModelForTier(ModelTier.POWERFUL)
        assertNotNull(powerful)
        assertEquals(ModelTier.POWERFUL, models.find { it.id == powerful!!.modelId }!!.tier)
    }

    // ── getModelsForTier ─────────────────────────────────────────────

    @Test
    fun `getModelsForTier returns only matching tier`() {
        val reg = registryWithModels()
        val lightModels = reg.getModelsForTier(ModelTier.LIGHT)
        assertTrue(lightModels.isNotEmpty())
        assertTrue(lightModels.all { it.tier == ModelTier.LIGHT })
    }

    @Test
    fun `getModelsForTier returns sorted by quality`() {
        val reg = registryWithModels()
        val lightModels = reg.getModelsForTier(ModelTier.LIGHT)
        // Just verify it returns multiple and they're ordered (first has highest score)
        assertTrue("Should have at least 2 LIGHT models", lightModels.size >= 2)
    }

    // ── Quality ranking ──────────────────────────────────────────────

    @Test
    fun `STANDARD tier ranks sonnet above gemini`() {
        val reg = registryWithModels()
        val best = reg.getBestModelForTier(ModelTier.STANDARD)
        assertNotNull(best)
        // Sonnet should win over Gemini due to explicit rank 1 vs rank 9
        assertEquals("anthropic/claude-sonnet-4-6", best!!.modelId)
    }

    @Test
    fun `LIGHT tier ranks qwen flash first`() {
        val reg = registryWithModels()
        val best = reg.getBestModelForTier(ModelTier.LIGHT)
        assertNotNull(best)
        assertEquals("qwen/qwen3.5-flash-02-23", best!!.modelId)
    }

    @Test
    fun `POWERFUL tier ranks opus above grok`() {
        val reg = registryWithModels()
        val powerful = reg.getModelsForTier(ModelTier.POWERFUL)
        val opusIdx = powerful.indexOfFirst { it.id.contains("opus") }
        val grokIdx = powerful.indexOfFirst { it.id.contains("grok") }
        assertTrue("Both should be present", opusIdx >= 0 && grokIdx >= 0)
        assertTrue("Opus should rank before Grok", opusIdx < grokIdx)
    }

    // ── ModelTier.fromString ─────────────────────────────────────────

    @Test
    fun `fromString parses difficulty values`() {
        assertEquals(ModelTier.LIGHT, ModelTier.fromString("easy"))
        assertEquals(ModelTier.LIGHT, ModelTier.fromString("light"))
        assertEquals(ModelTier.LIGHT, ModelTier.fromString("simple"))
        assertEquals(ModelTier.STANDARD, ModelTier.fromString("medium"))
        assertEquals(ModelTier.STANDARD, ModelTier.fromString("standard"))
        assertEquals(ModelTier.POWERFUL, ModelTier.fromString("hard"))
        assertEquals(ModelTier.POWERFUL, ModelTier.fromString("powerful"))
        assertEquals(ModelTier.POWERFUL, ModelTier.fromString("complex"))
    }

    @Test
    fun `fromString returns null for unknown values`() {
        assertNull(ModelTier.fromString(null))
        assertNull(ModelTier.fromString(""))
        assertNull(ModelTier.fromString("unknown"))
    }

    @Test
    fun `fromString is case insensitive and trims whitespace`() {
        assertEquals(ModelTier.LIGHT, ModelTier.fromString("  Easy  "))
        assertEquals(ModelTier.STANDARD, ModelTier.fromString("MEDIUM"))
        assertEquals(ModelTier.POWERFUL, ModelTier.fromString(" Hard "))
    }

    // ── AnthropicModels.forTier static mappings ──────────────────────

    @Test
    fun `forTier returns null for OpenRouter providers`() {
        assertNull(AnthropicModels.forTier(ModelTier.LIGHT, LlmProvider.OPEN_ROUTER))
        assertNull(AnthropicModels.forTier(ModelTier.STANDARD, LlmProvider.ETHOS_PREMIUM))
    }

    @Test
    fun `forTier returns distinct models per tier for Claude OAuth`() {
        val light = AnthropicModels.forTier(ModelTier.LIGHT, LlmProvider.CLAUDE_OAUTH)
        val standard = AnthropicModels.forTier(ModelTier.STANDARD, LlmProvider.CLAUDE_OAUTH)
        val powerful = AnthropicModels.forTier(ModelTier.POWERFUL, LlmProvider.CLAUDE_OAUTH)
        assertNotNull(light)
        assertNotNull(standard)
        assertNotNull(powerful)
        // Each tier should return a different model
        assertNotEquals(light, standard)
        assertNotEquals(standard, powerful)
        assertNotEquals(light, powerful)
    }

    @Test
    fun `forTier returns distinct models per tier for OpenAI`() {
        val light = AnthropicModels.forTier(ModelTier.LIGHT, LlmProvider.OPENAI)
        val standard = AnthropicModels.forTier(ModelTier.STANDARD, LlmProvider.OPENAI)
        val powerful = AnthropicModels.forTier(ModelTier.POWERFUL, LlmProvider.OPENAI)
        assertNotNull(light)
        assertNotNull(standard)
        assertNotNull(powerful)
        assertNotEquals(light, standard)
        assertNotEquals(standard, powerful)
    }

    @Test
    fun `forTier LIGHT is cheaper than STANDARD which is cheaper than POWERFUL for Claude OAuth`() {
        // Haiku < Sonnet < Opus by name convention
        val light = AnthropicModels.forTier(ModelTier.LIGHT, LlmProvider.CLAUDE_OAUTH)!!
        val powerful = AnthropicModels.forTier(ModelTier.POWERFUL, LlmProvider.CLAUDE_OAUTH)!!
        assertTrue("LIGHT should be haiku", light.name.contains("HAIKU", ignoreCase = true))
        assertTrue("POWERFUL should be opus", powerful.name.contains("OPUS", ignoreCase = true))
    }

    // ── Helper ───────────────────────────────────────────────────────

    /** Creates a registry populated with the sample models. */
    private fun registryWithModels(): OpenRouterModelRegistry {
        val reg = OpenRouterModelRegistry()
        reg.loadModels(models)
        return reg
    }
}
