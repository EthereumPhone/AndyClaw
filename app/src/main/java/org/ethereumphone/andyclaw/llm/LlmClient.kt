package org.ethereumphone.andyclaw.llm

/**
 * Common interface for all LLM providers.
 *
 * Uses the existing Anthropic message types as the internal format.
 * Providers that speak OpenAI format convert at the boundary.
 */
interface LlmClient {

    /** Send a non-streaming message and return the full response. */
    suspend fun sendMessage(request: MessagesRequest): MessagesResponse

    /** Stream a message, delivering tokens and tool calls via [callback]. */
    suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback)

    /**
     * Maximum number of tools this provider can handle per request.
     * -1 means unlimited (default for cloud providers).
     * Constrained providers (e.g. small local models) override this.
     */
    val maxToolCount: Int get() = -1
}
