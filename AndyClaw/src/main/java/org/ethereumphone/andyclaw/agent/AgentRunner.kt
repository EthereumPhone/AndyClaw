package org.ethereumphone.andyclaw.agent

/**
 * Response from an agent run.
 */
data class AgentResponse(
    val text: String,
    val isError: Boolean = false,
)

/**
 * Interface for running AI agent prompts.
 * Implementations provide the actual LLM call (Anthropic, OpenAI, local, gateway-proxied, etc.).
 */
interface AgentRunner {

    /**
     * Run the agent with a user prompt and optional system prompt.
     * The implementation handles model selection, API calls, and response parsing.
     */
    suspend fun run(
        prompt: String,
        systemPrompt: String? = null,
        skillsPrompt: String? = null,
    ): AgentResponse
}
