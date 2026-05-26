package org.ethereumphone.andyclaw.llm

enum class LlmProvider(val displayName: String, val description: String) {
    ETHOS_PREMIUM(
        displayName = "ethOS Premium LLM",
        description = "Uses your ethOS paymaster balance for inference. No API key required.",
    ),
    OPEN_ROUTER(
        displayName = "OpenRouter",
        description = "Cloud inference via OpenRouter. Fast and capable, but prompts are processed by third-party servers.",
    ),
    TINFOIL(
        displayName = "Tinfoil TEE",
        description = "Cloud inference inside a verified Trusted Execution Environment. Strong privacy with good performance.",
    ),
    CLAUDE_OAUTH(
        displayName = "Claude (OAuth)",
        description = "Uses your Claude Pro/Max subscription directly via Anthropic's API. Requires a setup-token from Claude Code CLI.",
    ),
    OPENAI_OAUTH(
        displayName = "ChatGPT (OAuth)",
        description = "Uses your ChatGPT Plus/Pro/Business subscription via OpenAI's Codex backend. Requires a refresh token from the Codex CLI (~/.codex/auth.json after `codex login`). Codex-supported models only.",
    ),
    OPENAI(
        displayName = "OpenAI",
        description = "Cloud inference via OpenAI's API. Requires an OpenAI API key.",
    ),
    VENICE(
        displayName = "Venice AI",
        description = "Privacy-focused cloud inference via Venice AI. Uncensored models available. Requires a Venice API key.",
    ),
    LOCAL(
        displayName = "On-Device",
        description = "Runs entirely on your phone. No data leaves the device. Slower performance, limited capabilities.",
    ),
    CUSTOM(
        displayName = "Custom Server",
        description = "Connect to any OpenAI-compatible HTTP endpoint you host yourself (Ollama, LM Studio, vLLM, llama.cpp server, LocalAI, …). Enter the full /v1/chat/completions URL, an optional API key, and the model id served by your backend.",
    );

    companion object {
        fun fromName(name: String): LlmProvider? = entries.find { it.name == name }
    }
}
