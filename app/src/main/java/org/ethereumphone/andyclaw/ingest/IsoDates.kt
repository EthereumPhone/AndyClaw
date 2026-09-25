package org.ethereumphone.andyclaw.ingest

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Date parsing for the ingest path, and nowhere else.
 *
 * Every source here writes time differently and all of them are precise: schema.org uses
 * ISO-8601 with an offset, iCal uses a basic format with an optional `Z` or a `TZID`, and a
 * boarding-pass barcode carries a bare Julian day with no year at all. Getting a departure
 * time an hour wrong is the same failure as getting the gate wrong, so each form is parsed
 * as what it is rather than pushed through one forgiving pattern.
 *
 * A local time with no offset is resolved against the device's zone. That is a guess, and
 * it is the right one: a mail that says a flight leaves at 09:40 with no offset means 09:40
 * where the flight is, and the device is usually there or heading there. Where a source does
 * carry an offset it is always used.
 */
object IsoDates {

    private val icalUtc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val icalLocal = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val icalDate = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val isoDay = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** ISO-8601 with or without an offset, or a bare date. Null when it is none of those. */
    fun parseIso(value: String?, zone: ZoneId = ZoneId.systemDefault()): Long? = parseMoment(value, zone)?.epochMs

    /**
     * A parsed time together with what it knew about itself.
     *
     * [localDate] is the day *as the source wrote it*: `2026-09-01T00:30:00+02:00` is on the
     * 1st, whatever that is in UTC. That, not the UTC day, is what a boarding-pass barcode
     * carries, so it is what a flight's dedupe key is built from. [dateOnly] is a bare date,
     * whose [epochMs] is only the start of that day in [zone] and must not be shown as a time.
     */
    data class Moment(val epochMs: Long, val localDate: String, val dateOnly: Boolean)

    fun parseMoment(value: String?, zone: ZoneId = ZoneId.systemDefault()): Moment? {
        val raw = normalise(value?.trim().orEmpty())
        if (raw.isEmpty()) return null
        runCatching { OffsetDateTime.parse(raw) }.getOrNull()?.let {
            return Moment(it.toInstant().toEpochMilli(), formatDay(it.toLocalDate()), dateOnly = false)
        }
        runCatching { LocalDateTime.parse(raw) }.getOrNull()?.let {
            return Moment(it.atZone(zone).toInstant().toEpochMilli(), formatDay(it.toLocalDate()), dateOnly = false)
        }
        runCatching { LocalDate.parse(raw) }.getOrNull()?.let {
            return Moment(it.atStartOfDay(zone).toInstant().toEpochMilli(), formatDay(it), dateOnly = true)
        }
        return null
    }

