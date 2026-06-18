package org.ethereumphone.andyclaw.llm

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Manages ChatGPT (Codex) OAuth tokens for [ChatGptOauthClient].
 *
 * The user obtains a refresh token by running `codex login` on a machine with
 * the Codex CLI and copying the `tokens.refresh_token` value out of
 * `~/.codex/auth.json`. This manager:
 *
 *  - Exchanges that refresh_token for a fresh access_token at
 *    `https://auth.openai.com/oauth/token` (RFC 6749 refresh_token grant).
 *  - Parses the id_token JWT to extract the `chatgpt_account_id`, which is
 *    required as a header on every Codex /responses request.
 *  - Caches the access_token + expiry + accountId in SecurePrefs to avoid a
 *    refresh on every call (the access_token's `exp` claim is honored).
 *  - Refresh-rotates the refresh_token if the server returns a new one.
 *
 * Thread-safe — concurrent callers share one in-flight refresh via [mutex].
 *
 * Reuses the Codex CLI's client_id; see Anthropic's analogous setup-token
 * pattern in [ClaudeOauthTokenManager]. OpenAI does not publish a public OAuth
 * program for the platform API; this is the same approach the community
 * `EvanZhouDev/openai-oauth` project uses and carries the same caveats — it is
 * for personal use with your own ChatGPT account, may be rate-limited, and
 * could break if OpenAI rotates Codex's client config.
 */
class ChatGptOauthTokenManager(
    private val refreshTokenProvider: () -> String,
    private val accessTokenProvider: () -> String,
    private val expiresAtProvider: () -> Long,
    private val accountIdProvider: () -> String,
    /** Persists new accessToken + accountId + expiry. Refresh token rotated separately via [refreshTokenSetter]. */
    private val onTokensUpdated: (accessToken: String, expiresAtMillis: Long, accountId: String) -> Unit,
    /** Called when the server rotates the refresh token (some IdPs do this). */
    private val refreshTokenSetter: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "ChatGptOauthTokenMgr"
        private const val TOKEN_ENDPOINT = "https://auth.openai.com/oauth/token"
        /** Same client_id the Codex CLI registers with auth.openai.com. */
        private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val SCOPE = "openid profile email offline_access"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        /** Refresh 5 minutes before expiry so an in-flight request doesn't 401. */
        private const val EXPIRY_BUFFER_MS = 5 * 60 * 1000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Returns (validAccessToken, accountId), refreshing if necessary. Thread-safe. */
    suspend fun getValidAuth(): Pair<String, String> {
        val current = accessTokenProvider()
        val expiresAt = expiresAtProvider()
        val accountId = accountIdProvider()

        if (current.isNotBlank() && accountId.isNotBlank() &&
            System.currentTimeMillis() < expiresAt - EXPIRY_BUFFER_MS
        ) {
            return current to accountId
        }

        return mutex.withLock {
            // Double-check after the lock — another coroutine may have already refreshed.
            val rechecked = accessTokenProvider()
            val recheckExpiry = expiresAtProvider()
            val recheckAccount = accountIdProvider()
            if (rechecked.isNotBlank() && recheckAccount.isNotBlank() &&
                System.currentTimeMillis() < recheckExpiry - EXPIRY_BUFFER_MS
            ) {
                return@withLock rechecked to recheckAccount
            }
            refresh()
        }
    }

    /** Force a refresh (e.g. after a 401). Returns (accessToken, accountId). */
    suspend fun forceRefresh(): Pair<String, String> = mutex.withLock { refresh() }

    private fun refresh(): Pair<String, String> {
        val refreshToken = refreshTokenProvider().trim()
        if (refreshToken.isBlank()) {
            throw ChatGptOauthException(
                "No ChatGPT refresh token configured. Run `codex login` and paste tokens.refresh_token from ~/.codex/auth.json in Settings.",
            )
        }

        Log.d(TAG, "Refreshing ChatGPT OAuth access token...")

        val body = json.encodeToString(
            TokenRequest.serializer(),
            TokenRequest(refresh_token = refreshToken),
        ).toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            Log.e(TAG, "ChatGPT token refresh failed: HTTP ${response.code} — $responseBody")
            throw ChatGptOauthException(
                "ChatGPT token refresh failed (HTTP ${response.code}). Your refresh_token may be expired or the Codex client_id may have changed — re-run `codex login` and paste the new refresh_token.",
            )
        }

        val tokenResponse = json.decodeFromString<TokenResponse>(responseBody)

        // exp is inside the access_token JWT (claim "exp", seconds). Falls back
        // to "expires_in" if the server included it, otherwise a conservative 30 min.
        val accessExpiryMs = jwtExpEpochMs(tokenResponse.access_token)
            ?: tokenResponse.expires_in?.let { System.currentTimeMillis() + it * 1000L }
            ?: (System.currentTimeMillis() + 30 * 60 * 1000L)

        val accountId = idTokenAccountId(tokenResponse.id_token)
            ?: throw ChatGptOauthException(
                "ChatGPT token response did not contain a chatgpt_account_id claim. The Codex client_id may have changed.",
            )

        onTokensUpdated(tokenResponse.access_token, accessExpiryMs, accountId)
        if (!tokenResponse.refresh_token.isNullOrBlank() && tokenResponse.refresh_token != refreshToken) {
            // Server rotated the refresh token — persist the new one.
            refreshTokenSetter(tokenResponse.refresh_token)
        }
        Log.i(TAG, "ChatGPT OAuth refreshed; expires in ${(accessExpiryMs - System.currentTimeMillis()) / 1000}s")

        return tokenResponse.access_token to accountId
    }

    /** Decode the middle (payload) segment of a JWT to a JsonObject. Returns null on any failure. */
    private fun jwtPayload(token: String?): JsonObject? {
        if (token.isNullOrBlank()) return null
        val parts = token.split(".")
        if (parts.size != 3) return null
        return try {
            val padded = parts[1] + "=".repeat((4 - parts[1].length % 4) % 4)
            val bytes = Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
            json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode JWT payload", e)
            null
        }
    }

    private fun jwtExpEpochMs(token: String?): Long? {
        val payload = jwtPayload(token) ?: return null
        val exp = payload["exp"]?.jsonPrimitive?.longOrNull ?: return null
        return exp * 1000L
    }

    /**
     * Extract the `chatgpt_account_id` claim, which lives nested under the
     * `https://api.openai.com/auth` claim namespace per the Codex auth.ts
     * reference implementation.
     */
    private fun idTokenAccountId(idToken: String?): String? {
        val payload = jwtPayload(idToken) ?: return null
        val authClaim = payload["https://api.openai.com/auth"]?.jsonObject ?: return null
        return authClaim["chatgpt_account_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    @Serializable
    private data class TokenRequest(
        val grant_type: String = "refresh_token",
        val refresh_token: String,
        val client_id: String = CLIENT_ID,
        val scope: String = SCOPE,
    )

    @Serializable
    private data class TokenResponse(
        val token_type: String = "",
        val access_token: String,
        val expires_in: Long? = null,
        val id_token: String? = null,
        val refresh_token: String? = null,
    )
}

class ChatGptOauthException(message: String) : Exception(message)
