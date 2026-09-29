package org.ethereumphone.andyclaw.skills.builtin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.PrintStream
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The limits that keep a tool from holding the agent hostage: processes that never exit,
 * interpreted loops that never yield, and reads that never end.
 */
class ExecutionLimitsTest {

    private fun sh(cmd: String): Process = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()

    // ── BoundedProcess ─────────────────────────────────────────────────

    @Test
    fun `a process that never exits is killed at the timeout`() = runBlocking {
        val p = sh("sleep 30")
        val started = System.nanoTime()
        val r = BoundedProcess.await(p, timeoutMs = 500, maxChars = 1000)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(r.timedOut)
        assertNull(r.exitCode)
        assertTrue("took ${tookMs}ms", tookMs < 5_000)
        assertFalse(p.isAlive)
    }

    @Test
    fun `endless output is bounded and does not block the timeout`() = runBlocking {
        val r = BoundedProcess.await(sh("yes"), timeoutMs = 500, maxChars = 1000)
        assertTrue(r.timedOut)
        assertEquals(1000, r.output.length)
        assertTrue(r.truncated)
    }

    @Test
    fun `stdin is closed so a reader sees EOF`() = runBlocking {
        val r = BoundedProcess.await(sh("cat; echo done"), timeoutMs = 5_000, maxChars = 1000)
        assertEquals(0, r.exitCode)
        assertEquals("done\n", r.output)
        assertFalse(r.truncated)
    }

    @Test
    fun `cancelling the run ends the wait and kills the process`() = runBlocking {
        val p = sh("sleep 30")
        val job = async(Dispatchers.IO) { BoundedProcess.await(p, timeoutMs = 60_000, maxChars = 1000) }
        delay(200)
        withTimeout(5_000) { job.cancel(); job.join() }
        assertTrue(job.isCancelled)
        assertTrue(p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun `a backgrounded child holding the pipe does not hang the result`() = runBlocking {
        val started = System.nanoTime()
        val r = BoundedProcess.await(sh("sleep 20 & echo hi"), timeoutMs = 5_000, maxChars = 1000)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(0, r.exitCode)
        assertTrue(r.output.startsWith("hi"))
        assertTrue("took ${tookMs}ms", tookMs < 5_000)
    }

    // ── SandboxThread ──────────────────────────────────────────────────

    @Test
    fun `a runaway that ignores interrupts does not wedge the next call`() = runBlocking {
        val stop = AtomicBoolean(false)
        val runaway = SandboxThread.start("test") {
            @Suppress("ControlFlowWithEmptyBody")
            while (!stop.get()) { /* ignores interrupts, like a BeanShell while(true) */ }
        }
        try {
            SandboxThread.await(runaway, 200)
            error("expected a timeout")
        } catch (_: TimeoutException) {
        }
        // On the old single-thread executor this queued behind the runaway forever.
        val next = SandboxThread.start("test") { 42 }
        assertEquals(42, SandboxThread.await(next, 2_000))
        stop.set(true)
    }

    @Test
    fun `bounded output keeps the first bytes and flags the rest`() {
        val sink = BoundedOutputStream(10)
        val ps = PrintStream(sink, true, "UTF-8")
        repeat(1000) { ps.print("abcdef") }
        assertEquals("abcdefabcd", sink.toString())
        assertTrue(sink.overflowed)
    }

    // ── BoundedText ────────────────────────────────────────────────────

    @Test
    fun `a bounded read stops at the limit`() {
        val r = BoundedText.read(ByteArrayInputStream(ByteArray(1_000) { 'a'.code.toByte() }), 100)
        assertEquals(100, r.text.length)
        assertTrue(r.truncated)
        val exact = BoundedText.read(ByteArrayInputStream("hello".toByteArray()), 5)
        assertEquals("hello", exact.text)
        assertFalse(exact.truncated)
    }

    @Test
    fun `only text mime types are text`() {
        assertTrue(BoundedText.isTextMime("text/plain; charset=utf-8"))
        assertTrue(BoundedText.isTextMime("application/json"))
        assertTrue(BoundedText.isTextMime("application/ld+json"))
        assertFalse(BoundedText.isTextMime("image/png"))
        assertFalse(BoundedText.isTextMime("application/zip"))
        assertFalse(BoundedText.isTextMime(null))
    }

    @Test
    fun `html is reduced to text`() {
        val html = "<html><head><style>p{}</style></head><body><p>Hi&nbsp;there</p>" +
            "<script>alert(1)</script><div>A &amp; B</div></body></html>"
        assertEquals("Hi there\nA & B", BoundedText.htmlToText(html))
    }

    // ── TriggerIds ─────────────────────────────────────────────────────

    @Test
    fun `ids created in the same millisecond differ and stay positive`() {
        val now = 1_790_000_000_123L
        val a = TriggerIds.next(now) { false }
        val b = TriggerIds.next(now) { false }
        assertNotEquals(a, b)
        assertTrue(a > 0 && b > 0)
    }

    @Test
    fun `an id already in use is skipped`() {
        val now = 1_800_000_000_000L
        val first = TriggerIds.next(now) { false }
        val next = TriggerIds.next(now) { it == first + 1 }
        assertEquals(first + 2, next)
    }
}
