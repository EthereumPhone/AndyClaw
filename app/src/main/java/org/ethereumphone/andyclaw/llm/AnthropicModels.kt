package org.ethereumphone.andyclaw.llm

enum class AnthropicModels(
    val modelId: String,
    val maxTokens: Int,
    val provider: LlmProvider,
    /** Total context window size in tokens (input + output). 0 = unknown. */
    val contextWindow: Int = 0,
) {
    // OpenRouter models — contextWindow from https://openrouter.ai/api/v1/models
    // Dynamically overridden by OpenRouterModelRegistry when available.
    // ethOS Premium serves these through the backend, which only serves and bills
    // the ids it lists (premiumLlmAndyHandler.js) — add a model there too.
    CLAUDE_OPUS_5("anthropic/claude-opus-5", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 1_000_000),
    CLAUDE_SONNET_5("anthropic/claude-sonnet-5", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 1_000_000),
    MINIMAX_M3("minimax/minimax-m3", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 1_048_576),
    KIMI_K3("moonshotai/kimi-k3", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 1_048_576),
    GEMINI_3_1_PRO("google/gemini-3.1-pro-preview", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 1_048_576),
    GROK_4_7("x-ai/grok-4.7", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 500_000),
    GLM_5_3("z-ai/glm-5.3", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 1_310_720),
    DEEPSEEK_V4_PRO("deepseek/deepseek-v4-pro", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 1_048_576),
    QWEN_3_7_PLUS("qwen/qwen3.7-plus", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 1_000_000),
    QWEN_3_8_FLASH("qwen/qwen3.8-flash", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 1_000_000),
    GEMMA_4_26B("google/gemma-4-26b-a4b-it", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 262_144),

    // Claude setup-token models (direct Anthropic API)
    CLAUDE_OAUTH_OPUS_5("claude-opus-5", 8192, LlmProvider.CLAUDE_OAUTH, contextWindow = 1_000_000),
    CLAUDE_OAUTH_SONNET_5("claude-sonnet-5", 8192, LlmProvider.CLAUDE_OAUTH, contextWindow = 1_000_000),
    CLAUDE_OAUTH_HAIKU_4_5("claude-haiku-4-5", 8192, LlmProvider.CLAUDE_OAUTH, contextWindow = 200_000),

    // ChatGPT OAuth (Codex backend) — uses ~/.codex/auth.json refresh token.
    // Model IDs are best-effort; the live list is discoverable at
    // chatgpt.com/backend-api/codex/models?client_version=. Edit as needed.
    CHATGPT_OAUTH_GPT_5_4("gpt-5.4", 16384, LlmProvider.OPENAI_OAUTH, contextWindow = 400_000),
    CHATGPT_OAUTH_GPT_5_4_PRO("gpt-5.4-pro", 16384, LlmProvider.OPENAI_OAUTH, contextWindow = 400_000),
    CHATGPT_OAUTH_GPT_5_3_CODEX("gpt-5.3-codex", 16384, LlmProvider.OPENAI_OAUTH, contextWindow = 400_000),
    CHATGPT_OAUTH_GPT_5_2_CODEX("gpt-5.2-codex", 16384, LlmProvider.OPENAI_OAUTH, contextWindow = 400_000),

    // Tinfoil TEE models — https://inference.tinfoil.sh/v1/models. ethOS Premium
    // bills these by id (tinfoilProxyHandler.js) — add a model there too.
    TINFOIL_KIMI_K3("kimi-k3", 8192, LlmProvider.TINFOIL, contextWindow = 262_144),
    TINFOIL_GLM_5_3("glm-5-3", 8192, LlmProvider.TINFOIL, contextWindow = 1_048_576),
    TINFOIL_GEMMA_4_31B("gemma4-31b", 8192, LlmProvider.TINFOIL, contextWindow = 262_144),
    TINFOIL_GPT_OSS_120B("gpt-oss-120b", 8192, LlmProvider.TINFOIL, contextWindow = 131_072),

    // OpenAI models — flagship/frontier
    OPENAI_GPT_6_ASTRA("gpt-6-astra", 128000, LlmProvider.OPENAI, contextWindow = 1_050_000),
    OPENAI_GPT_6_SOL("gpt-6-sol", 128000, LlmProvider.OPENAI, contextWindow = 1_050_000),
    OPENAI_GPT_6_LUNA("gpt-6-luna", 128000, LlmProvider.OPENAI, contextWindow = 1_050_000),
    OPENAI_GPT_5_5("gpt-5.5", 128000, LlmProvider.OPENAI, contextWindow = 1_050_000),
    OPENAI_GPT_5_4_MINI("gpt-5.4-mini", 128000, LlmProvider.OPENAI, contextWindow = 400_000),
    OPENAI_GPT_5_4_NANO("gpt-5.4-nano", 128000, LlmProvider.OPENAI, contextWindow = 400_000),

    // Venice AI models — https://api.venice.ai/api/v1/models
    VENICE_CLAUDE_OPUS_5("claude-opus-5", 8192, LlmProvider.VENICE),
    VENICE_CLAUDE_SONNET_5("claude-sonnet-5", 8192, LlmProvider.VENICE),
    VENICE_OPENAI_GPT_6_ASTRA("openai-gpt-6-astra", 8192, LlmProvider.VENICE),
    VENICE_OPENAI_GPT_6_SOL("openai-gpt-6-sol", 8192, LlmProvider.VENICE),
    VENICE_OPENAI_GPT_6_LUNA("openai-gpt-6-luna", 8192, LlmProvider.VENICE),
    VENICE_OPENAI_GPT_5_5("openai-gpt-55", 8192, LlmProvider.VENICE),
    VENICE_OPENAI_GPT_OSS_120B("openai-gpt-oss-120b", 8192, LlmProvider.VENICE),
    VENICE_DEEPSEEK_V4_PRO("deepseek-v4-pro", 8192, LlmProvider.VENICE),
    VENICE_DEEPSEEK_V4_1_FLASH("deepseek-v4-1-flash", 8192, LlmProvider.VENICE),
    VENICE_QWEN_3_8_MAX("qwen-3-8-max", 8192, LlmProvider.VENICE),
    VENICE_QWEN_3_8_FLASH("qwen-3-8-flash", 8192, LlmProvider.VENICE),
    VENICE_QWEN_3_8_27B("qwen-3-8-27b", 8192, LlmProvider.VENICE),
    VENICE_QWEN3_6_35B("qwen3-6-35b-a3b", 8192, LlmProvider.VENICE),
    VENICE_GLM_5_3("z-ai-glm-5-3", 8192, LlmProvider.VENICE),
    VENICE_GLM_5_3_FLASH("z-ai-glm-5-3-flash", 8192, LlmProvider.VENICE),
    VENICE_GLM_4_7_FLASH_HERETIC("olafangensan-glm-4.7-flash-heretic", 8192, LlmProvider.VENICE),
    VENICE_GEMINI_3_1_PRO("gemini-3-1-pro-preview", 8192, LlmProvider.VENICE),
    VENICE_GEMINI_3_8_FLASH("gemini-3-8-flash", 8192, LlmProvider.VENICE),
    VENICE_GROK_4_7("grok-4-7", 8192, LlmProvider.VENICE),
    VENICE_GROK_BUILD_0_1("grok-build-0-1", 8192, LlmProvider.VENICE),
    VENICE_KIMI_K3("kimi-k3", 8192, LlmProvider.VENICE),
    VENICE_MINIMAX_M27("minimax-m27", 8192, LlmProvider.VENICE),
    VENICE_MISTRAL_SMALL("mistral-small-3-2-24b-instruct", 8192, LlmProvider.VENICE),
    VENICE_GOOGLE_GEMMA_4_31B("google-gemma-4-31b-it", 8192, LlmProvider.VENICE),
    VENICE_NVIDIA_NEMOTRON("nvidia-nemotron-3-nano-30b-a3b", 8192, LlmProvider.VENICE),
    VENICE_UNCENSORED("venice-uncensored-1-2", 8192, LlmProvider.VENICE),
    VENICE_UNCENSORED_RP("venice-uncensored-role-play", 8192, LlmProvider.VENICE),

    // Local models
    QWEN2_5_1_5B("qwen2.5-1.5b-instruct", 4096, LlmProvider.LOCAL, contextWindow = 131_072);

    companion object {
        /**
         * Retired or superseded ids → their successor. A saved selection is
         * resolved through this on every request, so a device keeps working after
         * its model is dropped here, and a model a provider stopped serving is
         * replaced rather than falling through to the MINIMAX_M3 default.
         *
         * The map is provider-agnostic: never alias an id that another provider
         * still uses as-is (e.g. "gpt-5.4" is live for ChatGPT OAuth).
         */
        private val legacyModelAliases = mapOf(
            // Direct Anthropic (and Venice, which uses the same ids).
            "claude-opus-4-6-20250514" to "claude-opus-5",
            "claude-sonnet-4-6-20250514" to "claude-sonnet-5",
            "claude-opus-4-6" to "claude-opus-5",
            "claude-opus-4-5" to "claude-opus-5",
            "claude-sonnet-4-6" to "claude-sonnet-5",
            "claude-sonnet-4-5" to "claude-sonnet-5",
            "claude-3-5-haiku-latest" to "claude-haiku-4-5", // retired by Anthropic

            // OpenRouter (dotted canonical form and the older hyphenated one).
            "anthropic/claude-opus-4.6" to "anthropic/claude-opus-5",
            "anthropic/claude-sonnet-4.6" to "anthropic/claude-sonnet-5",
            "anthropic/claude-opus-4-6" to "anthropic/claude-opus-5",
            "anthropic/claude-sonnet-4-6" to "anthropic/claude-sonnet-5",
            "minimax/minimax-m2.5" to "minimax/minimax-m3",
            "moonshotai/kimi-k2.5" to "moonshotai/kimi-k3",
            "x-ai/grok-4" to "x-ai/grok-4.7",
            "z-ai/glm-5" to "z-ai/glm-5.3",
            "deepseek/deepseek-r1" to "deepseek/deepseek-v4-pro",
            "qwen/qwen3.5-plus-02-15" to "qwen/qwen3.7-plus",
            "qwen/qwen3.5-flash-02-23" to "qwen/qwen3.8-flash",
            "google/gemma-3-4b-it" to "google/gemma-4-26b-a4b-it",

            // Tinfoil. kimi-k2-5 and deepseek-r1-0528 are no longer served.
            // "kimi-k2-5" is also Venice's id and was the old default selection;
            // both providers serve "kimi-k3", so one alias covers all three.
            "kimi-k2-5" to "kimi-k3",
            "deepseek-r1-0528" to "glm-5-3",
            "llama3-3-70b" to "gemma4-31b",

            // OpenAI.
            "gpt-5" to "gpt-6-sol",
            "gpt-5-mini" to "gpt-6-luna",
            "gpt-4.1" to "gpt-6-sol",
            "gpt-4.1-mini" to "gpt-6-luna",
            "gpt-4.1-nano" to "gpt-6-luna",
            "gpt-4o" to "gpt-6-sol",
            "gpt-4o-mini" to "gpt-6-luna",
            "o3" to "gpt-6-astra",
            "o4-mini" to "gpt-6-luna",

            // Venice. Several of these are no longer served at all.
            "openai-gpt-54" to "openai-gpt-6-sol",
            "openai-gpt-54-pro" to "openai-gpt-6-astra",
            "openai-gpt-52" to "openai-gpt-6-sol",
            "openai-gpt-52-codex" to "openai-gpt-6-sol",
            "openai-gpt-53-codex" to "openai-gpt-6-sol",
            "openai-gpt-4o-2024-11-20" to "openai-gpt-6-sol",
            "openai-gpt-4o-mini-2024-07-18" to "openai-gpt-6-luna",
            "deepseek-v3.2" to "deepseek-v4-pro",
            "llama-3.3-70b" to "z-ai-glm-5-3",
            "llama-3.2-3b" to "qwen-3-8-flash",
            "hermes-3-llama-3.1-405b" to "venice-uncensored-1-2",
            "qwen3-235b-a22b-instruct-2507" to "qwen-3-8-max",
            "qwen3-235b-a22b-thinking-2507" to "qwen-3-8-max",
            "qwen3-coder-480b-a35b-instruct" to "qwen-3-8-max",
            "qwen3-coder-480b-a35b-instruct-turbo" to "qwen-3-8-max",
            "qwen3-next-80b" to "qwen-3-8-flash",
            "qwen3-5-35b-a3b" to "qwen3-6-35b-a3b",
            "qwen3-4b" to "qwen-3-8-flash",
            "qwen3-vl-235b-a22b" to "qwen-3-8-27b",
            "zai-org-glm-5" to "z-ai-glm-5-3",
            "zai-org-glm-4.7" to "z-ai-glm-5-3",
            "zai-org-glm-4.7-flash" to "z-ai-glm-5-3-flash",
            "zai-org-glm-4.6" to "z-ai-glm-5-3",
            "gemini-3-pro-preview" to "gemini-3-1-pro-preview",
            "gemini-3-flash-preview" to "gemini-3-8-flash",
            "grok-41-fast" to "grok-4-7",
            "grok-code-fast-1" to "grok-build-0-1",
            "kimi-k2-thinking" to "kimi-k3",
            "minimax-m25" to "minimax-m27",
            "minimax-m21" to "minimax-m27",
            "mistral-31-24b" to "mistral-small-3-2-24b-instruct",
            "google-gemma-3-27b-it" to "google-gemma-4-31b-it",
            "venice-uncensored" to "venice-uncensored-1-2",
        )

        /** Claude family and version in any provider's spelling ("anthropic/claude-opus-4.7", "claude-sonnet-5"). */
        private val CLAUDE_VERSION = Regex("""claude-(opus|sonnet|haiku|fable|mythos)-(\d+)(?:[.-](\d{1,2}))?(?!\d)""")

        /** OpenAI reasoning models, direct ("gpt-6-sol", "o3") or on Venice ("openai-gpt-55"). */
        private val OPENAI_REASONING = Regex("""^(openai-)?(gpt-[5-9]|o\d)""")

        /**
         * False for models that reject `temperature` with a 400: Claude Opus 4.7+,
         * Sonnet 5+, Fable and Mythos, and OpenAI's GPT-5+ and o-series. Callers
         * such as the router and the compactor ask for temperature 0; the request
         * builders drop it for these models instead of failing the call.
         */
        fun acceptsTemperature(modelId: String): Boolean {
            val id = modelId.substringAfterLast('/').lowercase()
            CLAUDE_VERSION.find(id)?.let { match ->
                val major = match.groupValues[2].toInt()
                val minor = match.groupValues[3].toIntOrNull() ?: 0
                return when (match.groupValues[1]) {
                    "fable", "mythos" -> false
                    "opus" -> major < 4 || (major == 4 && minor < 7)
                    "sonnet" -> major < 5
                    else -> true
                }
            }
            return !OPENAI_REASONING.containsMatchIn(id)
        }

        fun fromModelId(id: String): AnthropicModels? {
            val canonical = legacyModelAliases[id] ?: id
            return entries.find { it.modelId == canonical }
        }

        /** Return models available for the given provider. */
        fun forProvider(provider: LlmProvider): List<AnthropicModels> = when (provider) {
            LlmProvider.ETHOS_PREMIUM -> entries.filter {
                it.provider == LlmProvider.TINFOIL || it.provider == LlmProvider.OPEN_ROUTER
            }
            else -> entries.filter { it.provider == provider }
        }

        /** Cheap/fast model for skill routing classification. Null = skip LLM routing. */
        fun routingModelForProvider(provider: LlmProvider): AnthropicModels? = when (provider) {
            LlmProvider.ETHOS_PREMIUM -> QWEN_3_8_FLASH
            LlmProvider.OPEN_ROUTER -> QWEN_3_8_FLASH
            LlmProvider.CLAUDE_OAUTH -> CLAUDE_OAUTH_HAIKU_4_5
            LlmProvider.TINFOIL -> TINFOIL_GEMMA_4_31B
            LlmProvider.OPENAI -> OPENAI_GPT_6_LUNA
            LlmProvider.OPENAI_OAUTH -> null // Codex models aren't designed as fast routers; fall back to heuristic.
            LlmProvider.VENICE -> VENICE_QWEN_3_8_FLASH
            LlmProvider.LOCAL -> null
            LlmProvider.CUSTOM -> null // We don't know what the user's backend has; skip LLM routing for CUSTOM.
        }

        /** Default model for a given provider. */
        fun defaultForProvider(provider: LlmProvider): AnthropicModels = when (provider) {
            LlmProvider.ETHOS_PREMIUM -> CLAUDE_SONNET_5
            LlmProvider.OPEN_ROUTER -> CLAUDE_SONNET_5
            LlmProvider.CLAUDE_OAUTH -> CLAUDE_OAUTH_SONNET_5
            LlmProvider.TINFOIL -> TINFOIL_KIMI_K3
            LlmProvider.OPENAI -> OPENAI_GPT_6_SOL
            LlmProvider.OPENAI_OAUTH -> CHATGPT_OAUTH_GPT_5_4
            LlmProvider.VENICE -> VENICE_GLM_5_3
            LlmProvider.LOCAL -> QWEN2_5_1_5B
            // CUSTOM has no built-in "default" model — the user supplies the id in Settings.
            // We pick QWEN2_5_1_5B here as a harmless placeholder; getLlmClientForProvider
            // for CUSTOM ignores this and uses securePrefs.customModelId / selectedModel.
            LlmProvider.CUSTOM -> QWEN2_5_1_5B
        }

        /**
         * Model for a given difficulty tier and provider.
         *
         * For [LlmProvider.OPEN_ROUTER] and [LlmProvider.ETHOS_PREMIUM], returns
         * null — those providers use [OpenRouterModelRegistry] for dynamic selection.
         * For other providers with fixed model sets, returns a static mapping.
         */
        fun forTier(tier: ModelTier, provider: LlmProvider): AnthropicModels? = when (provider) {
            // Dynamic providers — handled by OpenRouterModelRegistry
            LlmProvider.OPEN_ROUTER, LlmProvider.ETHOS_PREMIUM -> null

            LlmProvider.CLAUDE_OAUTH -> when (tier) {
                ModelTier.LIGHT -> CLAUDE_OAUTH_HAIKU_4_5
                ModelTier.STANDARD -> CLAUDE_OAUTH_SONNET_5
                ModelTier.POWERFUL -> CLAUDE_OAUTH_OPUS_5
            }
            LlmProvider.TINFOIL -> when (tier) {
                ModelTier.LIGHT -> TINFOIL_GEMMA_4_31B
                ModelTier.STANDARD -> TINFOIL_KIMI_K3
                ModelTier.POWERFUL -> TINFOIL_GLM_5_3
            }
            LlmProvider.OPENAI -> when (tier) {
                ModelTier.LIGHT -> OPENAI_GPT_6_LUNA
                ModelTier.STANDARD -> OPENAI_GPT_6_SOL
                ModelTier.POWERFUL -> OPENAI_GPT_6_ASTRA
            }
            LlmProvider.OPENAI_OAUTH -> when (tier) {
                ModelTier.LIGHT -> CHATGPT_OAUTH_GPT_5_2_CODEX
                ModelTier.STANDARD -> CHATGPT_OAUTH_GPT_5_3_CODEX
                ModelTier.POWERFUL -> CHATGPT_OAUTH_GPT_5_4
            }
            // CUSTOM uses the user's single configured model id regardless of tier.
            LlmProvider.CUSTOM -> null
            LlmProvider.VENICE -> when (tier) {
                ModelTier.LIGHT -> VENICE_QWEN3_6_35B
                ModelTier.STANDARD -> VENICE_CLAUDE_SONNET_5
                ModelTier.POWERFUL -> VENICE_CLAUDE_OPUS_5
            }
            LlmProvider.LOCAL -> QWEN2_5_1_5B // Only one local model
        }
    }
}
