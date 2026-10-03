package org.ethereumphone.andyclaw.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The lock every native llama call takes. The native side is not loaded here; what is pinned
 * is that a caller cancelled while another holds the model stops waiting, and that the
 * nested calls a generation makes (load, tokenize) do not deadlock on it.
 */
class LlamaCppLockTest {

    @Test
    fun `a caller that is no longer wanted stops waiting for the model`() {
        val llama = LlamaCpp()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread {
            llama.withNativeLock {
                held.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        assertTrue(held.await(5, TimeUnit.SECONDS))

        val started = System.nanoTime()
        try {
            llama.withNativeLock(stillWanted = { false }) { fail("ran without the lock") }
            fail("expected the wait to be given up")
        } catch (_: CancellationException) {
        }
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))

        release.countDown()
        holder.join()
        assertEquals(42, llama.withNativeLock { llama.withNativeLock { 42 } })
    }

    @Test
    fun `nothing is unloaded or tokenized without a model`() {
        val llama = LlamaCpp()
        llama.unload()
        assertEquals(-1, llama.tokenize("hello"))
    }
}
