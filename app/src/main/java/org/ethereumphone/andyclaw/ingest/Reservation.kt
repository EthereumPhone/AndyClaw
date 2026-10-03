package org.ethereumphone.andyclaw.ingest

import org.ethereumphone.andyclaw.ambient.TimePrecision

/**
 * What ingestion produces: typed data, never prose.
 *
 * `agent-os-design.md` §5 and `andyclaw-to-agent-first.md` §3 state the same hard rule from
 * two directions: **never LLM-extract what is already structured.** Airlines and hotels emit
 * schema.org JSON-LD, boarding passes carry an IATA barcode payload, calendars are iCal.
 * Every field below was read out of one of those, deterministically, by code in this
 * package with no model anywhere in the path.
 *
 * The rule is not about cost. A model that reads a gate number is a model that can be wrong
 * about a gate number, and a device whose pitch is that it tells you things before you ask
 * cannot be wrong about that in an airport. The model decides *when to surface*. It never
 * decides *what the gate number is*.
 */
sealed interface Reservation {
    /** The airline's or hotel's own reference, when the source carried one. */
    val reservationNumber: String?

    /**
     * The deduplication key.
     *
     * The same flight arrives as a booking confirmation, a schedule change and a check-in
     * reminder, and the boarding pass arrives separately again. All four have to converge on
     * one card, so the key is derived from the real-world thing — carrier, number, date —
     * and never from the message it was found in.
     */
    val sourceKey: String

    /** When it starts, in epoch milliseconds, or null when the source did not say. */
    val startMs: Long?

    /** The source says this booking is cancelled (`reservationStatus`). Becomes a tombstone. */
    val cancelled: Boolean

    /** The mail proved who sent it: DKIM passed, aligned with the From domain. */
    val authenticated: Boolean

    /** When the message this came from was received; decides which of two messages is newer. */
    val observedMs: Long

    /** The mail this came from, for the card's `source`. */
    val sourceMessageId: String?

    /** The From domain an [authenticated] mail proved; null for anything else. */
    val fromDomain: String?
}

/**
 * Carrier and number in the one form every source agrees on: `LH` + `400`, whether the mail
 * said `LH 0400`, `LH400` or `400` with the airline separately, so the confirmation and the
 * boarding pass meet on the same key.
 */
internal fun flightIdentity(carrier: String?, number: String?): Pair<String, String>? {
    val c = carrier?.uppercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }
    var n = number?.uppercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() } ?: return null
    if (c != null && n.startsWith(c) && n.length > c.length && n.drop(c.length).first().isDigit()) n = n.drop(c.length)
    val digits = n.trimStart('0').ifEmpty { n }
    // `LH400` with no airline given: an IATA designator is two characters, at least one a letter.
    val derivedCarrier = c ?: Regex("""^([A-Z0-9]{2})(\d+[A-Z]?)$""").find(n)?.groupValues?.get(1)
        ?.takeIf { code -> code.any { it.isLetter() } }
    val derivedNumber = if (c == null && derivedCarrier != null) n.drop(2).trimStart('0').ifEmpty { n.drop(2) } else digits
    return (derivedCarrier ?: return null) to derivedNumber
}

data class FlightReservation(
    override val reservationNumber: String?,
    val airlineName: String? = null,
    val airlineIata: String? = null,
    val flightNumber: String? = null,
    val departureAirport: String? = null,
    val departureAirportName: String? = null,
    val arrivalAirport: String? = null,
    val arrivalAirportName: String? = null,
    val departureTimeMs: Long? = null,
    val arrivalTimeMs: Long? = null,
    val departureTerminal: String? = null,
    val departureGate: String? = null,
    val passengerName: String? = null,
    val seat: String? = null,
    /** Present when a `.pkpass` or a PDF carried a scannable payload. */
    val boardingPass: BoardingPass? = null,
    /**
     * The departure day as the source wrote it (`yyyy-MM-dd`), in the departure airport's
     * terms: the date part of a local time, or the barcode's own day of flight. What the key
     * is built from, so a pass and a confirmation agree east and west of UTC alike.
     */
    val departureDate: String? = null,
    /**
     * What [departureTimeMs] is. [TimePrecision.DATE_ONLY] means there is no time at all —
     * [departureTimeMs] is null and only [departureDate] is known.
     */
    val departurePrecision: TimePrecision = TimePrecision.EXACT,
    override val cancelled: Boolean = false,
    override val authenticated: Boolean = false,
    override val observedMs: Long = 0L,
    override val sourceMessageId: String? = null,
    override val fromDomain: String? = null,
) : Reservation {

    override val startMs: Long? get() = departureTimeMs

    /**
     * `flight:<carrier><number>:<yyyy-mm-dd>`, falling back to the record locator.
     *
     * Carrier and number and date identify a flight; a record locator identifies a booking,
     * which can hold several. Preferring the flight is what merges a confirmation mail with
     * the boarding pass that arrives two days later. The date is the local day of departure,
     * never the UTC day: a Berlin 00:30 departure is on the 1st, as its barcode says, and
     * dating it the 31st in UTC was what split one flight into two cards.
     */
    override val sourceKey: String
        get() {
            val identity = flightIdentity(airlineIata, flightNumber)
                ?: flightIdentity(airlineName, flightNumber)
            val day = departureDate ?: departureTimeMs?.let { IsoDates.toUtcDate(it) }
            return when {
                identity != null && day != null -> "flight:${identity.first}${identity.second}:$day"
                identity != null -> "flight:${identity.first}${identity.second}"
                reservationNumber != null -> "flight:pnr:${reservationNumber.uppercase()}"
                else -> "flight:${departureAirport.orEmpty()}-${arrivalAirport.orEmpty()}:${day.orEmpty()}"
            }
        }

    val routeLabel: String
        get() = listOfNotNull(departureAirport, arrivalAirport).joinToString(" → ").ifBlank { "Flight" }

    val flightLabel: String
        get() = listOfNotNull(airlineIata ?: airlineName, flightNumber).joinToString(" ").ifBlank { "Flight" }
}

