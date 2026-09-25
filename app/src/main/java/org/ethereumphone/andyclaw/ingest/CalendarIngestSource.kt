package org.ethereumphone.andyclaw.ingest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.ZoneId

/**
 * The user's own calendar, as typed events.
 *
 * The same split as [GmailIngestSource]: this fetches, [GoogleCalendarEventParser] decides
 * what the bytes mean. `GoogleCalendarSkill.gcal_list_events` already calls this endpoint —
 * it renders the result as prose for a model to read, which is the right thing for a tool
 * and the wrong thing for a card, so the raw JSON is parsed here instead of the summary
 * being re-read.
 */
class CalendarIngestSource(
    private val getAccessToken: suspend () -> String,
    private val client: OkHttpClient = GmailIngestSource.defaultClient(),
    /** Whether a Google account is connected at all; without one there is nothing to fetch. */
    private val isAuthenticated: () -> Boolean = { true },
    /** Drop a cached access token the API has just refused. */
    private val invalidateToken: () -> Unit = {},
) : CalendarSource {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Every calendar the user shows in Google Calendar — not just `primary`, where a shared
     * work calendar or a partner's never appeared — and every page of each, up to a bound.
     * [SourceResult.Fetched.complete] is true only when all of that was read, because it is
     * what licenses deleting rows the calendar no longer has.
     */
    override suspend fun fetch(fromMs: Long, toMs: Long, zone: ZoneId): SourceResult<List<CalendarEvent>> =
        withContext(Dispatchers.IO) {
            if (!isAuthenticated()) return@withContext SourceResult.Unavailable(IngestProblem.NO_ACCOUNT)
            val session = GoogleSession(getAccessToken, invalidateToken, client, TAG)

            val calendars = when (val r = session.get("$BASE_URL/users/me/calendarList?minAccessRole=reader")) {
                is GoogleSession.Response.Ok -> selectedCalendars(r.body).ifEmpty { listOf("primary") }
                is GoogleSession.Response.Unavailable -> return@withContext SourceResult.Unavailable(r.problem, r.detail)
            }

            val events = mutableListOf<CalendarEvent>()
            var complete = true
            for (calendarId in calendars.take(MAX_CALENDARS)) {
                var pageToken: String? = null
                var pages = 0
                do {
                    val url = "$BASE_URL/calendars/${java.net.URLEncoder.encode(calendarId, "UTF-8")}/events" +
                        "?timeMin=${java.net.URLEncoder.encode(Instant.ofEpochMilli(fromMs).toString(), "UTF-8")}" +
                        "&timeMax=${java.net.URLEncoder.encode(Instant.ofEpochMilli(toMs).toString(), "UTF-8")}" +
                        "&maxResults=$PAGE_SIZE" +
                        // Expand recurrence server-side. Doing it here would be a second implementation
                        // of RRULE, and two answers to "when is this" is worse than one.
                        "&singleEvents=true&orderBy=startTime" +
                        (pageToken?.let { "&pageToken=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "")
                    when (val r = session.get(url)) {
                        is GoogleSession.Response.Ok -> {
                            val page = GoogleCalendarEventParser.parsePage(r.body, zone)
                            events += page.events
                            pageToken = page.nextPageToken
                        }
                        is GoogleSession.Response.Unavailable -> {
                            if (events.isEmpty() && calendarId == calendars.first()) {
                                return@withContext SourceResult.Unavailable(r.problem, r.detail)
                            }
                            complete = false
                            pageToken = null
                        }
                    }
                    pages++
                } while (pageToken != null && pages < MAX_PAGES)
                if (pageToken != null) complete = false
            }
            SourceResult.Fetched(events.distinctBy { it.sourceKey }, complete && calendars.size <= MAX_CALENDARS)
        }

    /** The ids of the calendars the user has switched on in Google Calendar. */
    private fun selectedCalendars(body: String): List<String> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        return (root["items"] as? JsonArray).orEmpty().mapNotNull { el ->
            val c = el as? JsonObject ?: return@mapNotNull null
            val selected = (c["selected"] as? JsonPrimitive)?.booleanOrNull ?: (c["primary"] as? JsonPrimitive)?.booleanOrNull ?: false
            val hidden = (c["hidden"] as? JsonPrimitive)?.booleanOrNull ?: false
            (c["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { selected && !hidden }
        }
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    companion object {
        private const val TAG = "CalendarIngest"
        private const val BASE_URL = "https://www.googleapis.com/calendar/v3"
        private const val PAGE_SIZE = 250
        private const val MAX_PAGES = 4
        private const val MAX_CALENDARS = 10
    }
}
