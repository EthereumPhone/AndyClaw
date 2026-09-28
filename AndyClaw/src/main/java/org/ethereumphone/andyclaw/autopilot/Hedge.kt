package org.ethereumphone.andyclaw.autopilot

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A hedged request: [attempt] once, and if it has not answered within [hedgeAfterMs], once
 * more in parallel; the first success wins and the other is cancelled.
 *
 * For Jev, whose failures are not slowness but hangs: on 2026-09-28 half the calls to
 * `/api/jev` returned 504 after ~4.06 s at the backend's gateway while the other half answered
 * in ~360 ms, at random. With one request, a hang cost the step 3.5 s and its retry 3.5 s more;
 * with a second request sent at 0.8 s, a step fails only when both do.
 *
 * An attempt that fails early starts the other at once rather than waiting out the hedge
 * delay. A [fatal] failure (the service refused: no point asking again) ends it immediately.
 */
object Hedge {

    suspend fun <T> firstSuccess(
        hedgeAfterMs: Long,
        attempts: Int = 2,
        fatal: (Throwable) -> Boolean = { false },
        attempt: suspend (index: Int) -> T,
    ): T = coroutineScope {
        val results = Channel<Result<T>>(attempts)
        val started = Channel<Unit>(attempts)
        val jobs = (0 until attempts).map { i ->
            async {
                if (i > 0) {
                    // Wait for the hedge delay, or for an earlier attempt to have failed.
                    withTimeoutOrNull(hedgeAfterMs * i) { started.receive() }
                }
                val r = runCatching { attempt(i) }
                if (r.exceptionOrNull()?.let { !fatal(it) } == true) started.trySend(Unit)
                results.send(r)
            }
        }
        var lastError: Throwable? = null
        try {
            repeat(attempts) {
                val r = results.receive()
                r.onSuccess { return@coroutineScope it }
                val e = r.exceptionOrNull()!!
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (fatal(e)) throw e
                lastError = e
            }
            throw lastError!!
        } finally {
            jobs.forEach { it.cancel() }
        }
    }
}
