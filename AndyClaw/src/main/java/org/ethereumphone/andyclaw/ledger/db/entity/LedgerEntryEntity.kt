package org.ethereumphone.andyclaw.ledger.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One ledger row, exactly as it sits on disk.
 *
 * `agent-os-design.md` §6's entry shape — `{ts, intent, provenance_class, flow_id@version,
 * actions[], screenshots[], outcome, model_ids, cost, prev_hash}` — plus the two fields
 * that make it a chain rather than a list: [seq] and [hash].
 *
 * **Append-only.** [org.ethereumphone.andyclaw.ledger.db.LedgerDao] has an insert and
 * queries and no update at all. Room will happily generate an `@Update` if one is added
 * later; do not. A row that can be rewritten is a log, and the point of this table is that
 * it is not one.
 *
 * Lists are stored as JSON text rather than through a type converter so the on-disk form
 * stays legible to an exporter and to a future build that has forgotten what the converter
 * did — the same reasoning `flows/` uses for its canonical JSON.
 */
@Entity(
    tableName = "ledger_entries",
    indices = [
        Index(value = ["seq"], unique = true),
        Index("sessionId"),
        Index("ts"),
    ],
)
data class LedgerEntryEntity(
    @PrimaryKey val id: String,
    /** Position in the chain. Monotonic, gapless, assigned by the single writer. */
    val seq: Long,
    val sessionId: String,
    val ts: Long,
    /** [org.ethereumphone.andyclaw.ledger.LedgerKind] name. */
    val kind: String,
    val intent: String,
    /** [org.ethereumphone.andyclaw.ExecutionEngine.Provenance] name. */
    val provenanceClass: String,
    /** Rung of `agent-os-design.md` §3's execution ladder, when the step is on it. */
    val routeRung: Int?,
    val flowRef: String?,
    /** JSON array of `{tool, ok, durationMs, note}`. */
    val actionsJson: String,
    /** JSON array of frame ids. */
    val framesJson: String,
    /** [org.ethereumphone.andyclaw.ledger.LedgerOutcome] name. */
    val outcome: String,
    /** JSON array of model ids. */
    val modelIdsJson: String,
    val costUsd: Double?,
    val inputTokens: Int,
    val outputTokens: Int,
    val durationMs: Long,
    val prevHash: String,
    val hash: String,
)
