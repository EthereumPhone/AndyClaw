package org.ethereumphone.andyclaw.ExecutionEngine

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * For the top of a `catch (e: Exception)`: rethrows when [e] is this coroutine's own
 * cancellation, so a cancelled run stops instead of becoming an ordinary tool error that the
 * loop feeds back to the model and carries on from.
 *
 * A [CancellationException] that is *not* ours — a tool's own `withTimeout` firing — returns
 * normally, and the caller handles it as the failure it is. Rethrowing that one would end the
 * whole turn silently: the `launch` that started the run takes a CancellationException for a
 * cancel, and reports nothing to anyone.
 */
suspend fun rethrowIfCancelled(e: Throwable) {
    if (e is CancellationException) currentCoroutineContext().ensureActive()
}
