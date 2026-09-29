package org.ethereumphone.andyclaw.heartbeat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SET-02: the OS ticks hourly; a longer interval is honoured by skipping ticks. */
class HeartbeatTickGateTest {

    private val minute = 60_000L
    private val now = 1_790_594_663_000L

    @Test
    fun `a day's interval skips the hourly ticks until the day is over`() {
        assertFalse(HeartbeatTickGate.shouldRun(now, now - 61 * minute, 1440))
        assertFalse(HeartbeatTickGate.shouldRun(now, now - 23 * 60 * minute, 1440))
        assertTrue(HeartbeatTickGate.shouldRun(now, now - 1441 * minute, 1440))
    }

    @Test
    fun `a tick a few minutes early still counts`() {
        assertTrue(HeartbeatTickGate.shouldRun(now, now - 1435 * minute, 1440))
        assertTrue(HeartbeatTickGate.shouldRun(now, now - 115 * minute, 120))
        assertFalse(HeartbeatTickGate.shouldRun(now, now - 60 * minute, 120))
    }

    @Test
    fun `up to an hour every tick runs, as before`() {
        assertTrue(HeartbeatTickGate.shouldRun(now, now - 1 * minute, 60))
        assertTrue(HeartbeatTickGate.shouldRun(now, now - 1 * minute, 30))
        assertTrue(HeartbeatTickGate.shouldRun(now, now - 1 * minute, 5))
    }

    @Test
    fun `a first run, or a clock set back, runs`() {
        assertTrue(HeartbeatTickGate.shouldRun(now, 0L, 1440))
        assertTrue(HeartbeatTickGate.shouldRun(now, now + 3 * 60 * minute, 1440))
    }

    @Test
    fun `a switched-off heartbeat never runs`() {
        assertFalse(HeartbeatTickGate.shouldRun(now, 0L, -1))
        assertFalse(HeartbeatTickGate.shouldRun(now, 0L, 0))
    }
}
