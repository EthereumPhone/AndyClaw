package org.ethereumphone.andyclaw.llm

/**
 * LLM client for Claude setup-tokens (sk-ant-oat01-...).
 *
 * Matches OpenClaw behavior:
 * - Use the setup-token directly as Bearer auth
 * - Call Anthropic's native Messages API
 * - Include Anthropic OAuth beta headers required for setup-token auth
 */
class ClaudeOauthClient(
    private val setupTokenProvider: () -> String,
) : LlmClient {

    companion object {
        private const val BASE_URL = "https://api.anthropic.com/v1/messages"
        private const val OAUTH_BETAS =
            "claude-code-20250219,oauth-2025-04-20,fine-grained-tool-streaming-2025-05-14,interleaved-thinking-2025-05-14"
    }

    override suspend fun sendMessage(request: MessagesRequest): MessagesResponse {
        val token = setupTokenProvider().trim()
        if (token.isBlank()) {
            throw ClaudeOauthException("No setup-token configured. Paste your Claude setup-token in Settings.")
        }
        return clientWithToken(token).sendMessage(request)
    }

    override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) {
        val token = setupTokenProvider().trim()
        if (token.isBlank()) {
            // Thrown like every other failure; onError plus a normal return read as an empty reply.
            throw ClaudeOauthException("No setup-token configured. Paste your Claude setup-token in Settings.")
        }
        clientWithToken(token).streamMessage(request, callback)
    }

    /**
     * One [AnthropicClient] per token, not per call: each one builds its own OkHttpClient,
     * and a fresh connection pool per request threw away keep-alive and TLS resumption on
     * every model call of every turn.
     */
    @Volatile private var cached: Pair<String, AnthropicClient>? = null

    private fun clientWithToken(token: String): AnthropicClient {
        cached?.let { (t, c) -> if (t == token) return c }
        val client = AnthropicClient(
            apiKey = { token },
            extraHeaders = {
                mapOf(
                    "anthropic-beta" to OAUTH_BETAS,
                )
            },
            baseUrl = BASE_URL,
            anthropicDirect = true,
        )
        cached = token to client
        return client
    }
}