    private val compactOffset = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?)([+-]\d{2})(\d{2})$""")
    private val spaceSeparated = Regex("""^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}.*)$""")

    /**
     * The two near-ISO shapes senders really use: a `+0200` offset with no colon, and a space
     * where the `T` belongs. Read strictly, both fell through to "no time" and the card was lost.
     */
    private fun normalise(raw: String): String {
        var s = raw
        spaceSeparated.find(s)?.let { s = "${it.groupValues[1]}T${it.groupValues[2]}" }
        compactOffset.find(s)?.let { s = "${it.groupValues[1]}${it.groupValues[2]}:${it.groupValues[3]}" }
        return s
    }

    /**
     * An iCal `TZID`, as whatever wrote it spelled it: an IANA name, one of the Windows names
     * Outlook and Exchange write (`W. Europe Standard Time`), or Outlook's own display form
     * (`(UTC+01:00) Amsterdam, Berlin, …`). Null when none of those, which leaves the device's
     * zone to decide — the old behaviour, and still the right last resort.
     */
    fun zoneFor(tzid: String?): ZoneId? {
        val id = tzid?.trim()?.trim('"').orEmpty()
        if (id.isEmpty()) return null
        runCatching { ZoneId.of(id) }.getOrNull()?.let { return it }
        WINDOWS_ZONES[id.lowercase()]?.let { return ZoneId.of(it) }
        outlookLabel.find(id)?.let { m ->
            val sign = m.groupValues[1]
            val hours = m.groupValues[2].padStart(2, '0')
            val minutes = m.groupValues[3].ifEmpty { "00" }
            return runCatching { ZoneOffset.of("$sign$hours:$minutes") }.getOrNull()
        }
        if (id.equals("utc", ignoreCase = true) || id.equals("gmt", ignoreCase = true)) return ZoneOffset.UTC
        return null
    }

    private val outlookLabel = Regex("""^\((?:UTC|GMT)\s*([+-])(\d{1,2})(?::(\d{2}))?\)""", RegexOption.IGNORE_CASE)

    /** The common Windows zone names (CLDR windowsZones, "001" territory). */
    private val WINDOWS_ZONES = mapOf(
        "utc" to "UTC",
        "gmt standard time" to "Europe/London",
        "greenwich standard time" to "Atlantic/Reykjavik",
        "w. europe standard time" to "Europe/Berlin",
        "central europe standard time" to "Europe/Budapest",
        "central european standard time" to "Europe/Warsaw",
        "romance standard time" to "Europe/Paris",
        "e. europe standard time" to "Europe/Chisinau",
        "fle standard time" to "Europe/Kiev",
        "gtb standard time" to "Europe/Bucharest",
        "russian standard time" to "Europe/Moscow",
        "turkey standard time" to "Europe/Istanbul",
        "israel standard time" to "Asia/Jerusalem",
        "arabian standard time" to "Asia/Dubai",
        "india standard time" to "Asia/Kolkata",
        "china standard time" to "Asia/Shanghai",
        "singapore standard time" to "Asia/Singapore",
        "tokyo standard time" to "Asia/Tokyo",
        "korea standard time" to "Asia/Seoul",
        "aus eastern standard time" to "Australia/Sydney",
        "new zealand standard time" to "Pacific/Auckland",
        "eastern standard time" to "America/New_York",
        "central standard time" to "America/Chicago",
        "mountain standard time" to "America/Denver",
        "us mountain standard time" to "America/Phoenix",
        "pacific standard time" to "America/Los_Angeles",
        "alaskan standard time" to "America/Anchorage",
        "hawaiian standard time" to "Pacific/Honolulu",
        "atlantic standard time" to "America/Halifax",
        "e. south america standard time" to "America/Sao_Paulo",
        "sa pacific standard time" to "America/Bogota",
        "canada central standard time" to "America/Regina",
        "south africa standard time" to "Africa/Johannesburg",
        "egypt standard time" to "Africa/Cairo",
    )

    /** The start of [date] (`yyyy-MM-dd`) in [zone]. */
    fun startOfDayMs(date: String, zone: ZoneId = ZoneId.systemDefault()): Long? =
        runCatching { LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull()

    /** The start of the day after [date] in [zone] — a date-only card's end. */
    fun endOfDayMs(date: String, zone: ZoneId = ZoneId.systemDefault()): Long? =
        runCatching { LocalDate.parse(date).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull()

    /**
     * An iCal `DATE-TIME` or `DATE`, with the `TZID` parameter the property carried.
     *
     * A `Z` suffix means UTC and wins over any `TZID`; without it the `TZID` decides; with
     * neither, the value is floating and resolves against the device.
     */
    fun parseICal(value: String?, tzid: String? = null, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val target = zoneFor(tzid) ?: zone
        return runCatching { LocalDateTime.parse(raw, icalUtc).toInstant(ZoneOffset.UTC).toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(raw, icalLocal).atZone(target).toInstant().toEpochMilli() }
            .recoverCatching { LocalDate.parse(raw, icalDate).atStartOfDay(target).toInstant().toEpochMilli() }
            .recoverCatching { parseIso(raw, target) ?: error("not a date") }
            .getOrNull()
    }

    /** True when an iCal value is a whole-day date rather than a date-time. */
    fun isICalAllDay(value: String?): Boolean {
        val raw = value?.trim().orEmpty()
        return raw.length == 8 && raw.all { it.isDigit() }
    }

    /** `yyyy-MM-dd` in UTC — the day part of a dedupe key, so it must not shift with the device. */
    fun toUtcDate(epochMs: Long): String =
        isoDay.format(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate())

    /**
     * Resolve a boarding pass's Julian day-of-year against a reference moment.
     *
     * A BCBP barcode carries the day of the year and no year — a boarding pass is only
     * useful for about a day, so the format never needed one. The year is therefore the one
     * that puts the flight closest to [nowMs]: a day-of-year 3 seen on 30 December is next
     * year's, and one seen on 2 January is this year's. Choosing the nearest of the three
     * candidate years gets both right, and gets them right without a clock read hidden
     * inside the parser — [nowMs] is always passed in, so the result is reproducible.
     */
    fun julianDayToDate(dayOfYear: Int, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate? {
        if (dayOfYear !in 1..366) return null
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return (today.year - 1..today.year + 1)
            .mapNotNull { year ->
                runCatching { LocalDate.ofYearDay(year, dayOfYear) }.getOrNull()
            }
            .minByOrNull { kotlin.math.abs(it.toEpochDay() - today.toEpochDay()) }
    }

    fun dateToEpochMs(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun formatDay(date: LocalDate): String = isoDay.format(date)
}
