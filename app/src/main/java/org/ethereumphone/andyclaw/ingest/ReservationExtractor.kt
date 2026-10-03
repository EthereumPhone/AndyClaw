package org.ethereumphone.andyclaw.ingest

import org.ethereumphone.andyclaw.ambient.TimePrecision
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
    /**
     * The receiving server saw a DKIM signature pass for the From domain
     * ([MailAuthentication]). Anyone can write "From: lufthansa.com"; only Lufthansa can sign
     * it. Mail that is not signed still makes cards, marked as such, and can never overwrite
     * what signed mail said.
     */
    val authenticated: Boolean = false,
    /** Every part was fetched. A message missing an attachment is not marked as done. */
    val complete: Boolean = true,
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

        // What the message says about itself travels with everything taken from it.
        val stamped = merge(reservations, boardingPasses).map { it.from(message) }
        return IngestResult(
            reservations = stamped,
            calendarEvents = events.distinctBy { it.sourceKey }.map {
                it.copy(authenticated = message.authenticated, observedMs = message.receivedMs, fromDomain = message.provenDomain())
            },
        )
    }

    private fun Reservation.from(message: MailMessage): Reservation {
        val domain = message.provenDomain()
        return when (this) {
            is FlightReservation -> copy(authenticated = message.authenticated, observedMs = message.receivedMs, sourceMessageId = message.id, fromDomain = domain)
            is LodgingReservation -> copy(authenticated = message.authenticated, observedMs = message.receivedMs, sourceMessageId = message.id, fromDomain = domain)
            is EventReservation -> copy(authenticated = message.authenticated, observedMs = message.receivedMs, sourceMessageId = message.id, fromDomain = domain)
        }
    }

    /** Who a signed mail proved it is; nothing for one that proved nothing. */
    private fun MailMessage.provenDomain(): String? =
        if (authenticated) MailAuthentication.domainOf(from) else null

    /**
     * Every reservation across a batch of messages, already merged and deduplicated.
     *
     * A message that throws yields nothing and leaves the others alone — whatever it throws,
     * a StackOverflowError included. The batch is a stranger's mail: one crafted message used to
     * kill the app, and since it was then never marked read, kill it again on every start.
     */
    fun extractAll(
        messages: List<MailMessage>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): IngestResult {
        val combined = messages.fold(IngestResult()) { acc, m ->
            val one = try {
                extract(m, nowMs, zone)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Throwable) {
                IngestResult()
            }
            acc + one
        }
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
        for (reservation in base + boardingPasses) {
            val existing = byKey[reservation.sourceKey]
            byKey[reservation.sourceKey] = when {
                existing == null -> reservation
                existing is FlightReservation && reservation is FlightReservation -> combine(existing, reservation)
                else -> pickOne(existing, reservation)
            }
        }
        return byKey.values.toList()
    }

    /**
     * Two sources for the same flight, as one.
     *
     * - A signed mail and an unsigned one are not combined: the signed one stands alone, so
     *   nothing an unverified sender wrote rides along on a verified card.
     * - The **time** comes from the more precise source — a departure beats a boarding time
     *   beats a bare day — whichever mail arrived first. Taking the first one's time was how a
     *   boarding pass read ahead of its confirmation turned 09:40 into midnight.
     * - Everything else comes from the **newer** message, the older one filling gaps; and the
     *   newer one decides whether the booking stands or is cancelled.
     */
    private fun combine(a: FlightReservation, b: FlightReservation): FlightReservation {
        if (a.authenticated != b.authenticated) return if (a.authenticated) a else b
        val (newer, older) = if (b.observedMs > a.observedMs) b to a else a to b
        // Signed by someone else than the older mail: it may fill in, not call the flight off.
        val cancelled = if (newer.cancelled && !older.cancelled && !sameSender(newer.fromDomain, older.fromDomain)) {
            older.cancelled
        } else {
            newer.cancelled
        }
        val (timed, untimed) = when {
            a.departurePrecision > b.departurePrecision -> a to b
            b.departurePrecision > a.departurePrecision -> b to a
            else -> newer to older
        }
        return FlightReservation(
            reservationNumber = newer.reservationNumber ?: older.reservationNumber,
            airlineName = newer.airlineName ?: older.airlineName,
            airlineIata = newer.airlineIata ?: older.airlineIata,
            flightNumber = newer.flightNumber ?: older.flightNumber,
            departureAirport = newer.departureAirport ?: older.departureAirport,
            departureAirportName = newer.departureAirportName ?: older.departureAirportName,
            arrivalAirport = newer.arrivalAirport ?: older.arrivalAirport,
            arrivalAirportName = newer.arrivalAirportName ?: older.arrivalAirportName,
            departureTimeMs = timed.departureTimeMs,
            arrivalTimeMs = newer.arrivalTimeMs ?: older.arrivalTimeMs,
            departureTerminal = newer.departureTerminal ?: older.departureTerminal,
            departureGate = newer.departureGate ?: older.departureGate,
            passengerName = newer.passengerName ?: older.passengerName,
            seat = newer.seat ?: older.seat,
            boardingPass = newer.boardingPass ?: older.boardingPass,
            departureDate = timed.departureDate ?: untimed.departureDate,
            departurePrecision = timed.departurePrecision,
            cancelled = cancelled,
            authenticated = a.authenticated,
            observedMs = maxOf(a.observedMs, b.observedMs),
            sourceMessageId = newer.sourceMessageId,
            fromDomain = older.fromDomain ?: newer.fromDomain,
        )
    }

    /**
     * Two mails come from the same sender when their proven domains are equal or one is a
     * subdomain of the other, or when either proved none (older data, a boarding pass in the
     * same mail), which keeps what was allowed before.
     */
    private fun sameSender(a: String?, b: String?): Boolean {
        if (a == null || b == null) return true
        return a == b || a.endsWith(".$b") || b.endsWith(".$a")
    }

    /** Hotels and events are not field-merged: the signed one, else the newer one, stands. */
    private fun pickOne(a: Reservation, b: Reservation): Reservation = when {
        a.authenticated != b.authenticated -> if (a.authenticated) a else b
        b.observedMs > a.observedMs -> b
        else -> a
    }

    /**
     * A barcode knows the day of the flight and nothing about the hour. It becomes a
     * date-only flight — never midnight, which is a time nobody boards at.
     */
    private fun fromBoardingPass(pass: BoardingPass, zone: ZoneId) = FlightReservation(
        reservationNumber = pass.recordLocator,
        airlineIata = pass.carrier,
        flightNumber = pass.flightNumber,
        departureAirport = pass.fromAirport,
        arrivalAirport = pass.toAirport,
        departureTimeMs = null,
        passengerName = pass.passengerName,
        seat = pass.seat,
        boardingPass = pass,
        departureDate = pass.flightDate,
        departurePrecision = TimePrecision.DATE_ONLY,
    )

    private fun ByteArray.toText(): String? =
        if (size > MAX_TEXT_BYTES) null else String(this, Charsets.UTF_8)

    /** A confirmation mail is kilobytes. Anything past this is not a body worth scanning. */
    private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
}
