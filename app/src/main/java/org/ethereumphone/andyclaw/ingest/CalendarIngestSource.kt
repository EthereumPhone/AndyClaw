package org.ethereumphone.andyclaw.ingest

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
) : CalendarSource {

    override suspend fun fetch(fromMs: Long, toMs: Long, zone: ZoneId): List<CalendarEvent> =
        fetchCalendar(fromMs, toMs, "primary", DEFAULT_MAX_RESULTS, zone)

    suspend fun fetchCalendar(
        fromMs: Long,
        toMs: Long,
        calendarId: String,
        maxResults: Int,
        zone: ZoneId,
    ): List<CalendarEvent> = withContext(Dispatchers.IO) {
        val token = try {
            getAccessToken()
        } catch (e: Exception) {
            Log.i(TAG, "no Google access token — skipping calendar ingest: ${e.message}")
            return@withContext emptyList()
        }

        val url = "$BASE_URL/calendars/${java.net.URLEncoder.encode(calendarId, "UTF-8")}/events" +
            "?timeMin=${java.net.URLEncoder.encode(Instant.ofEpochMilli(fromMs).toString(), "UTF-8")}" +
            "&timeMax=${java.net.URLEncoder.encode(Instant.ofEpochMilli(toMs).toString(), "UTF-8")}" +
            "&maxResults=${maxResults.coerceIn(1, HARD_MAX_RESULTS)}" +
            // Expand recurrence server-side. Doing it here would be a second implementation
            // of RRULE, and two answers to "when is this" is worse than one.
            "&singleEvents=true&orderBy=startTime"

        val body = try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Calendar ${response.code}")
                    null
                } else {
                    raw
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Calendar request failed: ${e.message}")
            null
        } ?: return@withContext emptyList()

        GoogleCalendarEventParser.parse(body, zone)
    }

    companion object {
        private const val TAG = "CalendarIngest"
        private const val BASE_URL = "https://www.googleapis.com/calendar/v3"
        private const val DEFAULT_MAX_RESULTS = 50
        private const val HARD_MAX_RESULTS = 250
    }
}
