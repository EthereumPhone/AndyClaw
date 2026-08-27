package org.ethereumphone.andyclaw.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * RFC 5545, including the three details that are easy to get subtly wrong and that nobody
 * notices until a meeting shows up an hour off.
 */
class ICalParserTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun `a plain event parses`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:abc-123@example.com
            SUMMARY:Design review
            LOCATION:Room 4
            DTSTART:20260901T083000Z
            DTEND:20260901T093000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event = ICalParser.parse(ics, utc).single()
        assertEquals("abc-123@example.com", event.uid)
        assertEquals("Design review", event.summary)
        assertEquals("Room 4", event.location)
        assertEquals(1_788_251_400_000L, event.startMs)
        assertEquals(1_788_255_000_000L, event.endMs)
        assertTrue(!event.allDay)
    }

    @Test
    fun `a folded line is rejoined before anything else happens`() {
        // Folding at 75 octets is not optional in the format, and a parser that misses it
        // returns a truncated summary rather than failing loudly.
        // Built by hand rather than with trimIndent, because exactly one leading space is
        // the fold marker and any other is part of the value — an indented fixture cannot
        // say which is which.
        val ics = listOf(
            "BEGIN:VEVENT",
            "UID:fold-1",
            "SUMMARY:A very long meeting title that the calendar server fol",
            " ded across two lines",
            "DTSTART:20260901T083000Z",
            "END:VEVENT",
        ).joinToString("\r\n")

        val event = ICalParser.parse(ics, utc).single()
        assertEquals("A very long meeting title that the calendar server folded across two lines", event.summary)
    }

    @Test
    fun `a TZID parameter decides the zone`() {
        val ics = """
            BEGIN:VEVENT
            UID:tz-1
            SUMMARY:Standup
            DTSTART;TZID=Europe/Berlin:20260901T103000
            END:VEVENT
        """.trimIndent()

        // 10:30 in Berlin is 08:30 UTC. Dropping the parameter would silently read it as
        // 10:30 wherever the device happens to be.
        assertEquals(1_788_251_400_000L, ICalParser.parse(ics, utc).single().startMs)
    }

    @Test
    fun `a Z suffix wins over a TZID`() {
        val ics = """
            BEGIN:VEVENT
            UID:tz-2
            DTSTART;TZID=Asia/Tokyo:20260901T083000Z
            SUMMARY:UTC wins
            END:VEVENT
        """.trimIndent()
        assertEquals(1_788_251_400_000L, ICalParser.parse(ics, utc).single().startMs)
    }

    @Test
    fun `a floating time resolves against the device`() {
        val ics = """
            BEGIN:VEVENT
            UID:tz-3
            DTSTART:20260901T103000
            SUMMARY:Floating
            END:VEVENT
        """.trimIndent()
        val berlin = ICalParser.parse(ics, ZoneId.of("Europe/Berlin")).single().startMs!!
        val tokyo = ICalParser.parse(ics, ZoneId.of("Asia/Tokyo")).single().startMs!!
        assertTrue(berlin > tokyo)
    }

    @Test
    fun `an all-day event is marked as one`() {
        val ics = """
            BEGIN:VEVENT
            UID:day-1
            SUMMARY:Public holiday
            DTSTART;VALUE=DATE:20260901
            END:VEVENT
        """.trimIndent()

        val event = ICalParser.parse(ics, utc).single()
        assertTrue(event.allDay)
        assertNotNull(event.startMs)
    }

    @Test
    fun `escapes inside text values are undone`() {
        val ics = """
            BEGIN:VEVENT
            UID:esc-1
            SUMMARY:Lunch\, then a walk
            LOCATION:5th Ave\; Suite 3
            DESCRIPTION:First line\nSecond line
            DTSTART:20260901T083000Z
            END:VEVENT
        """.trimIndent()

        val event = ICalParser.parse(ics, utc).single()
        assertEquals("Lunch, then a walk", event.summary)
        assertEquals("5th Ave; Suite 3", event.location)
        assertEquals("First line\nSecond line", event.description)
    }

    @Test
    fun `a colon inside a quoted parameter does not end the name`() {
        val ics = """
            BEGIN:VEVENT
            UID:q-1
            SUMMARY;X-NOTE="a:b":Quoted
            DTSTART:20260901T083000Z
            END:VEVENT
        """.trimIndent()
        assertEquals("Quoted", ICalParser.parse(ics, utc).single().summary)
    }

    @Test
    fun `several events all come out`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:a
            SUMMARY:One
            DTSTART:20260901T080000Z
            END:VEVENT
            BEGIN:VEVENT
            UID:b
            SUMMARY:Two
            DTSTART:20260901T100000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        assertEquals(listOf("One", "Two"), ICalParser.parse(ics, utc).map { it.summary })
    }

    @Test
    fun `a todo is not an event`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VTODO
            UID:t-1
            SUMMARY:Buy milk
            DUE:20260901T080000Z
            END:VTODO
            END:VCALENDAR
        """.trimIndent()
        assertTrue(ICalParser.parse(ics, utc).isEmpty())
    }

    @Test
    fun `text that is not iCal yields nothing`() {
        assertTrue(ICalParser.parse("Dear customer,", utc).isEmpty())
        assertTrue(ICalParser.parse("", utc).isEmpty())
        assertTrue(!ICalParser.looksLikeICal("hello"))
        assertTrue(ICalParser.looksLikeICal("BEGIN:VCALENDAR"))
    }

    @Test
    fun `the dedupe key follows the uid`() {
        val ics = """
            BEGIN:VEVENT
            UID:stable-uid
            SUMMARY:Anything
            DTSTART:20260901T083000Z
            END:VEVENT
        """.trimIndent()
        assertEquals("calendar:stable-uid", ICalParser.parse(ics, utc).single().sourceKey)
    }
}
