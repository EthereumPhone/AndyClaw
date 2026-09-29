package org.ethereumphone.andyclaw.services

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The launcher's turns, at most one running per session.
 *
 * - **A newer prompt wins** (CHAT-02). A second prompt for a session used to overwrite the first
 *   one's entry without stopping it, so both runs appended to one unsynchronised history and the
 *   first could no longer be stopped. The launcher follows only its newest turn, so the older one
 *   is cancelled, and joined — it has stopped touching the history before the newer one starts.
 * - **A turn outlives nobody** (IPC-05). The launcher's callback binder is linked to death for as
 *   long as the turn runs: a home screen that crashed, was killed or recreated leaves no turn
 *   acting behind it, invisible and unstoppable. It ends the way STOP ends it, as cancelled.
 */
internal class LauncherTurns(private val scope: CoroutineScope) {

    private val active = ConcurrentHashMap<String, Job>()

    /**
     * Runs [body] as [sessionId]'s turn, after the turn it replaces has stopped. [caller] is the
     * launcher's callback binder; its death cancels the turn.
     */
    fun start(sessionId: String, caller: IBinder?, body: suspend () -> Unit): Job {
        // Lazy, so it is registered before it runs and can wait for the turn it replaces.
        var previous: Job? = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]!!
            val unlink = caller?.let { watchCallerDeath(it, self) }
            try {
                previous?.let { older ->
                    if (!older.isCompleted) {
                        Log.i(TAG, "New prompt while an older turn of the session is running; cancelling the older one")
                        older.cancelAndJoin()
                    }
                }
                // A caller that died before this started, or a STOP that came first: not a step.
                ensureActive()
                body()
            } finally {
                unlink?.invoke()
                // Only this turn's own entry: a newer prompt may have replaced it, and removing
                // that one would leave it impossible to stop.
                active.remove(sessionId, self)
            }
        }
        previous = active.put(sessionId, job)
        job.start()
        return job
    }

    /** Cancels [sessionId]'s turn. False when none was running. */
    fun stop(sessionId: String): Boolean {
        val job = active.remove(sessionId) ?: return false
        if (!job.isActive) return false
        job.cancel()
        return true
    }

    fun isRunning(sessionId: String): Boolean = active[sessionId]?.isActive == true

    companion object {
        private const val TAG = "LauncherTurns"

        /**
         * Cancels [job] when [binder]'s process dies, and returns what unlinks it again. A binder
         * that is already dead cancels the job at once: there is nobody left to answer.
         */
        fun watchCallerDeath(binder: IBinder, job: Job): () -> Unit {
            val recipient = IBinder.DeathRecipient {
                Log.i(TAG, "The launcher that started this turn died; cancelling it")
                job.cancel()
            }
            try {
                binder.linkToDeath(recipient, 0)
            } catch (e: RemoteException) {
                Log.i(TAG, "The launcher died before its turn started; cancelling it")
                job.cancel()
                return {}
            }
            return { runCatching { binder.unlinkToDeath(recipient, 0) } }
        }
    }
}

/**
 * Exactly one terminal callback per launcher turn (IPC-06): `onComplete` or `onError`, once. The
 * launcher treats either as the end of the turn — the face, the card, the input row all follow
 * it — so a second one un-did the first: an error cut short by an empty success, or an answer
 * popping up under an error.
 */
internal class TurnTerminal {
    private val sent = AtomicBoolean(false)

    val ended: Boolean get() = sent.get()

    /** Runs [deliver] if no terminal callback has gone out for this turn yet. */
    fun end(deliver: () -> Unit): Boolean {
        if (!sent.compareAndSet(false, true)) return false
        deliver()
        return true
    }
}
