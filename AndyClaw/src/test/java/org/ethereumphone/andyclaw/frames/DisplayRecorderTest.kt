package org.ethereumphone.andyclaw.frames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayRecorderTest {

    private fun frame(n: Int) = ByteArray(32) { n.toByte() }

    @Test
    fun `a new screen is kept at most once a second`() {
        val r = DisplayRecorder(minIntervalMs = 1_000)
        assertEquals(DisplayRecorder.Verdict.KEEP, r.offer(frame(1), 0))
        assertEquals(DisplayRecorder.Verdict.TOO_SOON, r.offer(frame(2), 200))
        assertEquals(DisplayRecorder.Verdict.KEEP, r.offer(frame(2), 1_000))
        assertEquals(2, r.kept)
    }

    @Test
    fun `a screen that sits still is kept once`() {
        val r = DisplayRecorder(minIntervalMs = 1_000)
        r.offer(frame(1), 0)
        repeat(10) { assertEquals(DisplayRecorder.Verdict.SAME, r.offer(frame(1), 1_000L * (it + 1))) }
        assertEquals(DisplayRecorder.Verdict.KEEP, r.offer(frame(2), 11_500))
        assertEquals(2, r.kept)
    }

    @Test
    fun `a private app's frames are counted, never kept`() {
        val r = DisplayRecorder()
        assertEquals(DisplayRecorder.Verdict.PRIVATE, r.offer(frame(1), 0, privateApp = true))
        assertEquals(0, r.kept)
        assertEquals(1, r.skippedPrivate)
    }

    @Test
    fun `the cap stops the recording and says so`() {
        val r = DisplayRecorder(minIntervalMs = 0, maxFrames = 2)
        r.offer(frame(1), 0)
        r.offer(frame(2), 1)
        assertEquals(DisplayRecorder.Verdict.FULL, r.offer(frame(3), 2))
        assertTrue(r.truncated)
    }

    @Test
    fun `the duration is how long it ran, not frames times an interval`() {
        val r = DisplayRecorder()
        r.offer(frame(1), 10_000)
        r.offer(frame(1), 17_500)
        assertEquals(7_500, r.durationMs)
    }
}
