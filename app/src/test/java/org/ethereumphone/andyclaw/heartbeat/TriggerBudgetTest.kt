package org.ethereumphone.andyclaw.heartbeat

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
}
