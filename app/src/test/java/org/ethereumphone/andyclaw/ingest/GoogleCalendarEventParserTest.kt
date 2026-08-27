package org.ethereumphone.andyclaw.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** The Calendar v3 `events.list` shape, parsed as data rather than re-read as prose. */
class GoogleCalendarEventParserTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun `events parse with their times`() {
        val body = """
            {
              "items": [
                {
                  "id": "abc",
                  "iCalUID": "abc@google.com",
                  "summary": "Design review",
                  "location": "Room 4",
                  "start": { "dateTime": "2026-09-01T08:30:00Z" },
                  "end": { "dateTime": "2026-09-01T09:30:00Z" }
                }
              ]
            }
        """.trimIndent()

        val event = GoogleCalendarEventParser.parse(body, utc).single()
        assertEquals("abc@google.com", event.uid)
        assertEquals("Design review", event.summary)
        assertEquals("Room 4", event.location)
        assertEquals(1_788_251_400_000L, event.startMs)
        assertEquals(1_788_255_000_000L, event.endMs)
    }

    @Test
    fun `the iCalUID is preferred over the calendar-local id`() {
        // The same meeting invited to two accounts is one card, not two.
        val body = """
            {"items":[{"id":"local-1","iCalUID":"shared@google.com","summary":"Sync",
              "start":{"dateTime":"2026-09-01T08:30:00Z"}}]}
        """.trimIndent()
        assertEquals("calendar:shared@google.com", GoogleCalendarEventParser.parse(body, utc).single().sourceKey)
    }

    @Test
    fun `an all-day event is marked as one`() {
        val body = """
            {"items":[{"id":"d","summary":"Holiday","start":{"date":"2026-09-01"},"end":{"date":"2026-09-02"}}]}
        """.trimIndent()
        val event = GoogleCalendarEventParser.parse(body, utc).single()
        assertTrue(event.allDay)
        assertEquals(1_788_220_800_000L, event.startMs)
    }

    @Test
    fun `an event's own timeZone is used for a bare local time`() {
        val body = """
            {"items":[{"id":"tz","summary":"Standup",
              "start":{"dateTime":"2026-09-01T10:30:00","timeZone":"Europe/Berlin"}}]}
        """.trimIndent()
        assertEquals(1_788_251_400_000L, GoogleCalendarEventParser.parse(body, utc).single().startMs)
    }

    @Test
    fun `a cancelled event is not upcoming context`() {
        val body = """
            {"items":[{"id":"x","summary":"Gone","status":"cancelled",
              "start":{"dateTime":"2026-09-01T08:30:00Z"}}]}
        """.trimIndent()
        assertTrue(GoogleCalendarEventParser.parse(body, utc).isEmpty())
    }

    @Test
    fun `an event with no start is dropped`() {
        val body = """{"items":[{"id":"y","summary":"Someday"}]}"""
        assertTrue(GoogleCalendarEventParser.parse(body, utc).isEmpty())
    }

    @Test
    fun `a bare array is accepted too`() {
        val body = """[{"id":"z","summary":"Solo","start":{"dateTime":"2026-09-01T08:30:00Z"}}]"""
        assertEquals(1, GoogleCalendarEventParser.parse(body, utc).size)
    }

    @Test
    fun `junk parses to nothing rather than throwing`() {
        assertTrue(GoogleCalendarEventParser.parse("{not json", utc).isEmpty())
        assertTrue(GoogleCalendarEventParser.parse("", utc).isEmpty())
        assertTrue(GoogleCalendarEventParser.parse("""{"error":{"code":403}}""", utc).isEmpty())
    }
}
