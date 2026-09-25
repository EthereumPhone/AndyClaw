package org.ethereumphone.andyclaw.heartbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerBudgetTest {

    private var now = 0L
    private val budget = TriggerBudget(capacity = 3, refillMs = 10 * 60_000L, clock = { now })

    @Test
    fun `a few in a row are fine, a stream is not`() {
        repeat(3) { assertTrue(budget.tryAcquire("xmtp:0xabc")) }
        assertFalse(budget.tryAcquire("xmtp:0xabc"))
    }

    @Test
    fun `each sender has its own budget, and it refills`() {
        repeat(3) { budget.tryAcquire("xmtp:0xabc") }
        assertTrue("another sender is unaffected", budget.tryAcquire("telegram:42"))
        now += 10 * 60_000L
        assertTrue(budget.tryAcquire("xmtp:0xabc"))
        assertFalse(budget.tryAcquire("xmtp:0xabc"))
    }

    @Test
    fun `fresh identities share one ceiling`() {
        val b = TriggerBudget(capacity = 3, refillMs = 10 * 60_000L, totalCapacity = 10, totalRefillMs = 5 * 60_000L, clock = { now })
        var ran = 0
        for (sender in 1..20) if (b.tryAcquire("telegram:$sender")) ran++
        assertEquals("a new chat each time still stops at the ceiling", 10, ran)
    }
}
