package org.ethereumphone.andyclaw.agent

import org.ethereumphone.andyclaw.ExecutionEngine.Provenance

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
     *
     * [provenance] says where the content in [prompt] came from and decides what the
     * run is allowed to do. It defaults to [Provenance.UNTRUSTED] on purpose: a
     * caller that has not thought about provenance gets the most restricted run, not
     * the least. Every trusted trigger states its class explicitly.
     *
     * [conversationId] identifies the conversation the trigger arrived on — an XMTP
     * sender address, a Telegram chat id. Under [Provenance.UNTRUSTED] outbound
     * messaging is confined to it.
     */
    suspend fun run(
        prompt: String,
        systemPrompt: String? = null,
        skillsPrompt: String? = null,
        provenance: Provenance = Provenance.UNTRUSTED,
        conversationId: String? = null,
    ): AgentResponse
}
