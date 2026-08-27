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
    fun parseIso(value: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(raw).atZone(zone).toInstant().toEpochMilli() }
            .recoverCatching { LocalDate.parse(raw).atStartOfDay(zone).toInstant().toEpochMilli() }
            .getOrNull()
    }

    /**
     * An iCal `DATE-TIME` or `DATE`, with the `TZID` parameter the property carried.
     *
     * A `Z` suffix means UTC and wins over any `TZID`; without it the `TZID` decides; with
     * neither, the value is floating and resolves against the device.
     */
    fun parseICal(value: String?, tzid: String? = null, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val target = tzid?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
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
