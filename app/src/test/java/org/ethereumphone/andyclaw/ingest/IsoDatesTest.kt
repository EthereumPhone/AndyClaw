package org.ethereumphone.andyclaw.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class IsoDatesTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun `a moment keeps the local day it was written in`() {
        val m = IsoDates.parseMoment("2026-09-01T00:30:00+02:00", utc)!!
        assertEquals("2026-09-01", m.localDate)
        assertFalse(m.dateOnly)
    }

    @Test
    fun `a bare date is a day, not a time`() {
        val m = IsoDates.parseMoment("2026-09-01", ZoneId.of("Europe/Berlin"))!!
        assertTrue(m.dateOnly)
        assertEquals("2026-09-01", m.localDate)
    }

    @Test
    fun `offsets without a colon and a space for the T both parse`() {
        assertEquals(1_788_248_400_000L, IsoDates.parseIso("2026-09-01T09:40:00+0200", utc))
        assertEquals(1_788_248_400_000L, IsoDates.parseIso("2026-09-01 09:40:00+02:00", utc))
    }

    @Test
    fun `iCal zone names come in three spellings`() {
        assertEquals(ZoneId.of("Europe/Berlin"), IsoDates.zoneFor("Europe/Berlin"))
        assertEquals(ZoneId.of("Europe/Berlin"), IsoDates.zoneFor("W. Europe Standard Time"))
        assertEquals(ZoneOffset.ofHours(1), IsoDates.zoneFor("(UTC+01:00) Amsterdam, Berlin, Bern, Rome"))
        assertNull(IsoDates.zoneFor("Mars/Olympus_Mons"))
    }
}
