package org.ethereumphone.andyclaw.llm

enum class AnthropicModels(
    val modelId: String,
    val maxTokens: Int,
    val provider: LlmProvider,
) {
    // OpenRouter models
    MINIMAX_M25("minimax/minimax-m2.5", 8192, LlmProvider.OPEN_ROUTER),
    KIMI_K25("moonshotai/kimi-k2.5", 8192, LlmProvider.OPEN_ROUTER),

    // Tinfoil TEE models
    TINFOIL_KIMI_K25("kimi-k2.5", 8192, LlmProvider.TINFOIL),
    TINFOIL_LLAMA3_3_70B("llama3-3-70b", 8192, LlmProvider.TINFOIL),
    TINFOIL_DEEPSEEK_R1_70B("deepseek-r1-70b", 8192, LlmProvider.TINFOIL),

    // Local models
    QWEN3_4B("qwen3-4b", 4096, LlmProvider.LOCAL);

    companion object {
        fun fromModelId(id: String): AnthropicModels? = entries.find { it.modelId == id }

        /** Return models available for the given provider. */
        fun forProvider(provider: LlmProvider): List<AnthropicModels> =
            entries.filter { it.provider == provider }

        /** Default model for a given provider. */
        fun defaultForProvider(provider: LlmProvider): AnthropicModels = when (provider) {
            LlmProvider.OPEN_ROUTER -> MINIMAX_M25
            LlmProvider.TINFOIL -> TINFOIL_KIMI_K25
            LlmProvider.LOCAL -> QWEN3_4B
        }
    }
}
