package org.ethereumphone.andyclaw.ambient

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ethereumphone.andyclaw.ambient.db.PredictedContextDao
import org.ethereumphone.andyclaw.ambient.db.entity.PredictedContextEntity
import java.security.MessageDigest

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
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val writeLock = Mutex()

    /**
     * Insert or update the row for this [PredictedContext.sourceKey].
     *
     * An update keeps the original `createdMs` and, deliberately, the user's
     * `dismissedMs`: a re-ingest of the same flight is not a reason to put a card back on
     * screen that the user has already waved away. A genuinely changed time is a different
     * matter and does clear it — a gate change is exactly when the card should come back.
     */
    suspend fun put(context: PredictedContext): PredictedContext = writeLock.withLock {
        val now = clock()
        val existing = dao.findBySourceKey(context.sourceKey)

        if (existing == null) {
            val entity = context.copy(
                id = context.id.ifBlank { idFor(context.sourceKey) },
                createdMs = now,
                updatedMs = now,
            ).toEntity()
            dao.insert(entity)
            return@withLock entity.toDomain()
        }

        val timeChanged = existing.startMs != context.startMs || existing.endMs != context.endMs
        val updated = existing.copy(
            kind = context.kind.name,
            title = context.title,
            subtitle = context.subtitle,
            startMs = context.startMs,
            endMs = context.endMs,
            location = context.location,
            payloadJson = context.payloadJson,
            provenance = context.provenance,
            source = context.source.ifBlank { existing.source },
            updatedMs = now,
            dismissedMs = if (timeChanged) null else existing.dismissedMs,
        )
        dao.update(updated)
        updated.toDomain()
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
            .filter { (it.endMs ?: it.startMs) >= nowMs }
            .sortedBy { it.startMs }
            .take(limit)

    suspend fun all(): List<PredictedContext> = dao.getAll().map { it.toDomain() }

    fun observeAll(): Flow<List<PredictedContext>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun dismiss(id: String) = dao.dismiss(id, clock())

    /** Drop anything whose tail has run out. Cheap, and keeps the table roughly trip-sized. */
    suspend fun purgeExpired(nowMs: Long = clock()) =
        dao.deleteEndedBefore(nowMs - PredictedContextScorer.maxTailMs)

    suspend fun clear() = dao.deleteAll()

    companion object {
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
