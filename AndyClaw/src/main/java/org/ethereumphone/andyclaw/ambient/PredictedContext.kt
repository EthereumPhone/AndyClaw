package org.ethereumphone.andyclaw.ambient

/** What kind of thing is coming up. One per card type the ambient surface knows how to draw. */
enum class PredictedKind {
    FLIGHT,
    LODGING,
    EVENT,
    CALENDAR,
    OTHER;

    companion object {
        fun parse(name: String?): PredictedKind =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OTHER
    }
}

/**
 * One thing the device expects to matter to the user soon.
 *
 * This is the substrate the flagship demo needs and `andyclaw-to-agent-first.md` §3 says is
 * missing: the boarding pass on the lockscreen before you go looking for it. Nothing here
 * was decided by a model. Every field was parsed deterministically out of something that
 * was already structured — schema.org JSON-LD in a confirmation mail, the barcode payload
 * in a boarding pass, an iCal event — because the design doc's hard rule is that the model
 * chooses *when to surface* and never *what the gate number is*.
 *
 * [payloadJson] is the typed data, verbatim. It is stored as data and read as data; it is
 * never spliced into a prompt, which is the other half of the same rule — everything
 * ingested from mail is [org.ethereumphone.andyclaw.ExecutionEngine.Provenance.UNTRUSTED],
 * and untrusted content that reaches a prompt as prose is an injection channel.
 */
data class PredictedContext(
    /** Stable and derived from [sourceKey], so re-ingesting the same mail updates one row. */
    val id: String,
    val kind: PredictedKind,
    val title: String,
    val subtitle: String? = null,
    /** When the thing happens. The whole store is ordered around this. */
    val startMs: Long,
    val endMs: Long? = null,
    val location: String? = null,
    val payloadJson: String = "{}",
    /** Where the underlying content came from — nearly always `UNTRUSTED`. */
    val provenance: String = "UNTRUSTED",
    /** What this was parsed out of: `gmail:<messageId>`, `gcal:<eventId>`. */
    val source: String = "",
    /** The dedupe key: same real-world thing, same key, however many mails mention it. */
    val sourceKey: String,
    val createdMs: Long = 0L,
    val updatedMs: Long = 0L,
    /** Set when the user has waved it away. Scored zero from then on. */
    val dismissedMs: Long? = null,
)

/** A [PredictedContext] with its relevance worked out for one particular moment. */
data class ScoredContext(
    val context: PredictedContext,
    /** 0.0 (not relevant now) to 1.0 (happening). */
    val score: Double,
    /** Milliseconds until it starts. Negative once it has. */
    val untilStartMs: Long,
)

/**
 * Time-to-relevance, as a pure function.
 *
 * A flight matters a day out; a calendar event matters twenty minutes out; neither matters
 * a week early or a day late. So relevance is not "how soon" but "how far into this kind of
 * thing's own window are we" — which is why the lead-in is per [PredictedKind] and the
 * score is a ramp across it rather than a function of raw distance.
 *
 * Three regions, and the shape of each is the point:
 *
 * - **Before the window** — zero. Not a small number: a thing three weeks out must not
 *   outrank a thing this afternoon just because there are more of it.
 * - **Across the lead-in** — a straight ramp from 0 at the window's edge to 1 at the start.
 * - **While it is happening, and briefly after** — 1, then a decay across the tail, so the
 *   card does not vanish the instant the meeting begins.
 *
 * Pure, so the ambient surface can rank without a query and the ranking can be tested
 * without a clock.
 */
object PredictedContextScorer {

    /** How far ahead a thing of this kind starts being worth showing. */
    fun leadInMs(kind: PredictedKind): Long = when (kind) {
        PredictedKind.FLIGHT -> 24 * HOUR
        PredictedKind.LODGING -> 8 * HOUR
        PredictedKind.EVENT -> 3 * HOUR
        PredictedKind.CALENDAR -> 2 * HOUR
        PredictedKind.OTHER -> HOUR
    }

    /** How long it stays on screen after it is over. */
    fun tailMs(kind: PredictedKind): Long = when (kind) {
        PredictedKind.FLIGHT -> 3 * HOUR
        PredictedKind.LODGING -> 2 * HOUR
        PredictedKind.EVENT -> HOUR
        PredictedKind.CALENDAR -> 30 * MINUTE
        PredictedKind.OTHER -> 30 * MINUTE
    }

    /** The widest lead-in of any kind — how far ahead a query has to look. */
    val maxLeadInMs: Long = PredictedKind.entries.maxOf { leadInMs(it) }

    /** The longest tail of any kind — how far back a query has to look. */
    val maxTailMs: Long = PredictedKind.entries.maxOf { tailMs(it) }

    fun score(context: PredictedContext, nowMs: Long): Double {
        if (context.dismissedMs != null) return 0.0

        val start = context.startMs
        val end = context.endMs?.takeIf { it > start } ?: start
        val leadIn = leadInMs(context.kind)
        val tail = tailMs(context.kind)

        return when {
            nowMs < start - leadIn -> 0.0
            nowMs > end + tail -> 0.0
            nowMs < start -> {
                if (leadIn <= 0) 1.0 else (1.0 - (start - nowMs).toDouble() / leadIn).coerceIn(0.0, 1.0)
            }
            nowMs <= end -> 1.0
            else -> {
                if (tail <= 0) 0.0 else (1.0 - (nowMs - end).toDouble() / tail).coerceIn(0.0, 1.0)
            }
        }
    }

    fun scored(context: PredictedContext, nowMs: Long): ScoredContext =
        ScoredContext(context, score(context, nowMs), context.startMs - nowMs)

    /**
     * Rank a set for one moment: relevant first, and among equals the one happening soonest.
     *
     * Ties are common — everything in progress scores exactly 1 — so the second key is not
     * cosmetic. Without it the order of two live cards would be whatever the database
     * happened to return.
     */
    fun rank(contexts: List<PredictedContext>, nowMs: Long, minScore: Double = 0.0): List<ScoredContext> =
        contexts.asSequence()
            .map { scored(it, nowMs) }
            .filter { it.score > minScore }
            .sortedWith(compareByDescending<ScoredContext> { it.score }.thenBy { it.context.startMs })
            .toList()

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
}
