package org.ethereumphone.andyclaw.ledger

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ethereumphone.andyclaw.ledger.db.LedgerDao
import org.ethereumphone.andyclaw.ledger.db.entity.LedgerEntryEntity
import java.util.UUID

/** A ledger row with its list columns decoded — what a reader actually wants. */
data class LedgerEntry(
    val id: String,
    val seq: Long,
    val sessionId: String,
    val ts: Long,
    val kind: LedgerKind,
    val intent: String,
    val provenance: String,
    val routeRung: Int?,
    val flowRef: String?,
    val actions: List<LedgerAction>,
    val frames: List<String>,
    val outcome: LedgerOutcome,
    val modelIds: List<String>,
    val costUsd: Double?,
    val inputTokens: Int,
    val outputTokens: Int,
    val durationMs: Long,
    val prevHash: String,
    val hash: String,
) {
    companion object {
        fun from(e: LedgerEntryEntity): LedgerEntry = LedgerEntry(
            id = e.id,
            seq = e.seq,
            sessionId = e.sessionId,
            ts = e.ts,
            kind = runCatching { LedgerKind.valueOf(e.kind) }.getOrDefault(LedgerKind.TOOL),
            intent = e.intent,
            provenance = e.provenanceClass,
            routeRung = e.routeRung,
            flowRef = e.flowRef,
            actions = LedgerChain.decodeActions(e.actionsJson),
            frames = LedgerChain.decodeStrings(e.framesJson),
            outcome = runCatching { LedgerOutcome.valueOf(e.outcome) }.getOrDefault(LedgerOutcome.OK),
            modelIds = LedgerChain.decodeStrings(e.modelIdsJson),
            costUsd = e.costUsd,
            inputTokens = e.inputTokens,
            outputTokens = e.outputTokens,
            durationMs = e.durationMs,
            prevHash = e.prevHash,
            hash = e.hash,
        )
    }
}

/**
 * The one writer.
 *
 * A chain has exactly one place a row can be appended, so this is the only thing that
 * assigns `seq`, `prevHash` and `hash`, and it does so under a [Mutex]. That is not
 * defensive: agent runs overlap routinely on this device — a heartbeat fires while the
 * launcher chat is mid-turn — and two concurrent appends reading the same tail would
 * produce two rows claiming the same position, which is indistinguishable from tampering
 * when the chain is later verified.
 *
 * The tail is cached rather than re-read per append. The alternative is a query on the
 * latency path of every tool call, and the cache cannot go stale because nothing else
 * writes this table.
 *
 * **Retention.** The ledger is append-only but not infinite; a phone that keeps every row
 * forever eventually keeps nothing else. Rows are dropped from the **front** only, in
 * whole prefixes, so what remains still verifies end to end — see [LedgerDao].
 */
class LedgerRepository(
    private val dao: LedgerDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    private val mutex = Mutex()

    private var tail: LedgerEntryEntity? = null
    private var loaded = false
    private var known = 0

    /**
     * Append one row and return it as written.
     *
     * The only way a row enters the table. Callers that cannot suspend go through
     * [LedgerRecorder].
     */
    suspend fun append(draft: LedgerDraft): LedgerEntryEntity = mutex.withLock {
        ensureLoaded()

        val previous = tail
        val entity = LedgerEntryEntity(
            id = idGenerator(),
            seq = (previous?.seq ?: 0L) + 1,
            sessionId = draft.sessionId,
            ts = if (draft.ts != 0L) draft.ts else clock(),
            kind = draft.kind.name,
            intent = draft.intent.take(LedgerDraft.MAX_INTENT_CHARS),
            provenanceClass = draft.provenance,
            routeRung = draft.routeRung,
            flowRef = draft.flowRef,
            actionsJson = LedgerChain.encodeActions(draft.actions),
            framesJson = LedgerChain.encodeStrings(draft.frames),
            outcome = draft.outcome.name,
            modelIdsJson = LedgerChain.encodeStrings(draft.modelIds),
            costUsd = draft.costUsd,
            inputTokens = draft.inputTokens,
            outputTokens = draft.outputTokens,
            durationMs = draft.durationMs,
            prevHash = previous?.hash ?: LedgerChain.GENESIS,
            hash = "",
        )
        val sealed = entity.copy(hash = LedgerChain.hashOf(entity))

        dao.insert(sealed)
        tail = sealed
        known++
        pruneIfNeeded()
        sealed
    }

    /** Walk the whole retained chain. */
    suspend fun verify(): LedgerVerification = LedgerChain.verify(dao.getAll())

    suspend fun recent(limit: Int = 200): List<LedgerEntry> =
        dao.getRecent(limit).map(LedgerEntry::from)

    fun observeRecent(limit: Int = 200): Flow<List<LedgerEntry>> =
        dao.observeRecent(limit).map { rows -> rows.map(LedgerEntry::from) }

    /** Every row for one agent session, oldest first — a turn and the steps under it. */
    suspend fun session(sessionId: String): List<LedgerEntry> =
        dao.getBySession(sessionId).map(LedgerEntry::from)

    suspend fun count(): Int = dao.count()

    // ── Internals ─────────────────────────────────────────────────────

    private suspend fun ensureLoaded() {
        if (loaded) return
        tail = dao.getLast()
        known = dao.count()
        loaded = true
    }

    /**
     * Drop the oldest rows once the table has grown a whole slack window past the cap.
     *
     * Batched rather than one-for-one: a delete per append would put a write on the
     * latency path of every tool call to save a few kilobytes.
     */
    private suspend fun pruneIfNeeded() {
        if (known <= maxEntries + PRUNE_SLACK) return
        val cutoffSeq = (tail?.seq ?: return) - maxEntries
        if (cutoffSeq <= 0) return
        dao.deleteThroughSeq(cutoffSeq)
        known = dao.count()
    }

    companion object {
        /** Roughly a month of ordinary use. Rows are small; the cap is about disk, not privacy. */
        const val DEFAULT_MAX_ENTRIES = 20_000
        private const val PRUNE_SLACK = 1_000
    }
}
