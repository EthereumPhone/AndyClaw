package org.ethereumphone.andyclaw.ingest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.ambient.PredictedContext
import org.ethereumphone.andyclaw.ambient.PredictedContextPayload
import org.ethereumphone.andyclaw.ambient.PredictedContextRepository
import org.ethereumphone.andyclaw.ambient.PredictedKind
import org.ethereumphone.andyclaw.ambient.TimePrecision
import java.time.ZoneId

/**
 * Parsed reservation to ambient card.
 *
 * The card library in `agent-os-design.md` §5 is fixed and small — `BoardingPassCard`,
 * `TransitCard`, and the rest — and the model never generates layout, so what a card needs
 * is a kind, a time, a couple of lines of text and the structured payload behind it. That is
 * exactly the shape of [PredictedContext], and this is the only place a parsed reservation
 * turns into one.
 *
 * Anything without a start time is dropped rather than given one. A card with no time cannot
 * be ranked by time-to-relevance, and a guessed time on a boarding pass is the failure mode
 * this whole path exists to avoid.
 *
 * [PredictedContext.payloadJson] carries the fields verbatim — including the barcode
 * payload, which is the single value a boarding-pass card exists to display and must survive
 * the trip unaltered.
 */
object PredictedContextMapper {

    private val json = Json { encodeDefaults = false }

    /**
     * [source] is `gmail:<messageId>` for mail. [zone] places a date-only flight's day; it is
     * the device's, which is where the user is when the card matters.
     */
    fun fromReservation(
        reservation: Reservation,
        source: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): PredictedContext? =
        when (reservation) {
            is FlightReservation -> fromFlight(reservation, source, zone)
            is LodgingReservation -> fromLodging(reservation, source)
            is EventReservation -> fromEvent(reservation, source)
        }

    /**
     * [source] is `gcal` or `device-calendar` for the calendar itself — the authority on
     * events, whose rows mail cannot overwrite — and `gmail-ics` for an invitation that came
     * by mail. [observedMs] defaults to when the mail arrived; for a live calendar it is the
     * moment it was read, because that is how current it is.
     */
    fun fromCalendarEvent(event: CalendarEvent, source: String, observedMs: Long = event.observedMs): PredictedContext? {
        val start = event.startMs ?: return null
        val summary = event.summary?.takeIf { it.isNotBlank() } ?: return null
        val fromMail = source !in PredictedContextPayload.LIVE_CALENDAR_SOURCES
        return context(
            kind = PredictedKind.CALENDAR,
            title = summary,
            subtitle = event.location?.takeIf { it.isNotBlank() },
            startMs = start,
            endMs = event.endMs,
            location = event.location,
            sourceKey = event.sourceKey,
            source = source,
            payload = buildJsonObject {
                event.uid?.let { put("uid", it) }
                put("summary", summary)
                event.location?.let { put("location", it) }
                put("start_ms", start)
                event.endMs?.let { put("end_ms", it) }
                put("all_day", event.allDay)
                event.occurrenceMs?.let { put("occurrence_ms", it) }
                bookkeeping(event.cancelled, observedMs, if (fromMail) event.authenticated else null, if (fromMail) event.fromDomain else null)
            },
        )
    }

    // ── Kinds ─────────────────────────────────────────────────────────

    /**
     * A flight whose source knew only the day — a bare boarding-pass barcode — spans that
     * whole local day and says so (`date_only`, `departure_date`) rather than claiming a
     * departure at midnight. A boarding time is labelled as one (`boarding_ms`).
     */
    private fun fromFlight(flight: FlightReservation, source: String, zone: ZoneId): PredictedContext? {
        val dateOnly = flight.departurePrecision == TimePrecision.DATE_ONLY || flight.departureTimeMs == null
        val start: Long
        val end: Long?
        if (dateOnly) {
            val day = flight.departureDate ?: return null
            start = IsoDates.startOfDayMs(day, zone) ?: return null
            end = IsoDates.endOfDayMs(day, zone)
        } else {
            start = flight.departureTimeMs ?: return null
            end = flight.arrivalTimeMs
        }
        val precision = if (dateOnly) TimePrecision.DATE_ONLY else flight.departurePrecision
        val payload = buildJsonObject {
            flight.reservationNumber?.let { put("reservation_number", it) }
            flight.airlineIata?.let { put("airline_iata", it) }
            flight.airlineName?.let { put("airline_name", it) }
            flight.flightNumber?.let { put("flight_number", it) }
            flight.departureAirport?.let { put("from", it) }
            flight.arrivalAirport?.let { put("to", it) }
            put(PredictedContextPayload.TIME_PRECISION, precision.wire)
            when (precision) {
                TimePrecision.EXACT -> put("departure_ms", start)
                TimePrecision.BOARDING -> put("boarding_ms", start)
                TimePrecision.DATE_ONLY -> put(PredictedContextPayload.DATE_ONLY, true)
            }
            flight.departureDate?.let { put("departure_date", it) }
            if (!dateOnly) flight.arrivalTimeMs?.let { put("arrival_ms", it) }
            flight.departureTerminal?.let { put("terminal", it) }
            flight.departureGate?.let { put("gate", it) }
            flight.passengerName?.let { put("passenger", it) }
            flight.seat?.let { put("seat", it) }
            flight.boardingPass?.let { pass ->
                // The reason the card exists. Verbatim, unmodified, unnormalised.
                put("barcode_payload", pass.payload)
                put("barcode_format", pass.format)
                pass.sequenceNumber?.let { put("sequence_number", it) }
                pass.cabin?.let { put("cabin", it) }
            }
            bookkeeping(flight.cancelled, flight.observedMs, flight.authenticated, flight.fromDomain)
        }
        return context(
            kind = PredictedKind.FLIGHT,
            title = "${flight.flightLabel}  ${flight.routeLabel}".trim(),
            subtitle = PredictedContextPayload.flightSubtitle(payload),
            startMs = start,
            endMs = end,
            location = flight.departureAirportName ?: flight.departureAirport,
            sourceKey = flight.sourceKey,
            source = source,
            payload = payload,
        )
    }

