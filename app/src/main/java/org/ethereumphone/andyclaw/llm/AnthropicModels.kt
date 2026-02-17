package org.ethereumphone.andyclaw.llm

enum class AnthropicModels(val modelId: String, val maxTokens: Int) {
    MINIMAX_M25("minimax/minimax-m2.5", 8192),
    KIMI_K25("moonshotai/kimi-k2.5", 8192);

    companion object {
        fun fromModelId(id: String): AnthropicModels? = entries.find { it.modelId == id }
    }
}
