package org.ethereumphone.andyclaw.ingest

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ethereumphone.andyclaw.google.GoogleAuthException
import org.ethereumphone.andyclaw.google.GoogleAuthExpiredException
import java.io.IOException

/**
 * One ingest's conversation with a Google API.
 *
 * The access token is fetched once per ingest; a 401 drops the cached one and refreshes it
 * exactly once, because a revoked or early-expired token otherwise made every call fail until
 * its stated expiry. Every failure comes back classified the way the launcher reports it —
 * no account, sign-in expired, offline — and once the token cannot be had, nothing else in
 * this ingest asks for it again.
 */
internal class GoogleSession(
    private val getAccessToken: suspend () -> String,
    private val invalidateToken: () -> Unit,
    private val client: OkHttpClient,
    private val tag: String,
) {

    sealed interface Response {
        data class Ok(val body: String) : Response
        data class Unavailable(val problem: IngestProblem, val detail: String? = null) : Response
    }

    private var token: String? = null
    private var refreshed = false
    private var authFailure: Response.Unavailable? = null

    suspend fun get(url: String): Response {
        authFailure?.let { return it }
        val first = token ?: acquire() ?: return authFailure!!
        token = first
        val response = execute(url, first)
        if (response !is Status || response.code != 401) return response.asResponse()
        if (refreshed) return Response.Unavailable(IngestProblem.AUTH_EXPIRED, "HTTP 401")

        refreshed = true
        invalidateToken()
        val second = acquire() ?: return authFailure!!
        token = second
        val retried = execute(url, second)
        if (retried is Status && retried.code == 401) {
            return Response.Unavailable(IngestProblem.AUTH_EXPIRED, "HTTP 401").also { authFailure = it }
        }
        return retried.asResponse()
    }

    private suspend fun acquire(): String? = try {
        getAccessToken()
    } catch (e: GoogleAuthExpiredException) {
        fail(IngestProblem.AUTH_EXPIRED, e.message)
    } catch (e: GoogleAuthException) {
        // No refresh token at all is "no account"; a failed refresh is a server problem.
        fail(if (e.message?.contains("No Google refresh token") == true) IngestProblem.NO_ACCOUNT else IngestProblem.FAILED, e.message)
    } catch (e: IOException) {
        fail(IngestProblem.OFFLINE, e.message)
    } catch (e: Exception) {
        org.ethereumphone.andyclaw.ExecutionEngine.rethrowIfCancelled(e)
        fail(IngestProblem.FAILED, e.message)
    }

    private fun fail(problem: IngestProblem, detail: String?): String? {
        Log.i(tag, "no Google access token (${problem.name}): $detail")
        authFailure = Response.Unavailable(problem, detail)
        return null
    }

    private sealed interface Raw {
        fun asResponse(): Response
    }

    private data class Body(val body: String) : Raw {
        override fun asResponse() = Response.Ok(body)
    }

    private data class Status(val code: Int) : Raw {
        override fun asResponse() = Response.Unavailable(IngestProblem.FAILED, "HTTP $code")
    }

    private data class Broken(val detail: String?) : Raw {
        override fun asResponse() = Response.Unavailable(IngestProblem.OFFLINE, detail)
    }

    private fun execute(url: String, token: String): Raw = try {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (response.isSuccessful && body != null) {
                Body(body)
            } else {
                Log.w(tag, "Google ${response.code} for ${url.substringBefore('?')}")
                Status(response.code)
            }
        }
    } catch (e: IOException) {
        Log.w(tag, "Google request failed: ${e.message}")
        Broken(e.message)
    }
}