    private fun fromLodging(lodging: LodgingReservation, source: String): PredictedContext? {
        val start = lodging.checkinMs ?: return null
        val name = lodging.name?.takeIf { it.isNotBlank() } ?: "Hotel"
        return context(
            kind = PredictedKind.LODGING,
            title = name,
            subtitle = lodging.address?.takeIf { it.isNotBlank() } ?: lodging.reservationNumber,
            startMs = start,
            endMs = lodging.checkoutMs,
            location = lodging.address,
            sourceKey = lodging.sourceKey,
            source = source,
            payload = buildJsonObject {
                lodging.reservationNumber?.let { put("reservation_number", it) }
                put("name", name)
                lodging.address?.let { put("address", it) }
                put("checkin_ms", start)
                lodging.checkoutMs?.let { put("checkout_ms", it) }
                lodging.checkinDate?.let { put("checkin_date", it) }
                if (lodging.dateOnly) put(PredictedContextPayload.DATE_ONLY, true)
                lodging.guestName?.let { put("guest", it) }
                bookkeeping(lodging.cancelled, lodging.observedMs, lodging.authenticated, lodging.fromDomain)
            },
        )
    }

    private fun fromEvent(event: EventReservation, source: String): PredictedContext? {
        val start = event.startTimeMs ?: return null
        val name = event.eventName?.takeIf { it.isNotBlank() } ?: "Event"
        return context(
            kind = PredictedKind.EVENT,
            title = name,
            subtitle = event.location?.takeIf { it.isNotBlank() } ?: event.reservationNumber,
            startMs = start,
            endMs = event.endTimeMs,
            location = event.location,
            sourceKey = event.sourceKey,
            source = source,
            payload = buildJsonObject {
                event.reservationNumber?.let { put("reservation_number", it) }
                put("name", name)
                event.location?.let { put("location", it) }
                put("start_ms", start)
                event.endTimeMs?.let { put("end_ms", it) }
                event.startDate?.let { put("start_date", it) }
                if (event.dateOnly) put(PredictedContextPayload.DATE_ONLY, true)
                event.attendeeName?.let { put("attendee", it) }
                event.ticketToken?.let { put("ticket_token", it) }
                bookkeeping(event.cancelled, event.observedMs, event.authenticated, event.fromDomain)
            },
        )
    }

    /**
     * The store's bookkeeping keys ([PredictedContextPayload]): a cancellation, when the source
     * was seen, and — for mail — whether it was signed, and by whom. Absent when not known.
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.bookkeeping(
        cancelled: Boolean,
        observedMs: Long,
        authenticated: Boolean?,
        fromDomain: String?,
    ) {
        if (cancelled) put(PredictedContextPayload.CANCELLED, true)
        if (observedMs > 0) put(PredictedContextPayload.OBSERVED_MS, observedMs)
        authenticated?.let { put(PredictedContextPayload.AUTHENTICATED, it) }
        fromDomain?.let { put(PredictedContextPayload.FROM_DOMAIN, it) }
    }

    private fun context(
        kind: PredictedKind,
        title: String,
        subtitle: String?,
        startMs: Long,
        endMs: Long?,
        location: String?,
        sourceKey: String,
        source: String,
        payload: JsonObject,
    ) = PredictedContext(
        id = PredictedContextRepository.idFor(sourceKey),
        kind = kind,
        title = title.trim(),
        subtitle = subtitle?.trim()?.takeIf { it.isNotEmpty() },
        startMs = startMs,
        endMs = endMs,
        location = location?.trim()?.takeIf { it.isNotEmpty() },
        payloadJson = json.encodeToString(JsonObject.serializer(), payload),
        // Mail and calendar bodies are written by whoever sent them. The class travels
        // with the row so the ambient surface — and anything downstream of it — can never
        // mistake a parsed field for something the user said.
        provenance = Provenance.UNTRUSTED.name,
        source = source,
        sourceKey = sourceKey,
    )
}
