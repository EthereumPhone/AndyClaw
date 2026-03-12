package org.ethereumphone.andyclaw.google

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ethereumphone.andyclaw.SecurePrefs
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class GoogleAuthManager(private val securePrefs: SecurePrefs) {

    companion object {
        private const val TAG = "GoogleAuthManager"
        private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val EXPIRY_BUFFER_MS = 5 * 60 * 1000L
        private val SCOPES = listOf(
            "https://www.googleapis.com/auth/gmail.modify",
            "https://www.googleapis.com/auth/drive",
            "https://www.googleapis.com/auth/calendar",
            "https://www.googleapis.com/auth/spreadsheets",
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val isAuthenticated: Boolean
        get() = securePrefs.googleOauthRefreshToken.value.isNotBlank()

    suspend fun getAccessToken(): String {
        val current = securePrefs.googleOauthAccessToken.value
        val expiresAt = securePrefs.googleOauthExpiresAt.value

        if (current.isNotBlank() && System.currentTimeMillis() < expiresAt - EXPIRY_BUFFER_MS) {
            return current
        }

        return mutex.withLock {
            val rechecked = securePrefs.googleOauthAccessToken.value
            val recheckExpiry = securePrefs.googleOauthExpiresAt.value
            if (rechecked.isNotBlank() && System.currentTimeMillis() < recheckExpiry - EXPIRY_BUFFER_MS) {
                return@withLock rechecked
            }
            refreshToken()
        }
    }

    /**
     * Starts the OAuth flow by launching a local HTTP server on a random port,
     * opening the browser for Google sign-in, and waiting for the loopback redirect.
     * Must be called from a coroutine (runs the server on IO dispatcher).
     */
    suspend fun startOAuthFlow(context: Context) = withContext(Dispatchers.IO) {
        val clientId = securePrefs.googleOauthClientId.value
        if (clientId.isBlank()) {
            Log.e(TAG, "No Google OAuth Client ID configured")
            return@withContext
        }

        val server = ServerSocket(0) // bind to random available port
        val port = server.localPort
        val redirectUri = "http://127.0.0.1:$port"

        // Generate a cryptographically random state token to prevent CSRF
        val stateBytes = ByteArray(32)
        SecureRandom().nextBytes(stateBytes)
        val state = android.util.Base64.encodeToString(
            stateBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )

        Log.i(TAG, "Started loopback OAuth server on port $port")

        try {
            // Set a generous timeout so the server doesn't hang forever
            server.soTimeout = 5 * 60 * 1000 // 5 minutes

            val uri = Uri.parse(AUTH_URL).buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", SCOPES.joinToString(" "))
                .appendQueryParameter("access_type", "offline")
                .appendQueryParameter("prompt", "consent")
                .appendQueryParameter("state", state)
                .build()

            // Open browser on the main thread
            withContext(Dispatchers.Main) {
                val browserIntent = Intent(Intent.ACTION_VIEW, uri)
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(browserIntent)
            }

            // Wait for the browser to redirect back to our loopback server
            val socket = server.accept()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: ""
            // e.g. "GET /?code=4/0Abc...&state=...&scope=... HTTP/1.1"

            val code = extractCodeFromRequest(requestLine)
            val returnedState = extractParamFromRequest(requestLine, "state")
            val error = extractParamFromRequest(requestLine, "error")

            // Validate state parameter to prevent CSRF
            val stateValid = returnedState == state

            // Send a user-friendly response page (no user-controlled content in HTML)
            val htmlBody = when {
                !stateValid -> {
                    "<html><body style='font-family:sans-serif;text-align:center;padding:40px'>" +
                        "<h2>Error</h2><p>Invalid OAuth state. Please try again from the app.</p></body></html>"
                }
                code != null -> {
                    "<html><body style='font-family:sans-serif;text-align:center;padding:40px'>" +
                        "<h2>Connected!</h2><p>You can close this tab and return to AndyClaw.</p></body></html>"
                }
                else -> {
                    "<html><body style='font-family:sans-serif;text-align:center;padding:40px'>" +
                        "<h2>Error</h2><p>Authorization was denied or failed. Please try again.</p></body></html>"
                }
            }
            val httpResponse = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n$htmlBody"
            socket.getOutputStream().write(httpResponse.toByteArray())
            socket.getOutputStream().flush()
            socket.close()

            if (!stateValid) {
                Log.e(TAG, "OAuth state mismatch — possible CSRF attack")
            } else if (code != null) {
                exchangeCode(code, redirectUri)
            } else {
                Log.e(TAG, "OAuth callback had no code. Error: $error")
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "OAuth flow timed out — user did not complete sign-in within 5 minutes")
        } catch (e: Exception) {
            Log.e(TAG, "OAuth flow error: ${e.message}", e)
        } finally {
            try { server.close() } catch (_: Exception) {}
        }
    }

    private fun exchangeCode(code: String, redirectUri: String) {
        val clientId = securePrefs.googleOauthClientId.value
        val clientSecret = securePrefs.googleOauthClientSecret.value

        val body = FormBody.Builder()
            .add("code", code)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("redirect_uri", redirectUri)
            .add("grant_type", "authorization_code")
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            Log.e(TAG, "Code exchange failed: HTTP ${response.code} — $responseBody")
            throw GoogleAuthException("Code exchange failed (${response.code})")
        }

        val tokenResponse = json.decodeFromString<TokenResponse>(responseBody)
        val expiresAt = System.currentTimeMillis() + (tokenResponse.expires_in * 1000L)

        securePrefs.setGoogleOauthAccessToken(tokenResponse.access_token)
        securePrefs.setGoogleOauthExpiresAt(expiresAt)
        if (tokenResponse.refresh_token.isNotBlank()) {
            securePrefs.setGoogleOauthRefreshToken(tokenResponse.refresh_token)
        }

        Log.i(TAG, "Google OAuth tokens obtained, expires in ${tokenResponse.expires_in}s")
    }

    private fun refreshToken(): String {
        val refreshToken = securePrefs.googleOauthRefreshToken.value
        if (refreshToken.isBlank()) {
            throw GoogleAuthException("No Google refresh token. Connect your Google account in Settings.")
        }

        val clientId = securePrefs.googleOauthClientId.value
        val clientSecret = securePrefs.googleOauthClientSecret.value

        Log.d(TAG, "Refreshing Google OAuth access token...")

        val body = FormBody.Builder()
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("grant_type", "refresh_token")
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            Log.e(TAG, "Token refresh failed: HTTP ${response.code} — $responseBody")
            throw GoogleAuthException("Google token refresh failed (${response.code}). Reconnect your Google account in Settings.")
        }

        val tokenResponse = json.decodeFromString<TokenResponse>(responseBody)
        val expiresAt = System.currentTimeMillis() + (tokenResponse.expires_in * 1000L)

        securePrefs.setGoogleOauthAccessToken(tokenResponse.access_token)
        securePrefs.setGoogleOauthExpiresAt(expiresAt)

        Log.i(TAG, "Google OAuth token refreshed, expires in ${tokenResponse.expires_in}s")
        return tokenResponse.access_token
    }

    private fun extractCodeFromRequest(requestLine: String): String? {
        return extractParamFromRequest(requestLine, "code")
    }

    private fun extractParamFromRequest(requestLine: String, param: String): String? {
        // requestLine looks like: "GET /?code=xxx&scope=yyy HTTP/1.1"
        val queryStart = requestLine.indexOf('?')
        val queryEnd = requestLine.indexOf(" HTTP/")
        if (queryStart < 0 || queryEnd < 0) return null

        val query = requestLine.substring(queryStart + 1, queryEnd)
        return query.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == param }
            ?.let { URLDecoder.decode(it[1], "UTF-8") }
    }

    @Serializable
    private data class TokenResponse(
        val access_token: String,
        val expires_in: Long = 3600,
        val refresh_token: String = "",
        val token_type: String = "",
        val scope: String = "",
    )
}

class GoogleAuthException(message: String) : Exception(message)
