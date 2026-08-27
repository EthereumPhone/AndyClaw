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

    fun parse(body: String, zone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> root["items"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return items.mapNotNull { el -> (el as? JsonObject)?.let { toEvent(it, zone) } }
    }

    private fun toEvent(node: JsonObject, zone: ZoneId): CalendarEvent? {
        val start = node["start"] as? JsonObject
        val end = node["end"] as? JsonObject
        val startValue = start?.str("dateTime") ?: start?.str("date")
        val endValue = end?.str("dateTime") ?: end?.str("date")

        val event = CalendarEvent(
            uid = node.str("iCalUID") ?: node.str("id"),
            summary = node.str("summary"),
            location = node.str("location"),
            description = node.str("description"),
            startMs = IsoDates.parseIso(startValue, timeZoneOf(start, zone)),
            endMs = IsoDates.parseIso(endValue, timeZoneOf(end, zone)),
            allDay = start?.str("date") != null,
        )
        // A cancelled event is not upcoming context; it is the absence of it.
        if (node.str("status") == "cancelled") return null
        return event.takeIf { it.startMs != null }
    }

    /** The event's own `timeZone`, when it names one — an all-day date has no offset of its own. */
    private fun timeZoneOf(node: JsonObject?, fallback: ZoneId): ZoneId =
        node?.str("timeZone")?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallback

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
