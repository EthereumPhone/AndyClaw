package org.ethereumphone.andyclaw.ingest

import java.time.ZoneId

/** One part of a mail: a body, or an attachment. */
data class MailPart(
    val mimeType: String,
    val filename: String? = null,
    /** Decoded text, for body parts. */
    val text: String? = null,
    /** Decoded bytes, for attachments. */
    val bytes: ByteArray? = null,
) {
    // Data classes with a ByteArray need these, or equality compares references and the
    // de-duplication in the extractor silently stops working.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MailPart) return false
        return mimeType == other.mimeType &&
            filename == other.filename &&
            text == other.text &&
            (bytes?.contentEquals(other.bytes) ?: (other.bytes == null))
    }

    override fun hashCode(): Int {
        var result = mimeType.hashCode()
        result = 31 * result + (filename?.hashCode() ?: 0)
        result = 31 * result + (text?.hashCode() ?: 0)
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        return result
    }
}

/** A mail, as far as ingestion is concerned. No headers it does not need, no raw source. */
data class MailMessage(
    val id: String,
    val subject: String? = null,
    val from: String? = null,
    val receivedMs: Long = 0L,
    val parts: List<MailPart> = emptyList(),
)

/** What one message yielded. */
data class IngestResult(
    val reservations: List<Reservation> = emptyList(),
    val calendarEvents: List<CalendarEvent> = emptyList(),
) {
    val isEmpty: Boolean get() = reservations.isEmpty() && calendarEvents.isEmpty()

    operator fun plus(other: IngestResult) = IngestResult(
        reservations = reservations + other.reservations,
        calendarEvents = calendarEvents + other.calendarEvents,
    )
}

/**
 * The whole extraction path, as one pure function.
 *
 * `agent-first-plan.md` Phase 3.3's definition of done is "**zero LLM calls in the
 * extraction path** — assert this in a test", and that is easiest to guarantee by making it
 * structurally impossible rather than by remembering. Every entry point here is an ordinary
 * non-suspend function over bytes and strings. It takes no client, holds no state, opens no
 * socket and reads no clock — [nowMs] is a parameter, because the only thing in the path
 * that needs the time is resolving a boarding pass's year, and a hidden clock read would
 * make the whole thing untestable for the sake of one argument.
 *
 * `IngestNoModelCallTest` holds that shape in place: an extractor that acquires a
 * dependency, a suspend modifier, or a coroutine is one where a model call has become
 * possible, and the test fails before anyone has to notice.
 *
 * Everything produced here is
 * [org.ethereumphone.andyclaw.ExecutionEngine.Provenance.UNTRUSTED] and is stored as typed
 * data. It is never turned back into prose and fed to a model: a confirmation mail is
 * written by whoever sent it, and mail bodies are the injection channel
 * `andyclaw-to-agent-first.md` §2 is about.
 */
object ReservationExtractor {

    fun extract(
        message: MailMessage,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): IngestResult {
        val reservations = mutableListOf<Reservation>()
        val boardingPasses = mutableListOf<FlightReservation>()
        val events = mutableListOf<CalendarEvent>()

        for (part in message.parts) {
            val mime = part.mimeType.lowercase()
            val name = part.filename?.lowercase().orEmpty()

            when {
                mime.startsWith("text/calendar") || name.endsWith(".ics") -> {
                    val text = part.text ?: part.bytes?.toText()
                    if (text != null) events += ICalParser.parse(text, zone)
                }

                mime.contains("pkpass") || name.endsWith(".pkpass") -> {
                    val bytes = part.bytes ?: continue
                    val pass = PkPassParser.parse(bytes, zone) ?: continue
                    PkPassParser.toFlightReservation(pass, nowMs, zone)?.let { boardingPasses += it }
                }

                mime.startsWith("application/pdf") || name.endsWith(".pdf") -> {
                    val bytes = part.bytes ?: continue
                    val text = PdfTextExtractor.extract(bytes)
                    for (pass in BcbpParser.findAll(text, nowMs)) {
                        boardingPasses += fromBoardingPass(pass, zone)
                    }
                }

                mime.startsWith("text/") -> {
                    val text = part.text ?: part.bytes?.toText() ?: continue
                    reservations += JsonLdReservationParser.parse(text, zone)
                    if (ICalParser.looksLikeICal(text)) events += ICalParser.parse(text, zone)
                }

                // An attachment whose type the sender did not label. Sniff, do not guess.
                part.bytes != null -> {
                    val bytes = part.bytes
                    when {
                        PkPassParser.looksLikePkPass(bytes) -> {
                            PkPassParser.parse(bytes, zone)
                                ?.let { PkPassParser.toFlightReservation(it, nowMs, zone) }
                                ?.let { boardingPasses += it }
                        }
                        PdfTextExtractor.looksLikePdf(bytes) -> {
                            for (pass in BcbpParser.findAll(PdfTextExtractor.extract(bytes), nowMs)) {
                                boardingPasses += fromBoardingPass(pass, zone)
                            }
                        }
                    }
                }
            }
        }

        return IngestResult(
            reservations = merge(reservations, boardingPasses),
            calendarEvents = events.distinctBy { it.sourceKey },
        )
    }

