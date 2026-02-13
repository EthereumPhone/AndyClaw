package org.ethereumphone.andyclaw.llm

enum class AnthropicModels(val modelId: String, val maxTokens: Int) {
    SONNET_4("claude-sonnet-4-20250514", 8192),
    HAIKU_35("claude-haiku-4-5-20251001", 8192),
    OPUS_4("claude-opus-4-20250514", 8192);

    companion object {
        fun fromModelId(id: String): AnthropicModels? = entries.find { it.modelId == id }
    }
}
