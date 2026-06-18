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
    CLAUDE_OPUS_4_6("anthropic/claude-opus-4.6", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 1_000_000),
    CLAUDE_SONNET_4_6("anthropic/claude-sonnet-4.6", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 1_000_000),
    MINIMAX_M25("minimax/minimax-m2.5", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 196_600),
    KIMI_K25("moonshotai/kimi-k2.5", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 262_144),
    GEMINI_3_1_PRO("google/gemini-3.1-pro-preview", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 1_048_576),
    GROK_4("x-ai/grok-4", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 256_000),
    GLM_5("z-ai/glm-5", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 80_000),
    DEEPSEEK_R1("deepseek/deepseek-r1", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 64_000),
    QWEN_3_5_PLUS("qwen/qwen3.5-plus-02-15", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 1_000_000),
    QWEN_3_5_FLASH("qwen/qwen3.5-flash-02-23", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 1_000_000),
    GEMMA_3_4B_IT("google/gemma-3-4b-it", 8192, LlmProvider.OPEN_ROUTER, contextWindow = 131_072),

    // Claude setup-token models (direct Anthropic API)
    CLAUDE_OAUTH_OPUS_4_6("claude-opus-4-6", 8192, LlmProvider.CLAUDE_OAUTH, contextWindow = 1_000_000),
    CLAUDE_OAUTH_SONNET_4_6("claude-sonnet-4-6", 8192, LlmProvider.CLAUDE_OAUTH, contextWindow = 1_000_000),
    CLAUDE_OAUTH_HAIKU_3_5("claude-3-5-haiku-latest", 8192, LlmProvider.CLAUDE_OAUTH, contextWindow = 200_000),

    // ChatGPT OAuth (Codex backend) — uses ~/.codex/auth.json refresh token.
    // Model IDs are best-effort; the live list is discoverable at
    // chatgpt.com/backend-api/codex/models?client_version=. Edit as needed.
    CHATGPT_OAUTH_GPT_5_4("gpt-5.4", 16384, LlmProvider.OPENAI_OAUTH, contextWindow = 400_000),
    CHATGPT_OAUTH_GPT_5_4_PRO("gpt-5.4-pro", 16384, LlmProvider.OPENAI_OAUTH, contextWindow = 400_000),
    CHATGPT_OAUTH_GPT_5_3_CODEX("gpt-5.3-codex", 16384, LlmProvider.OPENAI_OAUTH, contextWindow = 400_000),
    CHATGPT_OAUTH_GPT_5_2_CODEX("gpt-5.2-codex", 16384, LlmProvider.OPENAI_OAUTH, contextWindow = 400_000),

    // Tinfoil TEE models
    TINFOIL_KIMI_K25("kimi-k2-5", 8192, LlmProvider.TINFOIL, contextWindow = 262_144),
    TINFOIL_LLAMA3_3_70B("llama3-3-70b", 8192, LlmProvider.TINFOIL, contextWindow = 131_072),
    TINFOIL_DEEPSEEK_R1("deepseek-r1-0528", 8192, LlmProvider.TINFOIL, contextWindow = 131_072),
    TINFOIL_GPT_OSS_120B("gpt-oss-120b", 8192, LlmProvider.TINFOIL, contextWindow = 131_072),

    // OpenAI models — flagship/frontier
    OPENAI_GPT_5_4("gpt-5.4", 128000, LlmProvider.OPENAI, contextWindow = 1_050_000),
    OPENAI_GPT_5("gpt-5", 128000, LlmProvider.OPENAI, contextWindow = 128_000),
    OPENAI_GPT_5_MINI("gpt-5-mini", 128000, LlmProvider.OPENAI, contextWindow = 128_000),
    OPENAI_GPT_4_1("gpt-4.1", 32768, LlmProvider.OPENAI, contextWindow = 1_000_000),
    OPENAI_GPT_4_1_MINI("gpt-4.1-mini", 32768, LlmProvider.OPENAI, contextWindow = 1_000_000),
    OPENAI_GPT_4_1_NANO("gpt-4.1-nano", 32768, LlmProvider.OPENAI, contextWindow = 1_000_000),
    OPENAI_GPT_4O("gpt-4o", 16384, LlmProvider.OPENAI, contextWindow = 128_000),
    OPENAI_GPT_4O_MINI("gpt-4o-mini", 16384, LlmProvider.OPENAI, contextWindow = 128_000),
    OPENAI_O3("o3", 100000, LlmProvider.OPENAI, contextWindow = 200_000),
    OPENAI_O4_MINI("o4-mini", 100000, LlmProvider.OPENAI, contextWindow = 200_000),

    // Venice AI models
    VENICE_CLAUDE_OPUS_4_6("claude-opus-4-6", 8192, LlmProvider.VENICE),
    VENICE_CLAUDE_OPUS_4_5("claude-opus-4-5", 8192, LlmProvider.VENICE),
    VENICE_CLAUDE_SONNET_4_6("claude-sonnet-4-6", 8192, LlmProvider.VENICE),
    VENICE_CLAUDE_SONNET_4_5("claude-sonnet-4-5", 8192, LlmProvider.VENICE),
    VENICE_OPENAI_GPT_5_4("openai-gpt-54", 8192, LlmProvider.VENICE),
    VENICE_OPENAI_GPT_5_4_PRO("openai-gpt-54-pro", 8192, LlmProvider.VENICE),
    VENICE_OPENAI_GPT_5_2("openai-gpt-52", 8192, LlmProvider.VENICE),
    VENICE_OPENAI_GPT_5_2_CODEX("openai-gpt-52-codex", 8192, LlmProvider.VENICE),
    VENICE_OPENAI_GPT_5_3_CODEX("openai-gpt-53-codex", 8192, LlmProvider.VENICE),
    VENICE_OPENAI_GPT_4O("openai-gpt-4o-2024-11-20", 8192, LlmProvider.VENICE),
    VENICE_OPENAI_GPT_4O_MINI("openai-gpt-4o-mini-2024-07-18", 8192, LlmProvider.VENICE),
    VENICE_OPENAI_GPT_OSS_120B("openai-gpt-oss-120b", 8192, LlmProvider.VENICE),
    VENICE_DEEPSEEK_V3_2("deepseek-v3.2", 8192, LlmProvider.VENICE),
    VENICE_LLAMA_3_3_70B("llama-3.3-70b", 8192, LlmProvider.VENICE),
    VENICE_LLAMA_3_2_3B("llama-3.2-3b", 8192, LlmProvider.VENICE),
    VENICE_HERMES_3_405B("hermes-3-llama-3.1-405b", 8192, LlmProvider.VENICE),
    VENICE_QWEN3_235B_INSTRUCT("qwen3-235b-a22b-instruct-2507", 8192, LlmProvider.VENICE),
    VENICE_QWEN3_235B_THINKING("qwen3-235b-a22b-thinking-2507", 8192, LlmProvider.VENICE),
    VENICE_QWEN3_CODER("qwen3-coder-480b-a35b-instruct", 8192, LlmProvider.VENICE),
    VENICE_QWEN3_CODER_TURBO("qwen3-coder-480b-a35b-instruct-turbo", 8192, LlmProvider.VENICE),
    VENICE_QWEN3_NEXT_80B("qwen3-next-80b", 8192, LlmProvider.VENICE),
    VENICE_QWEN3_5_35B("qwen3-5-35b-a3b", 8192, LlmProvider.VENICE),
    VENICE_QWEN3_4B("qwen3-4b", 8192, LlmProvider.VENICE),
    VENICE_QWEN3_VL_235B("qwen3-vl-235b-a22b", 8192, LlmProvider.VENICE),
    VENICE_GLM_5("zai-org-glm-5", 8192, LlmProvider.VENICE),
    VENICE_GLM_4_7("zai-org-glm-4.7", 8192, LlmProvider.VENICE),
    VENICE_GLM_4_7_FLASH("zai-org-glm-4.7-flash", 8192, LlmProvider.VENICE),
    VENICE_GLM_4_7_FLASH_HERETIC("olafangensan-glm-4.7-flash-heretic", 8192, LlmProvider.VENICE),
    VENICE_GLM_4_6("zai-org-glm-4.6", 8192, LlmProvider.VENICE),
    VENICE_GEMINI_3_1_PRO("gemini-3-1-pro-preview", 8192, LlmProvider.VENICE),
    VENICE_GEMINI_3_PRO("gemini-3-pro-preview", 8192, LlmProvider.VENICE),
    VENICE_GEMINI_3_FLASH("gemini-3-flash-preview", 8192, LlmProvider.VENICE),
    VENICE_GROK_4_1_FAST("grok-41-fast", 8192, LlmProvider.VENICE),
    VENICE_GROK_CODE_FAST_1("grok-code-fast-1", 8192, LlmProvider.VENICE),
    VENICE_KIMI_K2_THINKING("kimi-k2-thinking", 8192, LlmProvider.VENICE),
    VENICE_KIMI_K25("kimi-k2-5", 8192, LlmProvider.VENICE),
    VENICE_MINIMAX_M25("minimax-m25", 8192, LlmProvider.VENICE),
    VENICE_MINIMAX_M21("minimax-m21", 8192, LlmProvider.VENICE),
    VENICE_MISTRAL_SMALL("mistral-small-3-2-24b-instruct", 8192, LlmProvider.VENICE),
    VENICE_MISTRAL_31("mistral-31-24b", 8192, LlmProvider.VENICE),
    VENICE_GOOGLE_GEMMA_3_27B("google-gemma-3-27b-it", 8192, LlmProvider.VENICE),
    VENICE_NVIDIA_NEMOTRON("nvidia-nemotron-3-nano-30b-a3b", 8192, LlmProvider.VENICE),
    VENICE_UNCENSORED("venice-uncensored", 8192, LlmProvider.VENICE),
    VENICE_UNCENSORED_RP("venice-uncensored-role-play", 8192, LlmProvider.VENICE),

    // Local models
    QWEN2_5_1_5B("qwen2.5-1.5b-instruct", 4096, LlmProvider.LOCAL, contextWindow = 131_072);

    companion object {
        private val legacyModelAliases = mapOf(
            // Backward compatibility for previously saved dated Anthropic IDs.
            "claude-opus-4-6-20250514" to "claude-opus-4-6",
            "claude-sonnet-4-6-20250514" to "claude-sonnet-4-6",
            // Backward compatibility: hyphenated → dotted OpenRouter IDs.
            "anthropic/claude-opus-4-6" to "anthropic/claude-opus-4.6",
            "anthropic/claude-sonnet-4-6" to "anthropic/claude-sonnet-4.6",
        )

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
            LlmProvider.ETHOS_PREMIUM -> QWEN_3_5_FLASH
            LlmProvider.OPEN_ROUTER -> QWEN_3_5_FLASH
            LlmProvider.CLAUDE_OAUTH -> CLAUDE_OAUTH_HAIKU_3_5
            LlmProvider.TINFOIL -> TINFOIL_LLAMA3_3_70B
            LlmProvider.OPENAI -> OPENAI_GPT_4_1_NANO
            LlmProvider.OPENAI_OAUTH -> null // Codex models aren't designed as fast routers; fall back to heuristic.
            LlmProvider.VENICE -> VENICE_LLAMA_3_2_3B
            LlmProvider.LOCAL -> null
            LlmProvider.CUSTOM -> null // We don't know what the user's backend has; skip LLM routing for CUSTOM.
        }

        /** Default model for a given provider. */
        fun defaultForProvider(provider: LlmProvider): AnthropicModels = when (provider) {
            LlmProvider.ETHOS_PREMIUM -> CLAUDE_SONNET_4_6
            LlmProvider.OPEN_ROUTER -> CLAUDE_SONNET_4_6
            LlmProvider.CLAUDE_OAUTH -> CLAUDE_OAUTH_SONNET_4_6
            LlmProvider.TINFOIL -> TINFOIL_KIMI_K25
            LlmProvider.OPENAI -> OPENAI_GPT_4_1
            LlmProvider.OPENAI_OAUTH -> CHATGPT_OAUTH_GPT_5_4
            LlmProvider.VENICE -> VENICE_LLAMA_3_3_70B
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
                ModelTier.LIGHT -> CLAUDE_OAUTH_HAIKU_3_5
                ModelTier.STANDARD -> CLAUDE_OAUTH_SONNET_4_6
                ModelTier.POWERFUL -> CLAUDE_OAUTH_OPUS_4_6
            }
            LlmProvider.TINFOIL -> when (tier) {
                ModelTier.LIGHT -> TINFOIL_LLAMA3_3_70B
                ModelTier.STANDARD -> TINFOIL_KIMI_K25
                ModelTier.POWERFUL -> TINFOIL_DEEPSEEK_R1
            }
            LlmProvider.OPENAI -> when (tier) {
                ModelTier.LIGHT -> OPENAI_GPT_4_1_NANO
                ModelTier.STANDARD -> OPENAI_GPT_4_1
                ModelTier.POWERFUL -> OPENAI_GPT_5
            }
            LlmProvider.OPENAI_OAUTH -> when (tier) {
                ModelTier.LIGHT -> CHATGPT_OAUTH_GPT_5_2_CODEX
                ModelTier.STANDARD -> CHATGPT_OAUTH_GPT_5_3_CODEX
                ModelTier.POWERFUL -> CHATGPT_OAUTH_GPT_5_4
            }
            // CUSTOM uses the user's single configured model id regardless of tier.
            LlmProvider.CUSTOM -> null
            LlmProvider.VENICE -> when (tier) {
                ModelTier.LIGHT -> VENICE_QWEN3_5_35B
                ModelTier.STANDARD -> VENICE_CLAUDE_SONNET_4_6
                ModelTier.POWERFUL -> VENICE_CLAUDE_OPUS_4_6
            }
            LlmProvider.LOCAL -> QWEN2_5_1_5B // Only one local model
        }
    }
}