    /** Every reservation across a batch of messages, already merged and deduplicated. */
    fun extractAll(
        messages: List<MailMessage>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): IngestResult {
        val combined = messages.fold(IngestResult()) { acc, m -> acc + extract(m, nowMs, zone) }
        return IngestResult(
            reservations = merge(combined.reservations, emptyList()),
            calendarEvents = combined.calendarEvents.distinctBy { it.sourceKey },
        )
    }

    // ── Merging ───────────────────────────────────────────────────────

    /**
     * Fold boarding passes into the bookings they belong to.
     *
     * The confirmation mail knows the terminal and the airline's name for the flight; the
     * boarding pass knows the seat, the sequence number and the payload that actually
     * scans. They arrive days apart in different messages and they are one card, so they
     * are merged on the flight's own key rather than on anything about the mail.
     *
     * A boarding pass with no matching booking still becomes a reservation of its own. That
     * is the common case for a flight somebody else booked, and it is the one the ambient
     * surface most needs to get right.
     */
    private fun merge(base: List<Reservation>, boardingPasses: List<FlightReservation>): List<Reservation> {
        val byKey = LinkedHashMap<String, Reservation>()
        for (reservation in base) {
            val existing = byKey[reservation.sourceKey]
            byKey[reservation.sourceKey] =
                if (existing is FlightReservation && reservation is FlightReservation) {
                    combine(existing, reservation)
                } else {
                    existing ?: reservation
                }
        }
        for (pass in boardingPasses) {
            val existing = byKey[pass.sourceKey]
            byKey[pass.sourceKey] =
                if (existing is FlightReservation) combine(existing, pass) else pass
        }
        return byKey.values.toList()
    }

    /** Field-by-field, [b] filling in what [a] does not have. Neither overwrites the other. */
    private fun combine(a: FlightReservation, b: FlightReservation) = FlightReservation(
        reservationNumber = a.reservationNumber ?: b.reservationNumber,
        airlineName = a.airlineName ?: b.airlineName,
        airlineIata = a.airlineIata ?: b.airlineIata,
        flightNumber = a.flightNumber ?: b.flightNumber,
        departureAirport = a.departureAirport ?: b.departureAirport,
        departureAirportName = a.departureAirportName ?: b.departureAirportName,
        arrivalAirport = a.arrivalAirport ?: b.arrivalAirport,
        arrivalAirportName = a.arrivalAirportName ?: b.arrivalAirportName,
        departureTimeMs = a.departureTimeMs ?: b.departureTimeMs,
        arrivalTimeMs = a.arrivalTimeMs ?: b.arrivalTimeMs,
        departureTerminal = a.departureTerminal ?: b.departureTerminal,
        departureGate = a.departureGate ?: b.departureGate,
        passengerName = a.passengerName ?: b.passengerName,
        seat = a.seat ?: b.seat,
        boardingPass = a.boardingPass ?: b.boardingPass,
    )

    private fun fromBoardingPass(pass: BoardingPass, zone: ZoneId) = FlightReservation(
        reservationNumber = pass.recordLocator,
        airlineIata = pass.carrier,
        flightNumber = pass.flightNumber,
        departureAirport = pass.fromAirport,
        arrivalAirport = pass.toAirport,
        departureTimeMs = pass.flightDate?.let { IsoDates.parseIso(it, zone) },
        passengerName = pass.passengerName,
        seat = pass.seat,
        boardingPass = pass,
    )

    private fun ByteArray.toText(): String? =
        if (size > MAX_TEXT_BYTES) null else String(this, Charsets.UTF_8)

    /** A confirmation mail is kilobytes. Anything past this is not a body worth scanning. */
    private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
}
