package org.ethereumphone.andyclaw.skills.builtin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Waits for a started [Process] without letting it hold the agent hostage.
 *
 * The old shell tool read stdout to EOF *before* its `waitFor(timeout)`, so `logcat`,
 * `tail -f` or `sleep 9999` never reached the timeout, and the blocking read ignored the run's
 * cancellation — STOP could not end the tool. Here:
 *  - stdin is closed at once, so a command waiting for input sees EOF instead of hanging;
 *  - output is drained on its own daemon thread into a bounded buffer (the rest is read and
 *    discarded, so a chatty process cannot block on a full pipe or grow our heap);
 *  - the wait is [runInterruptible], so cancelling the run interrupts it, and the process is
 *    destroyed in `finally` on every exit — timeout, cancel or error.
 *
 * Residual: `destroyForcibly()` kills the direct child (`sh`) only. A grandchild it
 * backgrounded can outlive it and keep the pipe open; we stop waiting for the drain thread
 * after [DRAIN_GRACE_MS] and return what we have rather than wait on it.
 */
internal object BoundedProcess {

    data class Result(
        /** null when the process did not finish within the timeout. */
        val exitCode: Int?,
        val output: String,
        val truncated: Boolean,
    ) {
        val timedOut: Boolean get() = exitCode == null
    }

    private const val DRAIN_GRACE_MS = 1_000L

    suspend fun await(process: Process, timeoutMs: Long, maxChars: Int): Result {
        try {
            process.outputStream.close()
        } catch (_: Exception) {
            // Already closed or the process is gone; nothing to feed it either way.
        }
        // Bytes, not chars: UTF-8 is at most 4 bytes a char, so this always holds maxChars.
        val sink = BoundedSink(maxChars.toLong() * 4)
        val drainer = Thread({ sink.drain(process.inputStream) }, "BoundedProcess-drain").apply {
            isDaemon = true
            start()
        }
        var finished = false
        try {
            finished = runInterruptible(Dispatchers.IO) {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            }
        } finally {
            if (!finished) process.destroyForcibly()
        }
        runInterruptible(Dispatchers.IO) { drainer.join(DRAIN_GRACE_MS) }
        if (drainer.isAlive) {
            // A backgrounded grandchild still holds the pipe. Closing our end unblocks the
            // reader on Android; where it does not, the daemon thread ends with that child.
            try { process.inputStream.close() } catch (_: Exception) { }
        }
        val (text, overflowed) = sink.snapshot()
        return Result(
            exitCode = if (finished) process.exitValue() else null,
            output = text.take(maxChars),
            truncated = overflowed || text.length > maxChars,
        )
    }

    /** Keeps the first [limit] bytes, reads and drops the rest until EOF. */
    internal class BoundedSink(private val limit: Long) {
        private val buf = java.io.ByteArrayOutputStream()
        private var overflowed = false

        fun drain(input: InputStream) {
            val chunk = ByteArray(8192)
            try {
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    synchronized(this) {
                        val room = (limit - buf.size()).coerceAtLeast(0).toInt()
                        if (n > room) overflowed = true
                        if (room > 0) buf.write(chunk, 0, minOf(n, room))
                    }
                }
            } catch (_: Exception) {
                // Stream closed under us (process destroyed); keep what we have.
            }
        }

        @Synchronized
        fun snapshot(): Pair<String, Boolean> = buf.toString(Charsets.UTF_8.name()) to overflowed
    }
}
