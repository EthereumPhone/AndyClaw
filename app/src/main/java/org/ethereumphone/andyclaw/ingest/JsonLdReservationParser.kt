package org.ethereumphone.andyclaw.ingest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.ethereumphone.andyclaw.ambient.TimePrecision
import java.time.ZoneId

/**
 * schema.org JSON-LD out of a mail body.
 *
 * Airlines, hotels and ticketing platforms embed a machine-readable copy of the booking in
 * every confirmation mail — that is what Gmail's own flight cards read. It arrives as a
 * `<script type="application/ld+json">` block, and parsing it is a JSON parse and a set of
 * field reads. There is no extraction problem here to solve with a model, which is exactly
 * `agent-os-design.md` §5's point.
 *
 * The parser is forgiving about shape and strict about meaning:
 *
 * - `@type` may be a string or an array; a document may be one object, an array of them, or
 *   a `@graph`. All of those appear in the wild and all of them mean the same thing.
 * - A field that is missing stays null. Nothing is inferred, defaulted, or guessed — a
 *   flight with no gate has no gate, and inventing one is the failure this whole path
 *   exists to avoid.
 * - A block that does not parse is skipped, not repaired. Mail bodies are truncated,
 *   re-encoded and mangled by every hop; one bad block must not take the good ones with it.
 */
object JsonLdReservationParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val scriptBlock = Regex(
        """<script[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /**
     * Every reservation in [body].
     *
     * [body] may be raw JSON-LD or an HTML mail containing one or more script blocks.
     */
    fun parse(body: String, zone: ZoneId = ZoneId.systemDefault()): List<Reservation> {
        if (body.isBlank()) return emptyList()

        val documents = buildList {
            for (match in scriptBlock.findAll(body)) {
                add(match.groupValues[1])
            }
            // A body that is itself a JSON document — Gmail's API can hand back a
            // `text/plain` part that is nothing but the markup.
            val trimmed = body.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) add(trimmed)
        }

        val out = mutableListOf<Reservation>()
        for (doc in documents) {
            val element = runCatching { json.parseToJsonElement(doc.trim()) }.getOrNull() ?: continue
            for (node in flatten(element)) {
                out += fromNode(node, zone) ?: continue
            }
        }
        return out.distinctBy { it.sourceKey }
    }

    // ── Shape ─────────────────────────────────────────────────────────

    /** Every object reachable from [element], including through `@graph` and nested arrays. */
    private fun flatten(element: JsonElement): List<JsonObject> = when (element) {
        is JsonArray -> element.flatMap { flatten(it) }
        is JsonObject -> {
            val graph = element["@graph"]
            if (graph != null) listOf(element) + flatten(graph) else listOf(element)
        }
        else -> emptyList()
    }

    private fun types(node: JsonObject): List<String> = when (val t = node["@type"]) {
        is JsonPrimitive -> listOfNotNull(t.contentOrNull)
        is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }

    // ── Mapping ───────────────────────────────────────────────────────

    /**
     * The most specific type wins.
     *
     * `"@type": ["Reservation", "FlightReservation"]` is a shape senders really use, and
     * taking the first entry that ends in `Reservation` picks the useless half of it. Every
     * declared type is tried against the three that mean something, and the first that
     * matches decides.
     */
    private fun fromNode(node: JsonObject, zone: ZoneId): Reservation? {
        for (type in types(node)) {
            when {
                type.endsWith("FlightReservation") -> return flight(node, zone)
                type.endsWith("LodgingReservation") -> return lodging(node, zone)
                type.endsWith("EventReservation") -> return event(node, zone)
            }
        }
        return null
    }

    private fun flight(node: JsonObject, zone: ZoneId): FlightReservation? {
        val f = node.obj("reservationFor") ?: return null
        val airline = f.obj("airline")
        val from = f.obj("departureAirport")
        val to = f.obj("arrivalAirport")
        // A departure given as a bare date is a day, not midnight: no time is invented for it.
        val departure = IsoDates.parseMoment(f.str("departureTime") ?: f.str("departureDate"), zone)
        val arrival = IsoDates.parseMoment(f.str("arrivalTime"), zone)?.takeIf { !it.dateOnly }

        val reservation = FlightReservation(
            reservationNumber = node.str("reservationNumber"),
            airlineName = airline?.str("name"),
            airlineIata = airline?.str("iataCode"),
            flightNumber = f.str("flightNumber"),
            departureAirport = from?.str("iataCode"),
            departureAirportName = from?.str("name"),
            arrivalAirport = to?.str("iataCode"),
            arrivalAirportName = to?.str("name"),
            departureTimeMs = departure?.takeIf { !it.dateOnly }?.epochMs,
            arrivalTimeMs = arrival?.epochMs,
            departureTerminal = f.str("departureTerminal"),
            departureGate = f.str("departureGate"),
            passengerName = node.obj("underName")?.str("name"),
            seat = node.obj("airplaneSeat")?.str("seatNumber") ?: node.str("airplaneSeat"),
            departureDate = departure?.localDate,
            departurePrecision = if (departure?.dateOnly == true) TimePrecision.DATE_ONLY else TimePrecision.EXACT,
            cancelled = isCancelled(node),
        )
        // A block with nothing identifying in it is markup noise, not a booking.
        val identified = reservation.flightNumber != null ||
            reservation.reservationNumber != null ||
            (reservation.departureAirport != null && reservation.arrivalAirport != null)
        return reservation.takeIf { identified }
    }

    /**
     * Most hotel mail says `checkinDate` / `checkoutDate`, not the `…Time` variant — and read
     * only as `…Time`, most hotels produced no card at all.
     */
    private fun lodging(node: JsonObject, zone: ZoneId): LodgingReservation? {
        val place = node.obj("reservationFor")
        val checkin = IsoDates.parseMoment(
            node.str("checkinTime") ?: place?.str("checkinTime") ?: node.str("checkinDate") ?: place?.str("checkinDate"),
            zone,
        )
        val checkout = IsoDates.parseMoment(
            node.str("checkoutTime") ?: place?.str("checkoutTime") ?: node.str("checkoutDate") ?: place?.str("checkoutDate"),
            zone,
        )
        val reservation = LodgingReservation(
            reservationNumber = node.str("reservationNumber"),
            name = place?.str("name"),
            address = place?.let { address(it) },
            checkinMs = checkin?.epochMs,
            checkoutMs = checkout?.epochMs,
            guestName = node.obj("underName")?.str("name"),
            checkinDate = checkin?.localDate,
            dateOnly = checkin?.dateOnly == true,
            cancelled = isCancelled(node),
        )
        return reservation.takeIf { it.name != null || it.reservationNumber != null }
    }

    private fun event(node: JsonObject, zone: ZoneId): EventReservation? {
        val e = node.obj("reservationFor")
        val start = IsoDates.parseMoment(e?.str("startDate"), zone)
        val reservation = EventReservation(
            reservationNumber = node.str("reservationNumber"),
            eventName = e?.str("name"),
            location = e?.obj("location")?.let { it.str("name") ?: address(it) },
            startTimeMs = start?.epochMs,
            endTimeMs = IsoDates.parseIso(e?.str("endDate"), zone),
            attendeeName = node.obj("underName")?.str("name"),
            ticketToken = node.str("ticketToken"),
            startDate = start?.localDate,
            dateOnly = start?.dateOnly == true,
            cancelled = isCancelled(node),
        )
        return reservation.takeIf { it.eventName != null || it.reservationNumber != null }
    }

    /**
     * `reservationStatus: ReservationCancelled`, in any of the spellings senders use (bare,
     * `http://schema.org/…`, `https://schema.org/…`). The cancellation mail becomes a
     * tombstone for the card the confirmation made, rather than being ignored.
     */
    private fun isCancelled(node: JsonObject): Boolean =
        node.str("reservationStatus")?.substringAfterLast('/')?.equals("ReservationCancelled", ignoreCase = true) == true

    /** A `PostalAddress`, flattened to one line, or a plain string address. */
    private fun address(node: JsonObject): String? {
        val postal = node.obj("address") ?: node.takeIf { types(it).any { t -> t.endsWith("PostalAddress") } }
        if (postal == null) return node.str("address")
        val parts = listOfNotNull(
            postal.str("streetAddress"),
            postal.str("addressLocality"),
            postal.str("postalCode"),
            postal.str("addressRegion"),
            postal.str("addressCountry") ?: postal.obj("addressCountry")?.str("name"),
        ).filter { it.isNotBlank() }
        return parts.joinToString(", ").ifBlank { node.str("address") }
    }

    // ── Field access ──────────────────────────────────────────────────

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.obj(key: String): JsonObject? = when (val v = this[key]) {
        is JsonObject -> v
        is JsonArray -> v.firstOrNull() as? JsonObject
        else -> null
    }
}
