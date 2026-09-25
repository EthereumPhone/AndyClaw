package org.ethereumphone.andyclaw.ledger

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.ethereumphone.andyclaw.ledger.db.entity.LedgerEntryEntity
import java.io.File
import java.io.Writer

/**
 * The ledger as JSONL, one row per line, oldest first — exportable *and verifiable*.
 *
 * Each line keeps the readable fields an export always had, plus `canonical`: the stored columns
 * exactly as they were hashed. The readable fields are decoded and re-encoded, which changes
 * bytes; recomputing `hash = sha256(prev_hash || canonical(row))` off the device needs the bytes
 * as they were.
 */
class LedgerExporter(
    private val repository: LedgerRepository,
    /** Waits for rows still queued; see [LedgerRecorder.drain]. */
    private val drain: suspend () -> Boolean = { true },
) {

    /** Writes the export to a new file in [dir] and returns it; the caller deletes it. */
    suspend fun export(dir: File): File {
        drain()
        val rows = repository.allStored()
        val file = File.createTempFile("ledger-export", ".jsonl", dir)
        try {
            file.bufferedWriter().use { write(rows, it) }
            return file
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    fun write(rows: List<LedgerEntryEntity>, out: Writer) {
        for (row in rows.sortedBy { it.seq }) {
            out.write(line(row).toString())
            out.write("\n")
        }
    }

    fun line(e: LedgerEntryEntity): JsonObject {
        val decoded = LedgerEntry.from(e)
        return JsonObject(
            linkedMapOf(
                "id" to JsonPrimitive(e.id),
                "seq" to JsonPrimitive(e.seq),
                "sessionId" to JsonPrimitive(e.sessionId),
                "ts" to JsonPrimitive(e.ts),
                "kind" to JsonPrimitive(e.kind),
                "intent" to JsonPrimitive(e.intent),
                "provenance" to JsonPrimitive(e.provenanceClass),
                "routeRung" to (e.routeRung?.let { JsonPrimitive(it) } ?: JsonNull),
                "flowRef" to (e.flowRef?.let { JsonPrimitive(it) } ?: JsonNull),
                "actions" to JsonArray(decoded.actions.map { a ->
                    JsonObject(buildMap {
                        put("tool", JsonPrimitive(a.tool))
                        put("ok", JsonPrimitive(a.ok))
                        put("durationMs", JsonPrimitive(a.durationMs))
                        a.note?.let { put("note", JsonPrimitive(it)) }
                    })
                }),
                "frames" to JsonArray(decoded.frames.map { JsonPrimitive(it) }),
                "outcome" to JsonPrimitive(e.outcome),
                "modelIds" to JsonArray(decoded.modelIds.map { JsonPrimitive(it) }),
                "costUsd" to (e.costUsd?.let { JsonPrimitive(it) } ?: JsonNull),
                "inputTokens" to JsonPrimitive(e.inputTokens),
                "outputTokens" to JsonPrimitive(e.outputTokens),
                "durationMs" to JsonPrimitive(e.durationMs),
                "prevHash" to JsonPrimitive(e.prevHash),
                "hash" to JsonPrimitive(e.hash),
                "canonical" to JsonObject(
                    mapOf(
                        "actionsJson" to JsonPrimitive(e.actionsJson),
                        "framesJson" to JsonPrimitive(e.framesJson),
                        "modelIdsJson" to JsonPrimitive(e.modelIdsJson),
                        "costUsd" to JsonPrimitive(e.costUsd?.let { String.format(java.util.Locale.ROOT, "%.8f", it) } ?: ""),
                    )
                ),
            )
        )
    }
}
