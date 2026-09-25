package org.ethereumphone.andyclaw.ambient

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * How precisely a source knew when something happens.
 *
 * A confirmation mail knows the departure to the minute. A Wallet pass often knows only when
 * boarding starts. A bare boarding-pass barcode knows the day and nothing else. Treating all
 * three as "a time" is how a boarding pass processed after the confirmation turned a 09:40
 * departure into 00:00: ranked lowest to highest, so the most precise source always wins.
 */
enum class TimePrecision {
    DATE_ONLY,
    BOARDING,
    EXACT,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun parse(value: String?): TimePrecision? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The keys of [PredictedContext.payloadJson] the store itself reads, and the rules it applies
 * to them when two sources describe the same real-world thing.
 *
 * The payload is the card's data, verbatim from its source; these few keys are bookkeeping
 * carried alongside it, additive and ignored by anything that does not know them.
 */
object PredictedContextPayload {

    /** The source said this booking or event is cancelled. The card is a tombstone. */
    const val CANCELLED = "cancelled"
    /** When the cancelling message was received. A newer confirmation can undo it; an older one cannot. */
    const val CANCELLED_MS = "cancelled_ms"
    /** When the message this row last took data from was received (or, for a calendar, fetched). */
    const val OBSERVED_MS = "observed_ms"
    /** Mail whose DKIM signature passed, aligned with its From domain. False for everything else. */
    const val AUTHENTICATED = "authenticated"
    /** One of [TimePrecision.wire]. */
    const val TIME_PRECISION = "time_precision"
    /** True when only the day is known; the card spans that whole local day. */
    const val DATE_ONLY = "date_only"

    /**
     * Keys that describe *when*. They travel together: a less precise source must not replace
     * any of them, or a boarding pass that knows only the day erases the departure time.
     */
    val TIME_KEYS = setOf(
        TIME_PRECISION, DATE_ONLY,
        "departure_ms", "boarding_ms", "arrival_ms", "departure_date",
        "start_ms", "end_ms", "start_date",
        "checkin_ms", "checkout_ms", "checkin_date",
    )

