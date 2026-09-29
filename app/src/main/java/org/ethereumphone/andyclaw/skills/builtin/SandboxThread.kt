package org.ethereumphone.andyclaw.skills.builtin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.OutputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs in-process interpreted code (BeanShell: `execute_code`, custom tools) on a fresh daemon
 * thread per call.
 *
 * Both used to share one `newSingleThreadExecutor()`. `future.cancel(true)` only interrupts, and
 * a BeanShell `while (true) {}` never checks — so one runaway held the only thread forever and
 * every later call queued behind it until the process died. A thread per call means a runaway
 * wedges only itself.
 *
 * Residual: nothing can *stop* such a thread. `Thread.stop()` throws
 * UnsupportedOperationException on Android, so on timeout or cancel the thread is interrupted
 * and abandoned; it keeps burning a core until it finishes or the process is restarted. It is a
 * daemon, so it never holds the process open.
 */
internal object SandboxThread {
    private val seq = AtomicInteger()

    fun <T> start(name: String, block: () -> T): FutureTask<T> {
        val task = FutureTask(block)
        Thread(task, "$name-${seq.incrementAndGet()}").apply {
            isDaemon = true
            start()
        }
        return task
    }

    /**
     * Waits up to [timeoutMs] for [task], cancellably: a cancelled run interrupts the wait
     * (and the task) instead of blocking the agent's STOP for the whole timeout.
     * Throws [java.util.concurrent.TimeoutException] / [java.util.concurrent.ExecutionException]
     * exactly as `Future.get` does.
     */
    suspend fun <T> await(task: FutureTask<T>, timeoutMs: Long): T {
        try {
            return runInterruptible(Dispatchers.IO) { task.get(timeoutMs, TimeUnit.MILLISECONDS) }
        } finally {
            if (!task.isDone) task.cancel(true)
        }
    }

    /** Blocking counterpart of [await] for non-suspend callers. */
    fun <T> awaitBlocking(task: FutureTask<T>, timeoutMs: Long): T {
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            if (!task.isDone) task.cancel(true)
        }
    }
}

/**
 * An in-memory sink that keeps the first [limit] bytes and silently drops the rest.
 *
 * `System.out`-style printing from interpreted code went into an unbounded
 * ByteArrayOutputStream, so a print loop grew the heap until OutOfMemoryError — an Error, which
 * no tool's `catch (e: Exception)` sees, so it crashed the app. It does not throw when full:
 * the PrintStream in front of it would swallow the IOException anyway, and the caller already
 * ends a runaway by timeout.
 */
internal class BoundedOutputStream(private val limit: Int) : OutputStream() {
    private val buf = java.io.ByteArrayOutputStream()

    @Volatile
    var overflowed: Boolean = false
        private set

    @Synchronized
    override fun write(b: Int) {
        if (buf.size() < limit) buf.write(b) else overflowed = true
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        val room = limit - buf.size()
        if (len > room) overflowed = true
        if (room > 0) buf.write(b, off, minOf(len, room))
    }

    @Synchronized
    override fun toString(): String = buf.toString(Charsets.UTF_8.name())
}
