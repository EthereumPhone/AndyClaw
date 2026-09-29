package org.ethereumphone.andyclaw.llm

/**
 * The model id a request should carry when it is not one of [AnthropicModels], or null.
 *
 * `AnthropicModels.fromModelId` falls back to MiniMax M3 for an id it does not know, which is right
 * for a stale saved selection and wrong for two providers whose model ids are the user's to choose:
 * - CUSTOM: the user's self-hosted server serves its own ids ("gpt-oss:20b", "llama3.2:latest");
 * - OPEN_ROUTER: its picker lists the whole registry, and "google/gemini-…" silently ran as MiniMax.
 * ETHOS_PREMIUM keeps the fallback on purpose: the premium backend only serves the ids it bills for.
 *
 * The in-app chat and the launcher's turns both use this (SET-10: the launcher sent
 * `minimax/minimax-m3` to a user's own server whatever they had picked).
 */
object ModelIdOverride {
    fun of(provider: LlmProvider, modelId: String): String? = when {
        modelId.isBlank() -> null
        provider == LlmProvider.CUSTOM -> modelId
        provider == LlmProvider.OPEN_ROUTER && AnthropicModels.fromModelId(modelId) == null -> modelId
        else -> null
    }
}