    /** Sources that are the calendar itself rather than a mail about it. */
    val LIVE_CALENDAR_SOURCES = setOf("gcal", "device-calendar")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(payloadJson: String?): JsonObject =
        runCatching { json.parseToJsonElement(payloadJson.orEmpty()) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    fun isCancelled(payloadJson: String?): Boolean {
        // Cheap reject first: this runs for every row on every ranking.
        if (payloadJson == null || !payloadJson.contains(CANCELLED)) return false
        return parse(payloadJson).bool(CANCELLED)
    }

    /**
     * How much a source is believed, highest first: the calendar itself, then mail that proved
     * who sent it, then mail that did not. A weaker source may fill gaps in what a stronger one
     * said and may never overwrite, cancel or revive it — which is what keeps a spoofed "gate
     * change" mail from rewriting a real boarding pass.
     */
    fun authority(source: String, payload: JsonObject): Int = when {
        source in LIVE_CALENDAR_SOURCES -> 3
        payload[AUTHENTICATED]?.let { (it as? JsonPrimitive)?.booleanOrNull } == false -> 1
        else -> 2
    }

    fun precision(payload: JsonObject): TimePrecision? = TimePrecision.parse(payload.str(TIME_PRECISION))

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    /**
     * Whether two versions of a row say the same thing: every field equal, and the payloads
     * equal as data — key order aside, and apart from [OBSERVED_MS], which only records when
     * the row was last confirmed.
     */
    fun sameContent(a: PredictedContext, b: PredictedContext): Boolean {
        if (a.copy(payloadJson = "") != b.copy(payloadJson = "")) return false
        if (a.payloadJson == b.payloadJson) return true
        return parse(a.payloadJson) - OBSERVED_MS == parse(b.payloadJson) - OBSERVED_MS
    }

    fun encode(payload: Map<String, JsonElement>): String =
        json.encodeToString(JsonObject.serializer(), JsonObject(payload))

    /**
     * The flight card's second line, from whatever the merged payload now knows.
     *
     * Recomputed after every merge rather than taken from the newest source: the boarding pass
     * knows the gate and seat, the confirmation knows the terminal, and the card should say all
     * three however the two arrived.
     */
    fun flightSubtitle(payload: JsonObject): String? {
        val parts = listOfNotNull(
            payload.str("terminal")?.let { "Terminal $it" },
            payload.str("gate")?.let { "Gate $it" },
            payload.str("seat")?.let { "Seat $it" },
        )
        return parts.joinToString(" · ").ifBlank { payload.str("reservation_number") }
    }

    /**
     * What decides whether a card the user dismissed comes back.
     *
     * Only the things that make the card worth looking at again: a departure that moved, a
     * new gate or terminal, a boarding pass that has just arrived, a cancellation. A re-ingest
     * that changes nothing a person would act on — the same mail seen again — leaves the
     * dismissal alone.
     */
    fun materialFingerprint(c: PredictedContext): String {
        val p = parse(c.payloadJson)
        val minute = c.startMs / 60_000L
        val endMinute = c.endMs?.let { it / 60_000L }
        val cancelled = p.bool(CANCELLED)
        return when (c.kind) {
            PredictedKind.FLIGHT -> listOf(minute, p.str("gate"), p.str("terminal"), p.containsKey("barcode_payload"), cancelled)
            PredictedKind.LODGING -> listOf(minute, endMinute, c.title, c.location, cancelled)
            PredictedKind.EVENT -> listOf(minute, c.location, cancelled)
            PredictedKind.CALENDAR -> listOf(minute, endMinute, c.location, cancelled)
            PredictedKind.OTHER -> listOf(minute, cancelled)
        }.joinToString("|")
    }

    /**
     * [incoming] folded into [existing], which keeps its identity (id, key, creation time).
     *
     * - A **weaker** source changes nothing. Not even gaps: a spoofed mail that adds a gate
     *   to a real confirmation which never stated one is exactly the attack to stop.
     * - A **stronger** source replaces what a weaker one said, wholesale, so unverified data
     *   never survives verified data.
     * - An **equal** source wins field by field, but the payload is a union: a re-ingest that
     *   failed to fetch one attachment no longer erases the barcode it carried. Times follow
     *   precision — a less precise source never replaces a more precise time — and
     *   cancellation follows recency: a cancellation older than the confirmation after it is
     *   ignored, and a cancelled booking comes back only from a newer message.
     * - A **material** change clears a dismissal; anything else keeps it.
     */
    fun merge(existing: PredictedContext, incoming: PredictedContext, nowMs: Long): PredictedContext {
        val pe = parse(existing.payloadJson)
        val pi = parse(incoming.payloadJson)
        val ae = authority(existing.source, pe)
        val ai = authority(incoming.source, pi)
        if (ai < ae) return existing

        val eCancelled = pe.bool(CANCELLED)
        val iCancelled = pi.bool(CANCELLED)
        val eObserved = pe.long(OBSERVED_MS) ?: 0L
        val iObserved = pi.long(OBSERVED_MS) ?: 0L

        val payload = LinkedHashMap<String, JsonElement>()
        val keepTimes: Boolean
        if (ai > ae) {
            payload.putAll(pi)
            keepTimes = false
        } else {
            // A message older than what the row already reflects cannot change its fate.
            if (iCancelled && !eCancelled && iObserved in 1 until eObserved) return existing
            if (!iCancelled && eCancelled && iObserved <= (pe.long(CANCELLED_MS) ?: eObserved)) return existing

            val pE = precision(pe)
            val pI = precision(pi) ?: TimePrecision.EXACT
            keepTimes = pE != null && pI < pE
            payload.putAll(pe)
            for ((k, v) in pi) if (k !in TIME_KEYS) payload[k] = v
            val timeSource = if (keepTimes) pe else pi
            if (keepTimes || precision(pi) != null) {
                // The time keys travel together, all from the more precise source.
                for (k in TIME_KEYS) timeSource[k]?.let { payload[k] = it } ?: payload.remove(k)
            } else {
                for (k in TIME_KEYS) pi[k]?.let { payload[k] = it }
            }
            maxOf(eObserved, iObserved).takeIf { it > 0 }?.let { payload[OBSERVED_MS] = JsonPrimitive(it) }
        }
        if (iCancelled) {
            payload[CANCELLED] = JsonPrimitive(true)
            payload[CANCELLED_MS] = JsonPrimitive(iObserved.takeIf { it > 0 } ?: nowMs)
        } else {
            payload.remove(CANCELLED)
            payload.remove(CANCELLED_MS)
        }

        val dateOnlyBefore = precision(pe) == TimePrecision.DATE_ONLY
        val merged = existing.copy(
            kind = incoming.kind,
            title = incoming.title.ifBlank { existing.title },
            subtitle = incoming.subtitle ?: existing.subtitle.takeIf { ai == ae },
            startMs = if (keepTimes) existing.startMs else incoming.startMs,
            endMs = when {
                keepTimes -> existing.endMs
                ai > ae -> incoming.endMs
                // A whole-day span is the date-only card's shape; it means nothing next to a real time.
                else -> incoming.endMs ?: existing.endMs.takeUnless { dateOnlyBefore && precision(pi) != TimePrecision.DATE_ONLY }
            },
            location = incoming.location ?: existing.location.takeIf { ai == ae },
            payloadJson = encode(payload),
            provenance = incoming.provenance,
            source = incoming.source.ifBlank { existing.source },
        )

        val withSubtitle = if (merged.kind == PredictedKind.FLIGHT) {
            merged.copy(subtitle = flightSubtitle(parse(merged.payloadJson)) ?: merged.subtitle)
        } else merged

        val material = materialFingerprint(existing) != materialFingerprint(withSubtitle)
        return withSubtitle.copy(dismissedMs = if (material) null else existing.dismissedMs)
    }
}
