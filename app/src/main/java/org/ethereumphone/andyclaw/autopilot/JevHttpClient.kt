package org.ethereumphone.andyclaw.autopilot

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Jev through our backend (`/api/jev`), authenticated with the same wallet signature as the
 * premium LLM route.
 *
 * Tuned for many small, latency-bound calls: one shared connection pool (the first call warms
 * TLS, every later step reuses it), tight timeouts, and gzip on the request body — a screen's
 * worth of state compresses several-fold, which matters on a mobile uplink.
 */
class JevHttpClient(
    private val userId: () -> String,
    private val signature: () -> String,
    private val url: String = "https://api.markushaas.com/api/jev",
) : JevClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .callTimeout(3500, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Until when (elapsedRealtime) to not bother: a 401/403/503 means disabled, no balance or a
     * bad sign-in, none of which the next step's call would find fixed. Without this every run
     * spent its first step — and every run after it — rediscovering the same refusal.
     */
    @Volatile private var unavailableUntil = 0L

    override suspend fun evaluate(request: JevRequest): JevResponse = withContext(Dispatchers.IO) {
        val uid = userId()
        val sig = signature()
        if (uid.isBlank() || sig.isBlank()) throw JevUnavailableException("no wallet sign-in")
        if (SystemClock.elapsedRealtime() < unavailableUntil) throw JevUnavailableException("recently unavailable")

        val body = gzip(request.toJson().toString().toByteArray())
        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("X-User-Id", uid)
            .addHeader("X-Signature", sig)
            .addHeader("Content-Encoding", "gzip")
            .post(body.toRequestBody(JSON))
            .build()

        val started = SystemClock.elapsedRealtime()
        // Enqueued rather than executed, so that STOP — which cancels this coroutine — cancels
        // the HTTP call with it instead of waiting out the network.
        val call = http.newCall(httpRequest)
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val outcome = runCatching {
                        response.use {
                            val text = it.body?.string().orEmpty()
                            val rtt = SystemClock.elapsedRealtime() - started
                            when {
                                it.isSuccessful -> JevResponseParser.parse(text, request.questions, rtt)
                                // Disabled server-side, no balance, bad sign-in: nothing a retry would fix.
                                it.code == 401 || it.code == 403 || it.code == 503 -> {
                                    unavailableUntil = SystemClock.elapsedRealtime() + UNAVAILABLE_BACKOFF_MS
                                    throw JevUnavailableException("HTTP ${it.code}", it.code)
                                }
                                else -> throw IOException("Jev HTTP ${it.code}")
                            }
                        }
                    }
                    outcome.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }
            })
        }
    }

    /** Opens the connection ahead of the first real step. Best effort. */
    suspend fun prewarm() = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(url).head().build()).execute().close()
        } catch (e: Exception) {
            Log.d(TAG, "prewarm failed: ${e.message}")
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "JevHttpClient"
        private val JSON = "application/json".toMediaType()
        private const val UNAVAILABLE_BACKOFF_MS = 5 * 60_000L
    }
}
