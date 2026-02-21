package org.ethereumphone.andyclaw.llm

enum class LlmProvider(val displayName: String, val description: String) {
    OPEN_ROUTER(
        displayName = "OpenRouter",
        description = "Cloud inference via OpenRouter. Fast and capable, but prompts are processed by third-party servers.",
    ),
    TINFOIL(
        displayName = "Tinfoil TEE",
        description = "Cloud inference inside a verified Trusted Execution Environment. Strong privacy with good performance.",
    ),
    LOCAL(
        displayName = "On-Device",
        description = "Runs entirely on your phone. No data leaves the device. Slower performance, limited capabilities.",
    );

    companion object {
        fun fromName(name: String): LlmProvider? = entries.find { it.name == name }
    }
}
