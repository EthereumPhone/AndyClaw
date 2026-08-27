package org.ethereumphone.andyclaw.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How often an event actually turns into a round trip to Gmail. */
class AmbientTriggerPolicyTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    @Test
    fun `a mail app's notification is a mail signal`() {
        assertEquals(AmbientSignal.MAIL_NOTIFICATION, AmbientTriggerPolicy.signalFor("com.google.android.gm"))
        assertEquals(AmbientSignal.CALENDAR_NOTIFICATION, AmbientTriggerPolicy.signalFor("com.google.android.calendar"))
    }

    @Test
    fun `every other app is not a signal at all`() {
        assertNull(AmbientTriggerPolicy.signalFor("com.whatsapp"))
        assertNull(AmbientTriggerPolicy.signalFor("org.ethereumphone.andyclaw"))
    }

    @Test
    fun `the first signal of any kind gets through`() {
        for (signal in AmbientSignal.entries) {
            assertTrue(signal.name, AmbientTriggerPolicy.shouldIngest(signal, lastIngestMs = 0L, nowMs = now))
        }
    }

    @Test
    fun `a burst of mail notifications is one ingest`() {
        // A phone posts notifications in bursts, and an ingest per notification is a
        // network round trip per mail on a battery.
        assertFalse(AmbientTriggerPolicy.shouldIngest(AmbientSignal.MAIL_NOTIFICATION, now, now + 5_000))
        assertTrue(AmbientTriggerPolicy.shouldIngest(AmbientSignal.MAIL_NOTIFICATION, now, now + 3 * minute))
    }

    @Test
    fun `a weak signal is throttled harder than a strong one`() {
        // A mail notification means something new exists. An unlock means the user is
        // here, which is not the same claim.
        assertTrue(
            AmbientTriggerPolicy.cooldownMs(AmbientSignal.USER_PRESENT) >
                AmbientTriggerPolicy.cooldownMs(AmbientSignal.MAIL_NOTIFICATION)
        )
        assertTrue(
            AmbientTriggerPolicy.cooldownMs(AmbientSignal.SCHEDULED) >
                AmbientTriggerPolicy.cooldownMs(AmbientSignal.CONNECTIVITY)
        )
    }

    @Test
    fun `the cooldown counts any ingest, not just one by the same signal`() {
        // An unlock two seconds after a mail notification has nothing new to fetch.
        assertFalse(AmbientTriggerPolicy.shouldIngest(AmbientSignal.USER_PRESENT, now, now + 2_000))
    }

    @Test
    fun `asking directly is never throttled`() {
        assertTrue(AmbientTriggerPolicy.shouldIngest(AmbientSignal.MANUAL, now, now))
    }

    @Test
    fun `the scheduled sweep really is a backstop`() {
        // Hours, not minutes. If this were short it would be the loop again.
        assertTrue(AmbientTriggerPolicy.cooldownMs(AmbientSignal.SCHEDULED) >= 60 * minute)
    }
}
