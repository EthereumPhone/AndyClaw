package org.ethereumphone.andyclaw.llm

enum class AnthropicModels(
    val modelId: String,
    val maxTokens: Int,
    val provider: LlmProvider,
) {
    // OpenRouter models
    CLAUDE_OPUS_4_6("anthropic/claude-opus-4-6", 8192, LlmProvider.OPEN_ROUTER),
    CLAUDE_SONNET_4_6("anthropic/claude-sonnet-4-6", 8192, LlmProvider.OPEN_ROUTER),
    MINIMAX_M25("minimax/minimax-m2.5", 8192, LlmProvider.OPEN_ROUTER),
    KIMI_K25("moonshotai/kimi-k2.5", 8192, LlmProvider.OPEN_ROUTER),

    // Tinfoil TEE models
    TINFOIL_KIMI_K25("kimi-k2-5", 8192, LlmProvider.TINFOIL),
    TINFOIL_LLAMA3_3_70B("llama3-3-70b", 8192, LlmProvider.TINFOIL),
    TINFOIL_DEEPSEEK_R1("deepseek-r1-0528", 8192, LlmProvider.TINFOIL),
    TINFOIL_GPT_OSS_120B("gpt-oss-120b", 8192, LlmProvider.TINFOIL),

    // Local models
    QWEN2_5_1_5B("qwen2.5-1.5b-instruct", 4096, LlmProvider.LOCAL);

    companion object {
        fun fromModelId(id: String): AnthropicModels? = entries.find { it.modelId == id }

        /** Return models available for the given provider. */
        fun forProvider(provider: LlmProvider): List<AnthropicModels> = when (provider) {
            LlmProvider.ETHOS_PREMIUM -> entries.filter {
                it.provider == LlmProvider.TINFOIL || it.provider == LlmProvider.OPEN_ROUTER
            }
            else -> entries.filter { it.provider == provider }
        }

        /** Default model for a given provider. */
        fun defaultForProvider(provider: LlmProvider): AnthropicModels = when (provider) {
            LlmProvider.ETHOS_PREMIUM -> TINFOIL_KIMI_K25
            LlmProvider.OPEN_ROUTER -> CLAUDE_SONNET_4_6
            LlmProvider.TINFOIL -> TINFOIL_KIMI_K25
            LlmProvider.LOCAL -> QWEN2_5_1_5B
        }
    }
}
