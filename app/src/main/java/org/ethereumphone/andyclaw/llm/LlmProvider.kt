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
    );

    companion object {
        fun fromName(name: String): LlmProvider? = entries.find { it.name == name }
    }
}
