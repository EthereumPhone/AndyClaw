package org.ethereumphone.andyclaw.ingest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.ZoneId

/**
 * Google Calendar's `events.list` response.
 *
 * The plan asks for "iCal from `gcal_list_events`", and the honest reading is both halves:
 * [ICalParser] handles a real `.ics` — which is what arrives attached to a mail — and this
 * handles what the Calendar v3 API actually returns, which is JSON carrying the same fields.
 * Either way the parse is deterministic and the model never sees the raw event.
 *
 * `iCalUID` is preferred over `id` as the identity, because it is the one that survives an
 * event being copied between calendars — the same meeting invited to two accounts is one
 * card, not two.
 */
object GoogleCalendarEventParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String, zone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent> = parsePage(body, zone).events

    /** One page of `events.list`, and the token for the next one if there is more. */
    data class Page(val events: List<CalendarEvent>, val nextPageToken: String?)

    fun parsePage(body: String, zone: ZoneId = ZoneId.systemDefault()): Page {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return Page(emptyList(), null)
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> root["items"] as? JsonArray ?: return Page(emptyList(), null)
            else -> return Page(emptyList(), null)
        }
        val next = (root as? JsonObject)?.str("nextPageToken")
        return Page(items.mapNotNull { el -> (el as? JsonObject)?.let { toEvent(it, zone) } }, next)
    }

    private fun toEvent(node: JsonObject, zone: ZoneId): CalendarEvent? {
        val start = node["start"] as? JsonObject
        val end = node["end"] as? JsonObject
        val original = node["originalStartTime"] as? JsonObject
        val startValue = start?.str("dateTime") ?: start?.str("date")
        val endValue = end?.str("dateTime") ?: end?.str("date")
        val originalMs = IsoDates.parseIso(original?.str("dateTime") ?: original?.str("date"), timeZoneOf(original, zone))

        // A cancelled event, or one the user declined, is the absence of context. It still
        // comes back as a tombstone, so a card made for it earlier goes away instead of staying.
        val declined = (node["attendees"] as? JsonArray).orEmpty().any { a ->
            val attendee = a as? JsonObject
            attendee?.get("self")?.let { (it as? JsonPrimitive)?.contentOrNull } == "true" &&
                attendee.str("responseStatus") == "declined"
        }
        val event = CalendarEvent(
            uid = node.str("iCalUID") ?: node.str("id"),
            summary = node.str("summary"),
            location = node.str("location"),
            description = node.str("description"),
            startMs = IsoDates.parseIso(startValue, timeZoneOf(start, zone)) ?: originalMs,
            endMs = IsoDates.parseIso(endValue, timeZoneOf(end, zone)),
            allDay = start?.str("date") != null,
            cancelled = node.str("status") == "cancelled" || declined,
            // One occurrence of a repeating event: the same iCalUID as every other occurrence.
            occurrenceMs = originalMs.takeIf { node.str("recurringEventId") != null },
        )
        return event.takeIf { it.startMs != null }
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    /** The event's own `timeZone`, when it names one — an all-day date has no offset of its own. */
    private fun timeZoneOf(node: JsonObject?, fallback: ZoneId): ZoneId =
        node?.str("timeZone")?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallback

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
