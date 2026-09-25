package org.ethereumphone.andyclaw.ingest

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The calendars on the phone itself, through `CalendarContract.Instances`.
 *
 * Without it, a phone with no Google account connected never showed a single card, although
 * `READ_CALENDAR` was already declared: every calendar the user has synced — Exchange, CalDAV,
 * a local one — was invisible. Recurrence is already expanded by the provider, one row per
 * occurrence, so there is no second implementation of RRULE here either.
 *
 * Calendars synced from a Google account are skipped while the Google API source reads that
 * account directly ([skipGoogleAccounts]); reading both would only duplicate its events.
 */
class DeviceCalendarSource(
    private val context: Context,
    private val skipGoogleAccounts: () -> Boolean = { false },
) : CalendarSource {

    override suspend fun fetch(fromMs: Long, toMs: Long, zone: ZoneId): SourceResult<List<CalendarEvent>> =
        withContext(Dispatchers.IO) {
            if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                return@withContext SourceResult.Unavailable(IngestProblem.NO_PERMISSION)
            }
            try {
                val calendars = visibleCalendars()
                if (calendars.isEmpty()) return@withContext SourceResult.Fetched(emptyList())
                SourceResult.Fetched(instances(fromMs, toMs, zone, calendars))
            } catch (e: SecurityException) {
                SourceResult.Unavailable(IngestProblem.NO_PERMISSION, e.message)
            } catch (e: Exception) {
                org.ethereumphone.andyclaw.ExecutionEngine.rethrowIfCancelled(e)
                Log.w(TAG, "calendar query failed: ${e.message}")
                SourceResult.Unavailable(IngestProblem.FAILED, e.message)
            }
        }

    /** The calendars the user shows, less the Google ones the API reads when it is connected. */
    private fun visibleCalendars(): Set<Long> {
        val skipGoogle = skipGoogleAccounts()
        val out = HashSet<Long>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_TYPE),
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (skipGoogle && c.getString(1) == GOOGLE_ACCOUNT_TYPE) continue
                out += c.getLong(0)
            }
        }
        return out
    }

    private fun instances(fromMs: Long, toMs: Long, zone: ZoneId, calendars: Set<Long>): List<CalendarEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.UID_2445,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.ORIGINAL_ID,
            CalendarContract.Instances.ORIGINAL_INSTANCE_TIME,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.RDATE,
            CalendarContract.Instances.CALENDAR_ID,
        )
        val out = mutableListOf<CalendarEvent>()
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && out.size < MAX_INSTANCES) {
                if (c.getLong(13) !in calendars) continue
                val allDay = c.getInt(5) == 1
                val begin = c.getLong(1)
                val end = if (c.isNull(2)) null else c.getLong(2)
                // All-day occurrences are stored at UTC midnight; their day is the user's day.
                val start = if (allDay) utcDayStart(begin, zone) else begin
                val finish = end?.let { if (allDay) utcDayStart(it, zone) else it }
                val repeating = !c.isNull(11) || !c.isNull(12)
                val exceptionOf = if (c.isNull(9)) null else c.getLong(9)
                val occurrence = when {
                    exceptionOf != null && !c.isNull(10) -> c.getLong(10).let { if (allDay) utcDayStart(it, zone) else it }
                    repeating -> start
                    else -> null
                }
                val uid = c.getString(6)?.takeIf { it.isNotBlank() }
                    ?: "device:${exceptionOf ?: c.getLong(0)}"
                out += CalendarEvent(
                    uid = uid,
                    summary = c.getString(3)?.takeIf { it.isNotBlank() },
                    location = c.getString(4)?.takeIf { it.isNotBlank() },
                    startMs = start,
                    endMs = finish,
                    allDay = allDay,
                    cancelled = c.getInt(7) == CalendarContract.Events.STATUS_CANCELED ||
                        c.getInt(8) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
                    occurrenceMs = occurrence,
                )
            }
        }
        return out
    }

    private fun utcDayStart(utcMidnight: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(utcMidnight).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    companion object {
        private const val TAG = "DeviceCalendar"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val MAX_INSTANCES = 500
    }
}
