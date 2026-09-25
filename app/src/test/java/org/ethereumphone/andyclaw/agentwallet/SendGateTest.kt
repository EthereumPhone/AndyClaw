package org.ethereumphone.andyclaw.agentwallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One money-moving operation at a time, however fast the taps come. */
class SendGateTest {

    @Test
    fun `a second send is refused until the first is done`() {
        val gate = SendGate()
        assertTrue(gate.tryEnter())
        assertTrue(gate.isBusy)
        assertFalse(gate.tryEnter())
        gate.exit()
        assertFalse(gate.isBusy)
        assertTrue(gate.tryEnter())
    }

    @Test
    fun `of many simultaneous taps exactly one gets through`() {
        val gate = SendGate()
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val entered = AtomicInteger()
        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    if (gate.tryEnter()) entered.incrementAndGet()
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, entered.get())
    }
}
