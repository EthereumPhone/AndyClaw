package org.ethereumphone.andyclaw.skills.builtin

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition

class CalendarSkill(private val context: Context) : AndyClawSkill {
    override val id = "calendar"
    override val name = "Calendar"

    override val baseManifest = SkillManifest(
        description = "Read, create, and delete calendar events on the device.",
        tools = listOf(
            ToolDefinition(
                name = "list_events",
                description = "List upcoming calendar events within a time range.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "start_time" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Start time as epoch millis (default: now)"))),
                        "end_time" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("End time as epoch millis (default: 7 days from now)"))),
                        "limit" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Max events to return (default 50)"))),
                    )),
                )),
                requiredPermissions = listOf("android.permission.READ_CALENDAR"),
            ),
            ToolDefinition(
                name = "get_event",
                description = "Get details of a specific calendar event by ID.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "event_id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Event ID"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("event_id"))),
                )),
                requiredPermissions = listOf("android.permission.READ_CALENDAR"),
            ),
            ToolDefinition(
                name = "create_event",
                description = "Create a new calendar event. Prefers Google Calendar if installed, otherwise uses the default calendar.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "title" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Event title"))),
                        "start_time" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Start time as epoch millis"))),
                        "end_time" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("End time as epoch millis"))),
                        "description" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Event description"))),
                        "location" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Event location"))),
                        "calendar_id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Calendar ID (uses default if omitted)"))),
                        "participants" to JsonObject(mapOf(
                            "type" to JsonPrimitive("array"),
                            "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                            "description" to JsonPrimitive("List of participant email addresses to invite"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("start_time"), JsonPrimitive("end_time"))),
                )),
                requiresApproval = true,
                requiredPermissions = listOf("android.permission.WRITE_CALENDAR"),
            ),
            ToolDefinition(
                name = "delete_event",
                description = "Delete a calendar event by ID.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "event_id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Event ID to delete"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("event_id"))),
                )),
                requiresApproval = true,
                requiredPermissions = listOf("android.permission.WRITE_CALENDAR"),
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "list_events" -> listEvents(params)
            "get_event" -> getEvent(params)
            "create_event" -> createEvent(params)
            "delete_event" -> deleteEvent(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun listEvents(params: JsonObject): SkillResult {
        val now = System.currentTimeMillis()
        val startTime = params["start_time"]?.jsonPrimitive?.long ?: now
        val endTime = params["end_time"]?.jsonPrimitive?.long ?: (now + 7L * 24 * 60 * 60 * 1000)
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 50
        return try {
            val events = mutableListOf<JsonObject>()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(startTime.toString())
                .appendPath(endTime.toString())
                .build()
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.DESCRIPTION,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                ),
                null, null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )
            cursor?.use {
                while (it.moveToNext() && events.size < limit) {
                    events.add(buildJsonObject {
                        put("event_id", it.getLong(0).toString())
                        put("title", it.getString(1) ?: "")
                        put("start_time", it.getLong(2))
                        put("end_time", it.getLong(3))
                        put("location", it.getString(4) ?: "")
                        put("description", it.getString(5) ?: "")
                        put("all_day", it.getInt(6) != 0)
                        put("calendar", it.getString(7) ?: "")
                    })
                }
            }
            SkillResult.Success(buildJsonObject {
                put("count", events.size)
                put("events", JsonArray(events))
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to list events: ${e.message}")
        }
    }

    private fun getEvent(params: JsonObject): SkillResult {
        val eventId = params["event_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: event_id")
        return try {
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.EVENT_LOCATION,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.ALL_DAY,
                    CalendarContract.Events.CALENDAR_ID,
                    CalendarContract.Events.ORGANIZER,
                    CalendarContract.Events.RRULE,
                ),
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId),
                null,
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val result = buildJsonObject {
                        put("event_id", it.getLong(0).toString())
                        put("title", it.getString(1) ?: "")
                        put("start_time", it.getLong(2))
                        put("end_time", it.getLong(3))
                        put("location", it.getString(4) ?: "")
                        put("description", it.getString(5) ?: "")
                        put("all_day", it.getInt(6) != 0)
                        put("calendar_id", it.getLong(7).toString())
                        put("organizer", it.getString(8) ?: "")
                        put("recurrence_rule", it.getString(9) ?: "")
                    }
                    return SkillResult.Success(result.toString())
                }
            }
            SkillResult.Error("Event not found: $eventId")
        } catch (e: Exception) {
            SkillResult.Error("Failed to get event: ${e.message}")
        }
    }

    private fun createEvent(params: JsonObject): SkillResult {
        val title = params["title"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: title")
        val startTime = params["start_time"]?.jsonPrimitive?.long
            ?: return SkillResult.Error("Missing required parameter: start_time")
        val endTime = params["end_time"]?.jsonPrimitive?.long
            ?: return SkillResult.Error("Missing required parameter: end_time")
        val description = params["description"]?.jsonPrimitive?.contentOrNull
        val location = params["location"]?.jsonPrimitive?.contentOrNull
        val calendarId = params["calendar_id"]?.jsonPrimitive?.contentOrNull
        val participants = params["participants"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()

        return try {
            val resolvedCalendarId = calendarId?.toLongOrNull() ?: getDefaultCalendarId()
                ?: return SkillResult.Error("No calendar found on device. Create a calendar first.")

            // Look up the owner email of this calendar so we can set ORGANIZER
            val ownerEmail = getCalendarOwnerEmail(resolvedCalendarId)

            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startTime)
                put(CalendarContract.Events.DTEND, endTime)
                put(CalendarContract.Events.CALENDAR_ID, resolvedCalendarId)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                if (description != null) put(CalendarContract.Events.DESCRIPTION, description)
                if (location != null) put(CalendarContract.Events.EVENT_LOCATION, location)
                if (ownerEmail != null) put(CalendarContract.Events.ORGANIZER, ownerEmail)
                if (participants.isNotEmpty()) {
                    put(CalendarContract.Events.HAS_ATTENDEE_DATA, 1)
                    put(CalendarContract.Events.GUESTS_CAN_INVITE_OTHERS, 1)
                    put(CalendarContract.Events.GUESTS_CAN_SEE_GUESTS, 1)
                }
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return SkillResult.Error("Failed to create event")
            val newId = uri.lastPathSegment?.toLongOrNull()
                ?: return SkillResult.Error("Failed to get event ID after creation")

            // Add the organizer as an attendee (required for Google Calendar invitations)
            if (participants.isNotEmpty() && ownerEmail != null) {
                val organizerAttendee = ContentValues().apply {
                    put(CalendarContract.Attendees.EVENT_ID, newId)
                    put(CalendarContract.Attendees.ATTENDEE_EMAIL, ownerEmail)
                    put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ORGANIZER)
                    put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
                    put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED)
                }
                context.contentResolver.insert(CalendarContract.Attendees.CONTENT_URI, organizerAttendee)
            }

            // Add participants as attendees
            for (email in participants) {
                val attendeeValues = ContentValues().apply {
                    put(CalendarContract.Attendees.EVENT_ID, newId)
                    put(CalendarContract.Attendees.ATTENDEE_EMAIL, email)
                    put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
                    put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
                    put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED)
                }
                context.contentResolver.insert(CalendarContract.Attendees.CONTENT_URI, attendeeValues)
            }

            SkillResult.Success(buildJsonObject {
                put("created", true)
                put("event_id", newId.toString())
                put("title", title)
                put("calendar_id", resolvedCalendarId.toString())
                if (ownerEmail != null) put("organizer", ownerEmail)
                if (participants.isNotEmpty()) {
                    put("participants_added", participants.size)
                }
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to create event: ${e.message}")
        }
    }

    private fun getCalendarOwnerEmail(calendarId: Long): String? {
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars.OWNER_ACCOUNT),
            "${CalendarContract.Calendars._ID} = ?",
            arrayOf(calendarId.toString()),
            null,
        )
        return cursor?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    private fun deleteEvent(params: JsonObject): SkillResult {
        val eventId = params["event_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: event_id")
        return try {
            val deleted = context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId),
            )
            if (deleted > 0) {
                SkillResult.Success(buildJsonObject { put("deleted", eventId) }.toString())
            } else {
                SkillResult.Error("Event not found: $eventId")
            }
        } catch (e: Exception) {
            SkillResult.Error("Failed to delete event: ${e.message}")
        }
    }

    /**
     * Prefer Google Calendar if available, otherwise fall back to any visible calendar.
     */
    private fun getDefaultCalendarId(): Long? {
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.IS_PRIMARY,
            ),
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            null,
        )
        var googleCalendarId: Long? = null
        var fallbackId: Long? = null
        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val accountType = it.getString(1) ?: ""
                val isPrimary = it.getInt(2) != 0
                if (accountType == "com.google" && (googleCalendarId == null || isPrimary)) {
                    googleCalendarId = id
                }
                if (fallbackId == null || isPrimary) {
                    fallbackId = id
                }
            }
        }
        return googleCalendarId ?: fallbackId
    }
}