data class LodgingReservation(
    override val reservationNumber: String?,
    val name: String? = null,
    val address: String? = null,
    val checkinMs: Long? = null,
    val checkoutMs: Long? = null,
    val guestName: String? = null,
    /** The check-in day as the source wrote it; builds the key. */
    val checkinDate: String? = null,
    /** Only dates were given: [checkinMs] and [checkoutMs] are the starts of those days. */
    val dateOnly: Boolean = false,
    override val cancelled: Boolean = false,
    override val authenticated: Boolean = false,
    override val observedMs: Long = 0L,
    override val sourceMessageId: String? = null,
    override val fromDomain: String? = null,
) : Reservation {

    override val startMs: Long? get() = checkinMs

    override val sourceKey: String
        get() {
            val place = name?.lowercase()?.filter { it.isLetterOrDigit() }?.take(32)
            val day = checkinDate ?: checkinMs?.let { IsoDates.toUtcDate(it) }
            return when {
                place != null && day != null -> "lodging:$place:$day"
                reservationNumber != null -> "lodging:ref:${reservationNumber.uppercase()}"
                else -> "lodging:${place.orEmpty()}${day.orEmpty()}"
            }
        }
}

data class EventReservation(
    override val reservationNumber: String?,
    val eventName: String? = null,
    val location: String? = null,
    val startTimeMs: Long? = null,
    val endTimeMs: Long? = null,
    val attendeeName: String? = null,
    val ticketToken: String? = null,
    /** The day as the source wrote it; builds the key. */
    val startDate: String? = null,
    /** Only a date was given: [startTimeMs] is the start of that day. */
    val dateOnly: Boolean = false,
    override val cancelled: Boolean = false,
    override val authenticated: Boolean = false,
    override val observedMs: Long = 0L,
    override val sourceMessageId: String? = null,
    override val fromDomain: String? = null,
) : Reservation {

    override val startMs: Long? get() = startTimeMs

    override val sourceKey: String
        get() {
            val event = eventName?.lowercase()?.filter { it.isLetterOrDigit() }?.take(32)
            val day = startDate ?: startTimeMs?.let { IsoDates.toUtcDate(it) }
            return when {
                event != null && day != null -> "event:$event:$day"
                reservationNumber != null -> "event:ref:${reservationNumber.uppercase()}"
                else -> "event:${event.orEmpty()}${day.orEmpty()}"
            }
        }
}

/** A calendar entry, parsed from iCal or from the Calendar API's own JSON. */
data class CalendarEvent(
    val uid: String?,
    val summary: String?,
    val location: String? = null,
    val description: String? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val allDay: Boolean = false,
    /**
     * Cancelled (`STATUS:CANCELLED`, `METHOD:CANCEL`, the API's `cancelled`) or declined by the
     * user. Kept as a tombstone rather than dropped, so the card it may already have goes away.
     */
    val cancelled: Boolean = false,
    /**
     * For one occurrence of a repeating event: when that occurrence was originally due
     * (`RECURRENCE-ID`, the API's `originalStartTime`). Every occurrence shares the series'
     * UID, and keying them by UID alone collapsed a daily standup into whichever day came last.
     */
    val occurrenceMs: Long? = null,
    /** For an invitation that arrived by mail: whether that mail proved who sent it. */
    val authenticated: Boolean = false,
    /** For an invitation that arrived by mail: when the mail was received. */
    val observedMs: Long = 0L,
    /** For an invitation that arrived by mail: the From domain it proved, if it did. */
    val fromDomain: String? = null,
) {
    val sourceKey: String
        get() = "calendar:${uid ?: "${summary?.lowercase()?.take(32).orEmpty()}:${startMs ?: 0L}"}" +
            (occurrenceMs?.let { "@$it" } ?: "")
}

/**
 * The scannable payload, kept exactly as it was found.
 *
 * [payload] is not re-encoded, reformatted or normalised. It is what a gate reader has to
 * see, and any tidying of it is a way to make a boarding pass that does not scan.
 */
data class BoardingPass(
    val payload: String,
    /** `PKBarcodeFormatAztec`, `PKBarcodeFormatQR`, `BCBP-M1`, … */
    val format: String,
    val passengerName: String? = null,
    val recordLocator: String? = null,
    val fromAirport: String? = null,
    val toAirport: String? = null,
    val carrier: String? = null,
    val flightNumber: String? = null,
    /** Local date of the flight as `yyyy-MM-dd`, resolved from the Julian day. */
    val flightDate: String? = null,
    val cabin: String? = null,
    val seat: String? = null,
    val sequenceNumber: String? = null,
)
