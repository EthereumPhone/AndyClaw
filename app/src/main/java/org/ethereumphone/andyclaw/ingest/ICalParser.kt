package org.ethereumphone.andyclaw.ingest

import java.time.ZoneId

/**
 * RFC 5545, as much of it as a calendar card needs.
 *
 * iCal is the third of `agent-os-design.md` §5's three already-structured formats, and like
 * the other two it needs parsing rather than interpretation. What makes it fiddly is not the
 * grammar but the details that are easy to get subtly wrong and that nobody notices until a
 * meeting shows up an hour off:
 *
 * - **Folding.** A long line is broken and continued with a leading space or tab, and the
 *   continuation is part of the same value. Unfolding has to happen before anything else,
 *   or a `SUMMARY` comes back truncated at 75 octets.
 * - **Parameters.** `DTSTART;TZID=Europe/Berlin:20260901T103000` — the zone is a parameter
 *   on the property, not part of the value, and dropping it silently reinterprets the time
 *   as local.
 * - **Escapes.** `\,`, `\;`, `\n` and `\\` are escapes inside a text value, so a location
 *   containing a comma arrives with backslashes in it if they are not undone.
 *
 * Only `VEVENT` is read. `VTODO`, `VJOURNAL`, alarms and recurrence rules are out of scope:
 * a recurring event expanded here would be a second implementation of a rule the calendar
 * server already applies, and two answers to "when is this" is worse than one.
 */
object ICalParser {

    fun looksLikeICal(text: String): Boolean =
        text.contains("BEGIN:VCALENDAR", ignoreCase = true) ||
            text.contains("BEGIN:VEVENT", ignoreCase = true)

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent> {
        if (text.isBlank()) return emptyList()

        val events = mutableListOf<CalendarEvent>()
        var current: MutableMap<String, Property>? = null

        for (line in unfold(text)) {
            val upper = line.uppercase()
            when {
                upper.startsWith("BEGIN:VEVENT") -> current = mutableMapOf()
                upper.startsWith("END:VEVENT") -> {
                    current?.let { events += toEvent(it, zone) }
                    current = null
                }
                current != null -> {
                    val property = parseProperty(line) ?: continue
                    // First wins: a malformed feed that repeats DTSTART means the first one.
                    current.putIfAbsent(property.name, property)
                }
            }
        }
        return events.filter { it.startMs != null || it.summary != null }
    }

    // ── Lines ─────────────────────────────────────────────────────────

    /** Split into logical lines, joining continuations. */
    internal fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.split("\r\n", "\n", "\r")) {
            if (raw.isEmpty()) continue
            if ((raw[0] == ' ' || raw[0] == '\t') && out.isNotEmpty()) {
                out[out.lastIndex] = out.last() + raw.substring(1)
            } else {
                out += raw
            }
        }
        return out
    }

    internal data class Property(val name: String, val params: Map<String, String>, val value: String)

    /** `NAME;PARAM=value:the value`, with the colon that ends the name not the one inside it. */
    internal fun parseProperty(line: String): Property? {
        val colon = indexOfValueColon(line)
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)

        val parts = head.split(';')
        val name = parts.first().trim().uppercase()
        if (name.isEmpty()) return null

        val params = parts.drop(1).mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq).trim().uppercase() to part.substring(eq + 1).trim().trim('"')
        }.toMap()

        return Property(name, params, value)
    }

    /** The first `:` that is not inside a quoted parameter value. */
    private fun indexOfValueColon(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ':' -> if (!quoted) return i
            }
        }
        return -1
    }

    private fun toEvent(props: Map<String, Property>, zone: ZoneId): CalendarEvent {
        val start = props["DTSTART"]
        val end = props["DTEND"]
        return CalendarEvent(
            uid = props["UID"]?.value?.trim()?.takeIf { it.isNotEmpty() },
            summary = props["SUMMARY"]?.value?.let(::unescape)?.takeIf { it.isNotEmpty() },
            location = props["LOCATION"]?.value?.let(::unescape)?.takeIf { it.isNotEmpty() },
            description = props["DESCRIPTION"]?.value?.let(::unescape)?.takeIf { it.isNotEmpty() },
            startMs = start?.let { IsoDates.parseICal(it.value, it.params["TZID"], zone) },
            endMs = end?.let { IsoDates.parseICal(it.value, it.params["TZID"], zone) },
            allDay = IsoDates.isICalAllDay(start?.value),
        )
    }

    internal fun unescape(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val e = value[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    '\\', ',', ';' -> sb.append(e)
                    else -> { sb.append(c); sb.append(e) }
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString().trim()
    }
}
