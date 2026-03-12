package org.ethereumphone.andyclaw.skills.builtin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import java.util.concurrent.TimeUnit

class GoogleCalendarSkill(
    private val getAccessToken: suspend () -> String,
) : AndyClawSkill {

    companion object {
        private const val BASE_URL = "https://www.googleapis.com/calendar/v3"
        private val JSON_TYPE = "application/json".toMediaType()
    }

    override val id = "google_calendar"
    override val name = "Google Calendar"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override val baseManifest = SkillManifest(
        description = "List and create events on Google Calendar. Use this to check the user's schedule or create new calendar events.",
        tools = listOf(
            ToolDefinition(
                name = "gcal_list_events",
                description = "List upcoming events from Google Calendar.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "calendar_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Calendar ID (default: 'primary')"),
                        )),
                        "time_min" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Start of time range in RFC3339 format (e.g. '2024-01-15T00:00:00Z'). Defaults to now."),
                        )),
                        "time_max" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("End of time range in RFC3339 format. Defaults to 7 days from now."),
                        )),
                        "max_results" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Maximum number of events to return (default: 10, max: 50)"),
                        )),
                    )),
                )),
            ),
            ToolDefinition(
                name = "gcal_create_event",
                description = "Create a new event on Google Calendar.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "summary" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Event title/summary"),
                        )),
                        "start" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Start time in RFC3339 format (e.g. '2024-01-15T10:00:00-05:00')"),
                        )),
                        "end" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("End time in RFC3339 format"),
                        )),
                        "description" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Event description (optional)"),
                        )),
                        "location" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Event location (optional)"),
                        )),
                        "attendees" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Comma-separated email addresses of attendees (optional)"),
                        )),
                        "calendar_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Calendar ID (default: 'primary')"),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("summary"),
                        JsonPrimitive("start"),
                        JsonPrimitive("end"),
                    )),
                )),
                requiresApproval = true,
            ),
        ),
        permissions = listOf(android.Manifest.permission.INTERNET),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return try {
            when (tool) {
                "gcal_list_events" -> listEvents(params)
                "gcal_create_event" -> createEvent(params)
                else -> SkillResult.Error("Unknown tool: $tool")
            }
        } catch (e: Exception) {
            SkillResult.Error("Google Calendar error: ${e.message}")
        }
    }

    private suspend fun listEvents(params: JsonObject): SkillResult = withContext(Dispatchers.IO) {
        val calendarId = params["calendar_id"]?.jsonPrimitive?.contentOrNull ?: "primary"
        val maxResults = (params["max_results"]?.jsonPrimitive?.let {
            it.contentOrNull?.toIntOrNull()
        } ?: 10).coerceIn(1, 50)

        val now = java.time.Instant.now()
        val timeMin = params["time_min"]?.jsonPrimitive?.contentOrNull
            ?: now.toString()
        val timeMax = params["time_max"]?.jsonPrimitive?.contentOrNull
            ?: now.plus(java.time.Duration.ofDays(7)).toString()

        val token = getAccessToken()
        val encodedCalId = java.net.URLEncoder.encode(calendarId, "UTF-8")
        val url = "$BASE_URL/calendars/$encodedCalId/events" +
            "?timeMin=${java.net.URLEncoder.encode(timeMin, "UTF-8")}" +
            "&timeMax=${java.net.URLEncoder.encode(timeMax, "UTF-8")}" +
            "&maxResults=$maxResults" +
            "&singleEvents=true" +
            "&orderBy=startTime"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            return@withContext SkillResult.Error("Failed to list events (HTTP ${response.code}): $responseBody")
        }

        SkillResult.Success(responseBody)
    }

    private suspend fun createEvent(params: JsonObject): SkillResult = withContext(Dispatchers.IO) {
        val summary = params["summary"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: summary")
        val start = params["start"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: start")
        val end = params["end"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: end")
        val description = params["description"]?.jsonPrimitive?.contentOrNull
        val location = params["location"]?.jsonPrimitive?.contentOrNull
        val attendees = params["attendees"]?.jsonPrimitive?.contentOrNull
        val calendarId = params["calendar_id"]?.jsonPrimitive?.contentOrNull ?: "primary"

        val token = getAccessToken()

        val eventJson = buildJsonObject {
            put("summary", summary)
            put("start", buildJsonObject { put("dateTime", start) })
            put("end", buildJsonObject { put("dateTime", end) })
            if (!description.isNullOrBlank()) put("description", description)
            if (!location.isNullOrBlank()) put("location", location)
            if (!attendees.isNullOrBlank()) {
                val attendeeList = attendees.split(",").map { it.trim() }.filter { it.isNotBlank() }
                put("attendees", JsonArray(attendeeList.map { email ->
                    JsonObject(mapOf("email" to JsonPrimitive(email)))
                }))
            }
        }

        val encodedCalId = java.net.URLEncoder.encode(calendarId, "UTF-8")
        val request = Request.Builder()
            .url("$BASE_URL/calendars/$encodedCalId/events")
            .addHeader("Authorization", "Bearer $token")
            .post(eventJson.toString().toRequestBody(JSON_TYPE))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            return@withContext SkillResult.Error("Failed to create event (HTTP ${response.code}): $responseBody")
        }

        SkillResult.Success(buildJsonObject {
            put("created", true)
            put("summary", summary)
            put("response", responseBody)
        }.toString())
    }
}
