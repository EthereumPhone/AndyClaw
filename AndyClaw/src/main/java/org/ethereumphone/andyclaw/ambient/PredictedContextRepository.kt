package org.ethereumphone.andyclaw.ambient

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.ethereumphone.andyclaw.ambient.db.PredictedContextDao
import org.ethereumphone.andyclaw.ambient.db.entity.PredictedContextEntity
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * What the device thinks is about to matter, and how relevant each of those things is now.
 *
 * Writes are keyed by [PredictedContext.sourceKey], so ingesting the same flight from three
 * different mails converges on one row instead of three cards. Reads narrow in SQL and rank
 * in Kotlin — [PredictedContextScorer] owns the curve, and having exactly one place that
 * decides relevance is what makes it testable without a database.
 */
class PredictedContextRepository(
    private val dao: PredictedContextDao,
    /** The device's zone, read per call: only to recognise an older build's date-only rows. */
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Last, so `PredictedContextRepository(dao) { now }` keeps meaning the clock. */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val writeLock = Mutex()

    /**
     * Insert, or merge into the row for the same real-world thing.
     *
     * The merge is [PredictedContextPayload.merge]'s: the calendar outranks mail and signed
     * mail outranks unsigned, a vaguer time never replaces a precise one, a cancellation only
     * counts if it is newer than what it cancels, and a dismissed card comes back only for a
     * change a person would act on — a new gate, a delay, a boarding pass arriving — not for
     * the same mail being read again. `createdMs` and the id never change; `updatedMs` moves
     * only when something did.
     *
     * The row is found by key, or else *adopted*: an earlier build dated flight, hotel and event
     * keys in UTC, so the same booking can already be on file under a key one day off. Those
     * rows keep their id and the user's dismissal instead of becoming a second card.
     */
    suspend fun put(context: PredictedContext): PredictedContext = writeLock.withLock {
        val now = clock()
        val existing = dao.findBySourceKey(context.sourceKey) ?: adoptable(context)

        if (existing == null) {
            val entity = context.copy(
                id = context.id.ifBlank { idFor(context.sourceKey) },
                payloadJson = stampCancellation(context, now),
                createdMs = now,
                updatedMs = now,
            ).toEntity()
            dao.insert(entity)
            return@withLock entity.toDomain()
        }

        val before = existing.toDomain()
        val merged = PredictedContextPayload.merge(before, context, now, zone())
        // Re-reading a calendar sees every event again; only a real change is a write, and only
        // a real change moves `updatedMs` — which the launcher reads as "this card just changed".
        if (PredictedContextPayload.sameContent(merged, before)) return@withLock before
        val updated = merged.copy(updatedMs = now).toEntity()
        dao.update(updated)
        updated.toDomain()
    }

    /** A tombstone written from nothing still has to say when it was cancelled. */
    private fun stampCancellation(context: PredictedContext, now: Long): String {
        if (!context.cancelled) return context.payloadJson
        val payload = PredictedContextPayload.parse(context.payloadJson)
        if (payload.containsKey(PredictedContextPayload.CANCELLED_MS)) return context.payloadJson
        val observed = (payload[PredictedContextPayload.OBSERVED_MS] as? JsonPrimitive)?.longOrNull
        return PredictedContextPayload.encode(
            payload + (PredictedContextPayload.CANCELLED_MS to JsonPrimitive(observed?.takeIf { it > 0 } ?: now))
        )
    }

    /**
     * The row an earlier key rule filed this under, if there is one.
     *
     * Only dated keys (`flight:LH400:2026-09-01`) and only the same thing on either side of
     * the date: a start within 12 h, or 36 h when either side knows only the day. A flight
     * number flies once a day, so two precise times further apart than that are two flights.
     */
    private suspend fun adoptable(context: PredictedContext): PredictedContextEntity? {
        val prefix = datedKeyPrefix(context.sourceKey) ?: return null
        val incomingDateOnly = isDateOnly(context.payloadJson, context.startMs)
        return dao.findBySourceKeyLike(likePrefix(prefix))
            .asSequence()
            .filter { it.sourceKey != context.sourceKey && datedKeyPrefix(it.sourceKey) == prefix }
            .map { it to abs(it.startMs - context.startMs) }
            .filter { (row, distance) ->
                val window = if (incomingDateOnly || isDateOnly(row.payloadJson, row.startMs)) 36 * HOUR else 12 * HOUR
                distance <= window
            }
            .minByOrNull { it.second }
            ?.first
    }

    /**
     * Whether a row knows only its day. Rows this build wrote say so; an older build's rows
     * do not, and those it resolved from a bare date always start at local midnight.
     */
    private fun isDateOnly(payloadJson: String, startMs: Long): Boolean {
        val payload = PredictedContextPayload.parse(payloadJson)
        PredictedContextPayload.precision(payload)?.let { return it == TimePrecision.DATE_ONLY }
        return Instant.ofEpochMilli(startMs).atZone(zone()).toLocalTime() == LocalTime.MIDNIGHT
    }

    /** Everything relevant at [nowMs], most relevant first. */
    suspend fun relevantNow(nowMs: Long = clock(), limit: Int = 20): List<ScoredContext> {
        val window = dao.inWindow(
            from = nowMs - PredictedContextScorer.maxTailMs,
            to = nowMs + PredictedContextScorer.maxLeadInMs,
        )
        return PredictedContextScorer.rank(window.map { it.toDomain() }, nowMs).take(limit)
    }

    /** Everything upcoming, relevant or not — for a "what's ahead" list rather than a card stack. */
    suspend fun upcoming(nowMs: Long = clock(), limit: Int = 50): List<PredictedContext> =
        dao.getAll()
            .map { it.toDomain() }
            .filter { (it.endMs ?: it.startMs) >= nowMs && !it.cancelled }
            .sortedBy { it.startMs }
            .take(limit)

    suspend fun all(): List<PredictedContext> = dao.getAll().map { it.toDomain() }

    fun observeAll(): Flow<List<PredictedContext>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /**
     * Under the write lock: a dismissal landing between a merge's read and its write used to
     * be overwritten by the merge, and the card the user had just waved away came straight back.
     */
    suspend fun dismiss(id: String) = writeLock.withLock { dao.dismiss(id, clock()) }

    suspend fun delete(id: String) = writeLock.withLock { dao.deleteById(id) }

    /**
     * Forget everything a source wrote — its name starting with [sourcePrefix] — when the
     * account behind it is disconnected: PNRs, names and barcodes are not kept for an account
     * the user has let go of.
     */
    suspend fun clearSource(sourcePrefix: String) =
        writeLock.withLock { dao.deleteBySourceLike(likePrefix(sourcePrefix)) }

    /**
     * After a complete read of a live calendar: its rows in [fromMs]..[toMs] that it no longer
     * returned were deleted or declined there, so they go here too. Returns how many.
     */
    suspend fun reconcile(source: String, fromMs: Long, toMs: Long, keep: Set<String>): Int = writeLock.withLock {
        val stale = dao.getAll().filter { it.source == source && it.startMs in fromMs..toMs && it.sourceKey !in keep }
        for (row in stale) dao.deleteById(row.id)
        stale.size
    }

    /** Drop anything whose tail has run out. Cheap, and keeps the table roughly trip-sized. */
    suspend fun purgeExpired(nowMs: Long = clock()) =
        dao.deleteEndedBefore(nowMs - PredictedContextScorer.maxTailMs)

    suspend fun clear() = writeLock.withLock { dao.deleteAll() }

    companion object {
        private const val HOUR = 60 * 60 * 1000L
        private val DATED_KEY = Regex("""^(.+:)\d{4}-\d{2}-\d{2}$""")

        /** `flight:LH400:` for `flight:LH400:2026-09-01`; null for a key with no date on the end. */
        fun datedKeyPrefix(sourceKey: String): String? = DATED_KEY.find(sourceKey)?.groupValues?.get(1)

        /** [prefix] as a `LIKE` pattern matching everything that starts with it, literally. */
        private fun likePrefix(prefix: String): String =
            prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

        /**
         * A deterministic id from the dedupe key.
         *
         * Deliberately not a random UUID: an ingest that re-derives the same key must land
         * on the same id even if the row was pruned and is being recreated, so a card the
         * user dismissed and a card the ambient surface is holding a reference to stay the
         * same card.
         */
        fun idFor(sourceKey: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(sourceKey.toByteArray(Charsets.UTF_8))
                .take(16)
                .joinToString("") { "%02x".format(it) }
    }
}

internal fun PredictedContext.toEntity(): PredictedContextEntity = PredictedContextEntity(
    id = id.ifBlank { PredictedContextRepository.idFor(sourceKey) },
    kind = kind.name,
    title = title,
    subtitle = subtitle,
    startMs = startMs,
    endMs = endMs,
    location = location,
    payloadJson = payloadJson,
    provenance = provenance,
    source = source,
    sourceKey = sourceKey,
    createdMs = createdMs,
    updatedMs = updatedMs,
    dismissedMs = dismissedMs,
)

internal fun PredictedContextEntity.toDomain(): PredictedContext = PredictedContext(
    id = id,
    kind = PredictedKind.parse(kind),
    title = title,
    subtitle = subtitle,
    startMs = startMs,
    endMs = endMs,
    location = location,
    payloadJson = payloadJson,
    provenance = provenance,
    source = source,
    sourceKey = sourceKey,
    createdMs = createdMs,
    updatedMs = updatedMs,
    dismissedMs = dismissedMs,
)
