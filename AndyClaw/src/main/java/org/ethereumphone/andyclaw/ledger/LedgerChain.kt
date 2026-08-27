package org.ethereumphone.andyclaw.ledger

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.ledger.db.entity.LedgerEntryEntity
import java.security.MessageDigest

/**
 * What makes the ledger tamper-evident, as a pure function.
 *
 * Every row commits to the row before it: `hash = sha256(prev_hash || canonical(row))`.
 * Change any field of any row — an outcome, a cost, a provenance class — and its own hash
 * stops matching, and so does every hash after it. There is no key involved and none is
 * needed: the property is *detection*, not secrecy, and a chain that anyone can recompute
 * is one an exported ledger can be checked against off the device.
 *
 * The canonical form is length-prefixed rather than delimited, which is the whole reason
 * this is a separate object with its own tests. `a|b` and `ab|` are different documents
 * under `len:value|` and identical under a bare separator, so a delimited encoding would
 * let two different rows hash the same and quietly admit a forgery. Field order here is
 * part of the format: appending a field is safe, reordering or removing one invalidates
 * every chain already on a device.
 */
object LedgerChain {

    /** The `prev_hash` of the first row. 64 zeros, so every row has one the same shape. */
    const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

    private val json = Json {
        encodeDefaults = true
        prettyPrint = false
    }

    // ── Hashing ───────────────────────────────────────────────────────

    /**
     * The hash [entry] should carry, given its contents and its [LedgerEntryEntity.prevHash].
     *
     * [LedgerEntryEntity.hash] itself is excluded, because a value cannot commit to itself.
     */
    fun hashOf(entry: LedgerEntryEntity): String {
        val canonical = buildString {
            field(entry.prevHash)
            field(entry.id)
            field(entry.seq.toString())
            field(entry.sessionId)
            field(entry.ts.toString())
            field(entry.kind)
            field(entry.intent)
            field(entry.provenanceClass)
            field(entry.routeRung?.toString() ?: "")
            field(entry.flowRef ?: "")
            field(entry.actionsJson)
            field(entry.framesJson)
            field(entry.outcome)
            field(entry.modelIdsJson)
            field(entry.costUsd?.let { formatCost(it) } ?: "")
            field(entry.inputTokens.toString())
            field(entry.outputTokens.toString())
            field(entry.durationMs.toString())
        }
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    /**
     * A cost is a `Double` in the row and a string in the hash, so the two must agree on
     * how many digits there are. `toString()` would not: it is locale-independent but not
     * stable across the value's provenance (`0.1 + 0.2` prints differently from `0.3`),
     * and a chain that depends on floating-point printing is a chain that breaks for
     * reasons nobody can reproduce. Fixed at eight decimals — a hundredth of a
     * micro-dollar, far below anything a model call can cost.
     */
    private fun formatCost(value: Double): String =
        String.format(java.util.Locale.ROOT, "%.8f", value)

    private fun StringBuilder.field(value: String) {
        append(value.length).append(':').append(value).append('|')
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ── Verification ──────────────────────────────────────────────────

    /**
     * Walk [entries] in `seq` order and report the first thing that is wrong.
     *
     * [entries] may be a suffix of the chain: retention drops the oldest rows, so the
     * first row's `prev_hash` usually names a row that is no longer here. That link is
     * reported as unverifiable rather than broken — claiming tampering because a row was
     * legitimately aged out would make the check useless.
     */
    fun verify(entries: List<LedgerEntryEntity>): LedgerVerification {
        if (entries.isEmpty()) return LedgerVerification(ok = true, checkedLinks = 0)

        val ordered = entries.sortedBy { it.seq }
        var previous: LedgerEntryEntity? = null
        var links = 0

        for (entry in ordered) {
            if (entry.hash != hashOf(entry)) {
                return LedgerVerification(
                    ok = false,
                    checkedLinks = links,
                    brokenAtSeq = entry.seq,
                    reason = "row ${entry.seq} does not hash to the value it carries — it was edited after it was written",
                )
            }
            val prev = previous
            if (prev != null) {
                if (entry.seq != prev.seq + 1) {
                    return LedgerVerification(
                        ok = false,
                        checkedLinks = links,
                        brokenAtSeq = entry.seq,
                        reason = "sequence jumps from ${prev.seq} to ${entry.seq} — a row was removed from the middle",
                    )
                }
                if (entry.prevHash != prev.hash) {
                    return LedgerVerification(
                        ok = false,
                        checkedLinks = links,
                        brokenAtSeq = entry.seq,
                        reason = "row ${entry.seq} does not follow row ${prev.seq}",
                    )
                }
                links++
            }
            previous = entry
        }

        return LedgerVerification(
            ok = true,
            checkedLinks = links,
            firstSeq = ordered.first().seq,
            lastSeq = ordered.last().seq,
        )
    }

    // ── Encoding of the list-shaped columns ───────────────────────────

    fun encodeActions(actions: List<LedgerAction>): String =
        json.encodeToString(
            JsonArray.serializer(),
            buildJsonArray {
                for (a in actions) {
                    add(
                        buildJsonObject {
                            put("tool", a.tool)
                            put("ok", a.ok)
                            put("durationMs", a.durationMs)
                            if (a.note != null) put("note", a.note)
                        }
                    )
                }
            },
        )

    fun decodeActions(raw: String): List<LedgerAction> = try {
        json.parseToJsonElement(raw).let { it as? JsonArray ?: JsonArray(emptyList()) }.map { el ->
            val o = el.jsonObject
            LedgerAction(
                tool = o["tool"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                ok = o["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
                durationMs = o["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                note = o["note"]?.jsonPrimitive?.contentOrNull,
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun encodeStrings(values: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), values)

    fun decodeStrings(raw: String): List<String> = try {
        json.parseToJsonElement(raw).let { it as? JsonArray ?: JsonArray(emptyList()) }
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    } catch (e: Exception) {
        emptyList()
    }
}

/** The result of walking a chain. [checkedLinks] is how much was actually proven. */
data class LedgerVerification(
    val ok: Boolean,
    val checkedLinks: Int,
    val firstSeq: Long = 0L,
    val lastSeq: Long = 0L,
    val brokenAtSeq: Long? = null,
    val reason: String? = null,
)
