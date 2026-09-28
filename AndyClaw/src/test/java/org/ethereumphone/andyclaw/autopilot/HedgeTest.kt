package org.ethereumphone.andyclaw.autopilot

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HedgeTest {

    @Test
    fun `a fast answer never sends the second request`() = runTest {
        val sent = mutableListOf<Int>()
        val r = Hedge.firstSuccess(800) { i -> sent += i; delay(300); "ok$i" }
        assertEquals("ok0", r)
        assertEquals(listOf(0), sent)
        assertEquals(300, currentTime)
    }

    @Test
    fun `a hung request is overtaken by the hedge`() = runTest {
        // The 2026-09-28 shape: one call hangs to the gateway's 504, the other answers in 360 ms.
        val r = Hedge.firstSuccess(800) { i -> if (i == 0) awaitCancellation() else { delay(360); "ok$i" } }
        assertEquals("ok1", r)
        assertEquals(1160, currentTime)
    }

    @Test
    fun `an early failure starts the other attempt at once`() = runTest {
        val r = Hedge.firstSuccess(800) { i -> if (i == 0) { delay(50); throw IOException("reset") } else { delay(300); "ok$i" } }
        assertEquals("ok1", r)
        assertEquals(350, currentTime)
    }

    @Test
    fun `both failing reports the failure`() = runTest {
        val e = runCatching { Hedge.firstSuccess<String>(800) { throw IOException("down $it") } }.exceptionOrNull()
        assertTrue(e is IOException)
    }

    @Test
    fun `a fatal failure is not asked again`() = runTest {
        val sent = mutableListOf<Int>()
        val e = runCatching {
            Hedge.firstSuccess<String>(800, fatal = { it is JevUnavailableException }) { i ->
                sent += i; throw JevUnavailableException("HTTP 403", 403)
            }
        }.exceptionOrNull()
        assertTrue(e is JevUnavailableException)
        assertEquals(listOf(0), sent)
    }
}
