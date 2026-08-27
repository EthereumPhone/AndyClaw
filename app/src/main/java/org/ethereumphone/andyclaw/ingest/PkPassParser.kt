package org.ethereumphone.andyclaw.ingest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.time.ZoneId
import java.util.zip.ZipInputStream

/** A `.pkpass`, reduced to the parts that matter here. */
data class PkPass(
    val organizationName: String? = null,
    val description: String? = null,
    val serialNumber: String? = null,
    val relevantDateMs: Long? = null,
    val transitType: String? = null,
    /** Every barcode the pass offers, best first — passes carry a fallback for old scanners. */
    val barcodes: List<PkBarcode> = emptyList(),
    /** Every field of every group, by its `key`. */
    val fields: Map<String, String> = emptyMap(),
    val fieldLabels: Map<String, String> = emptyMap(),
) {
    val isAir: Boolean get() = transitType == "PKTransitTypeAir"
}

data class PkBarcode(val message: String, val format: String, val encoding: String? = null)

/**
 * Apple Wallet passes.
 *
 * A `.pkpass` is a zip with a `pass.json` in it, and `pass.json` already contains everything
 * a boarding-pass card needs — gate, seat, times, and the exact barcode payload — as
 * key/value fields. Reading it is unzip plus a JSON parse. There is nothing here to infer,
 * which is the whole reason `agent-os-design.md` §5 singles out PKPASS by name.
 *
 * The signature is **not** verified, and that is deliberate rather than an omission: the
 * signature says Apple issued the pass, and this code has no security decision resting on
 * that. What comes out is `UNTRUSTED` typed data on its way to a card, exactly like every
 * other ingested thing, and it never becomes prompt text.
 */
object PkPassParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Bound the unzip — a pass is tens of kilobytes and an unbounded one is a zip bomb. */
    private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024

    fun looksLikePkPass(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    /** `pass.json` out of the archive, or null if there is not one. */
    fun readPassJson(pkpass: ByteArray): String? {
        return try {
            ZipInputStream(ByteArrayInputStream(pkpass)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.substringAfterLast('/') == "pass.json") {
                        val out = zip.readBytesBounded(MAX_ENTRY_BYTES)
                        return@use String(out, Charsets.UTF_8)
                    }
                    entry = zip.nextEntry
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun parse(pkpass: ByteArray, zone: ZoneId = ZoneId.systemDefault()): PkPass? =
        readPassJson(pkpass)?.let { parseJson(it, zone) }

    fun parseJson(passJson: String, zone: ZoneId = ZoneId.systemDefault()): PkPass? {
        val root = runCatching { json.parseToJsonElement(passJson) as? JsonObject }.getOrNull() ?: return null

        val barcodes = buildList {
            (root["barcodes"] as? JsonArray)?.forEach { el ->
                (el as? JsonObject)?.let { b -> barcodeOf(b)?.let { add(it) } }
            }
            // The pre-iOS-9 singular key. Still emitted by most airlines alongside the array.
            (root["barcode"] as? JsonObject)?.let { b -> barcodeOf(b)?.let { add(it) } }
        }.distinctBy { it.message }

        val group = listOf("boardingPass", "eventTicket", "generic", "coupon", "storeCard")
            .firstNotNullOfOrNull { root[it] as? JsonObject }

        val fields = mutableMapOf<String, String>()
        val labels = mutableMapOf<String, String>()
        for (name in FIELD_GROUPS) {
            (group?.get(name) as? JsonArray)?.forEach { el ->
                val f = el as? JsonObject ?: return@forEach
                val key = f.str("key") ?: return@forEach
                f.str("value")?.let { fields[key] = it }
                f.str("label")?.let { labels[key] = it }
            }
        }

        return PkPass(
            organizationName = root.str("organizationName"),
            description = root.str("description"),
            serialNumber = root.str("serialNumber"),
            relevantDateMs = IsoDates.parseIso(root.str("relevantDate"), zone),
            transitType = group?.str("transitType"),
            barcodes = barcodes,
            fields = fields,
            fieldLabels = labels,
        )
    }

    /**
     * The flight this pass is for.
     *
     * The barcode is the authority for the itinerary — it is the same string the gate reads
     * — and the pass fields fill in what the barcode format has no room for: the gate, the
     * boarding time, the airline's own name for the flight. Where both know something, the
     * barcode wins, because it is the copy that has to be right.
     */
    fun toFlightReservation(pass: PkPass, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): FlightReservation? {
        if (!pass.isAir && pass.barcodes.isEmpty()) return null

        val bcbp = pass.barcodes.firstNotNullOfOrNull { BcbpParser.find(it.message, nowMs) }
        val raw = pass.barcodes.firstOrNull()

        val boardingPass = bcbp?.copy(
            // Keep Wallet's own format name — it says which symbology to render.
            format = raw?.format ?: bcbp.format,
            payload = raw?.message ?: bcbp.payload,
        ) ?: raw?.let { BoardingPass(payload = it.message, format = it.format) }

        val departureMs = pass.fields.firstOfKeys("boardingTime", "departureTime", "gateClosingTime")
            ?.let { IsoDates.parseIso(it, zone) }
            ?: pass.relevantDateMs
            ?: bcbp?.flightDate?.let { IsoDates.parseIso(it, zone) }

        val reservation = FlightReservation(
            reservationNumber = bcbp?.recordLocator ?: pass.fields.firstOfKeys("confirmationNumber", "pnr"),
            airlineName = pass.organizationName,
            airlineIata = bcbp?.carrier ?: pass.fields.firstOfKeys("carrier", "airline"),
            flightNumber = bcbp?.flightNumber ?: pass.fields.firstOfKeys("flightNumber", "flight"),
            departureAirport = bcbp?.fromAirport ?: pass.fields.firstOfKeys("origin", "from", "departure"),
            arrivalAirport = bcbp?.toAirport ?: pass.fields.firstOfKeys("destination", "to", "arrival"),
            departureTimeMs = departureMs,
            departureTerminal = pass.fields.firstOfKeys("terminal", "departureTerminal"),
            departureGate = pass.fields.firstOfKeys("gate", "departureGate"),
            passengerName = bcbp?.passengerName ?: pass.fields.firstOfKeys("passenger", "passengerName", "name"),
            seat = bcbp?.seat ?: pass.fields.firstOfKeys("seat", "seatNumber"),
            boardingPass = boardingPass,
        )
        val identified = reservation.flightNumber != null ||
            reservation.reservationNumber != null ||
            (reservation.departureAirport != null && reservation.arrivalAirport != null)
        return reservation.takeIf { identified }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun barcodeOf(node: JsonObject): PkBarcode? {
        val message = node.str("message") ?: return null
        return PkBarcode(
            message = message,
            format = node.str("format") ?: "PKBarcodeFormatQR",
            encoding = node.str("messageEncoding"),
        )
    }

    private fun Map<String, String>.firstOfKeys(vararg keys: String): String? {
        for (key in keys) {
            // Airlines name fields freely; `gate` is as likely to be `gateNumber`.
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.let { return it.value.trim().ifBlank { null } }
        }
        for (key in keys) {
            entries.firstOrNull { it.key.contains(key, ignoreCase = true) }?.let { return it.value.trim().ifBlank { null } }
        }
        return null
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun ZipInputStream.readBytesBounded(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) break
        }
        return out.toByteArray()
    }

    private val FIELD_GROUPS = listOf(
        "headerFields", "primaryFields", "secondaryFields", "auxiliaryFields", "backFields",
    )
}
