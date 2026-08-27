package org.ethereumphone.andyclaw.ingest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.ambient.PredictedContext
import org.ethereumphone.andyclaw.ambient.PredictedContextRepository
import org.ethereumphone.andyclaw.ambient.PredictedKind

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

    fun fromReservation(reservation: Reservation, source: String): PredictedContext? =
        when (reservation) {
            is FlightReservation -> fromFlight(reservation, source)
            is LodgingReservation -> fromLodging(reservation, source)
            is EventReservation -> fromEvent(reservation, source)
        }

    fun fromCalendarEvent(event: CalendarEvent, source: String): PredictedContext? {
        val start = event.startMs ?: return null
        val summary = event.summary?.takeIf { it.isNotBlank() } ?: return null
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
            },
        )
    }

    // ── Kinds ─────────────────────────────────────────────────────────

    private fun fromFlight(flight: FlightReservation, source: String): PredictedContext? {
        val start = flight.departureTimeMs ?: return null
        val subtitleParts = listOfNotNull(
            flight.departureTerminal?.let { "Terminal $it" },
            flight.departureGate?.let { "Gate $it" },
            flight.seat?.let { "Seat $it" },
        )
        return context(
            kind = PredictedKind.FLIGHT,
            title = "${flight.flightLabel}  ${flight.routeLabel}".trim(),
            subtitle = subtitleParts.joinToString(" · ").ifBlank { flight.reservationNumber },
            startMs = start,
            endMs = flight.arrivalTimeMs,
            location = flight.departureAirportName ?: flight.departureAirport,
            sourceKey = flight.sourceKey,
            source = source,
            payload = buildJsonObject {
                flight.reservationNumber?.let { put("reservation_number", it) }
                flight.airlineIata?.let { put("airline_iata", it) }
                flight.airlineName?.let { put("airline_name", it) }
                flight.flightNumber?.let { put("flight_number", it) }
                flight.departureAirport?.let { put("from", it) }
                flight.arrivalAirport?.let { put("to", it) }
                put("departure_ms", start)
                flight.arrivalTimeMs?.let { put("arrival_ms", it) }
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
            },
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
                lodging.guestName?.let { put("guest", it) }
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
                event.attendeeName?.let { put("attendee", it) }
                event.ticketToken?.let { put("ticket_token", it) }
            },
        )
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
