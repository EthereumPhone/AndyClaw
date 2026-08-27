package org.ethereumphone.andyclaw.ingest

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
) : Reservation {

    override val startMs: Long? get() = departureTimeMs

    /**
     * `flight:<carrier><number>:<yyyy-mm-dd>`, falling back to the record locator.
     *
     * Carrier and number and date identify a flight; a record locator identifies a booking,
     * which can hold several. Preferring the flight is what merges a confirmation mail with
     * the boarding pass that arrives two days later.
     */
    override val sourceKey: String
        get() {
            val carrier = (airlineIata ?: airlineName)?.uppercase()?.filter { it.isLetterOrDigit() }
            val number = flightNumber?.uppercase()?.filter { it.isLetterOrDigit() }
            val day = departureTimeMs?.let { IsoDates.toUtcDate(it) }
            return when {
                carrier != null && number != null && day != null -> "flight:$carrier$number:$day"
                carrier != null && number != null -> "flight:$carrier$number"
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
) : Reservation {

    override val startMs: Long? get() = checkinMs

    override val sourceKey: String
        get() {
            val place = name?.lowercase()?.filter { it.isLetterOrDigit() }?.take(32)
            val day = checkinMs?.let { IsoDates.toUtcDate(it) }
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
) : Reservation {

    override val startMs: Long? get() = startTimeMs

    override val sourceKey: String
        get() {
            val event = eventName?.lowercase()?.filter { it.isLetterOrDigit() }?.take(32)
            val day = startTimeMs?.let { IsoDates.toUtcDate(it) }
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
) {
    val sourceKey: String
        get() = "calendar:${uid ?: "${summary?.lowercase()?.take(32).orEmpty()}:${startMs ?: 0L}"}"
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
