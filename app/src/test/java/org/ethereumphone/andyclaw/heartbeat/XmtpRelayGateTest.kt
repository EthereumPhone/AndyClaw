package org.ethereumphone.andyclaw.heartbeat

import org.ethereumphone.andyclaw.heartbeat.XmtpRelayGate.Admission
import org.junit.Assert.assertEquals
import org.junit.Test

class XmtpRelayGateTest {

    private var now = 1_000_000L
    private fun gate(maxQueued: Int = 5) = XmtpRelayGate(maxQueued = maxQueued, duplicateWindowMs = 30_000L, clock = { now })

    @Test
    fun `a second message while one is running is queued, not dropped`() {
        val g = gate()
        assertEquals(Admission.ADMITTED, g.admit("0xA", "hello"))
        assertEquals(Admission.ADMITTED, g.admit("0xB", "hi there"))
        assertEquals(Admission.ADMITTED, g.admit("0xA", "second message"))
    }

    @Test
    fun `the same message relayed twice is a duplicate, case-insensitively on the sender`() {
        val g = gate()
        assertEquals(Admission.ADMITTED, g.admit("0xAbC", "hello"))
        assertEquals(Admission.DUPLICATE, g.admit("0xabc", "hello"))
    }

    @Test
    fun `the same text after the window is a new message`() {
        val g = gate()
        assertEquals(Admission.ADMITTED, g.admit("0xA", "hello"))
        g.release()
        now += 31_000L
        assertEquals(Admission.ADMITTED, g.admit("0xA", "hello"))
    }

    @Test
    fun `the queue is bounded and release makes room`() {
        val g = gate(maxQueued = 2)
        assertEquals(Admission.ADMITTED, g.admit("0xA", "1"))
        assertEquals(Admission.ADMITTED, g.admit("0xA", "2"))
        assertEquals(Admission.QUEUE_FULL, g.admit("0xA", "3"))
        g.release()
        assertEquals(Admission.ADMITTED, g.admit("0xA", "3"))
    }

    @Test
    fun `a message refused for a full queue can be admitted later, not as a duplicate`() {
        val g = gate(maxQueued = 1)
        assertEquals(Admission.ADMITTED, g.admit("0xA", "1"))
        assertEquals(Admission.QUEUE_FULL, g.admit("0xB", "x"))
        g.release()
        assertEquals(Admission.ADMITTED, g.admit("0xB", "x"))
    }

    @Test
    fun `release never goes below zero`() {
        val g = gate(maxQueued = 1)
        g.release(); g.release()
        assertEquals(Admission.ADMITTED, g.admit("0xA", "1"))
        assertEquals(Admission.QUEUE_FULL, g.admit("0xA", "2"))
    }

    @Test
    fun `a clock set back does not strand an entry as a duplicate`() {
        val g = gate()
        assertEquals(Admission.ADMITTED, g.admit("0xA", "hello"))
        g.release()
        now -= 60_000L
        assertEquals(Admission.ADMITTED, g.admit("0xA", "hello"))
    }
}
