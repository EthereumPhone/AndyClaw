package org.ethereumphone.andyclaw.services

import org.ethereumphone.andyclaw.heartbeat.HeartbeatLogEntry
import org.ethereumphone.andyclaw.ledger.LedgerDigest
import org.ethereumphone.andyclaw.ledger.LedgerEntry
import org.ethereumphone.andyclaw.ledger.LedgerKind
import org.ethereumphone.andyclaw.ledger.ReplaySession
import org.ethereumphone.andyclaw.safety.ToolEffects
import org.ethereumphone.andyclaw.skills.ToolEffect
import org.json.JSONArray
import org.json.JSONObject

/**
 * The launcher's list replies, sized by what they cost on the wire rather than by row count
 * (IPC-04, SET-19).
 *
 * A binder reply has to fit the receiving process's ~1 MB transaction buffer, shared with
 * everything else in flight (frames included), and an AIDL `String` costs two bytes a character.
 * Three hundred ledger rows with a few display recordings came to ~1 MB: the call failed, and
 * the launcher showed an empty ledger — on the one screen whose job is to be the record. So every
 * list stops adding rows at [MAX_CHARS] of JSON (~512 KB on the wire); a capped list simply has
 * fewer rows, and a session says `truncated`.
 */
internal object LauncherReplies {

    /** The most JSON one list reply carries: 256 000 characters, ~512 KB as UTF-16. */
    const val MAX_CHARS = 256_000

    data class Capped(val array: JSONArray, val total: Int) {
        val kept: Int get() = array.length()
        val truncated: Boolean get() = kept < total
    }

    /**
     * [rows] in order, as many as fit in [maxChars] of JSON — contiguous from the first, so what
     * is shown has no holes. [Capped.total] is how many there were.
     */
    fun cap(rows: List<JSONObject>, maxChars: Int = MAX_CHARS): Capped {
        val array = JSONArray()
        var size = 2 // []
        for (row in rows) {
            val cost = row.toString().length + if (array.length() > 0) 1 else 0
            if (size + cost > maxChars) break
            array.put(row)
            size += cost
        }
        return Capped(array, rows.size)
    }

    // ── Ledger ───────────────────────────────────────────────────────

    /** A step that changed something outside the process: anything not a pure read. */
    fun isSideEffect(tool: String): Boolean =
        tool != "agent_display_capture" && ToolEffects.of(tool) != ToolEffect.READ

    /**
     * One ledger row for the launcher. [steps] are the TOOL rows a TURN row owns
     * ([LedgerDigest.attribute]); with them a turn also says what it did. The derived keys are
     * computed here and never stored or exported.
     *
     * `costUsd` is written as an explicit null when it is unknown rather than omitted or zeroed:
     * a viewer that renders an unknown price as free is lying in the one screen whose job is being
     * trustworthy.
     */
    fun ledgerEntry(row: LedgerEntry, steps: List<LedgerEntry>? = null): JSONObject {
        val actions = JSONArray()
        for (a in row.actions) {
            actions.put(JSONObject().apply {
                put("tool", a.tool)
                put("ok", a.ok)
                put("durationMs", a.durationMs)
                a.note?.let { put("note", it) }
            })
        }
        val frames = JSONArray()
        for (f in row.frames) frames.put(f)
        val models = JSONArray()
        for (m in row.modelIds) models.put(m)
        return JSONObject().apply {
            put("id", row.id)
            put("seq", row.seq)
            put("sessionId", row.sessionId)
            put("ts", row.ts)
            put("kind", row.kind.name)
            put("intent", row.intent)
            put("provenance", row.provenance)
            if (row.routeRung != null) put("routeRung", row.routeRung) else put("routeRung", JSONObject.NULL)
            if (row.flowRef != null) put("flowRef", row.flowRef) else put("flowRef", JSONObject.NULL)
            put("actions", actions)
            // Still whole this release, for an older launcher; frameCount is what a newer one
            // reads, and the ids are also in getLedgerSession's frames[].
            put("frames", frames)
            put("frameCount", row.frames.size)
            put("outcome", row.outcome.name)
            put("modelIds", models)
            if (row.costUsd != null) put("costUsd", row.costUsd) else put("costUsd", JSONObject.NULL)
            put("inputTokens", row.inputTokens)
            put("outputTokens", row.outputTokens)
            put("durationMs", row.durationMs)
            put("prevHash", row.prevHash)
            put("hash", row.hash)
            // Derived. `intent` stays as stored, for an older launcher and for the chain; what
            // to show is `displayIntent`, which is never somebody else's message.
            put("displayIntent", LedgerDigest.displayIntent(row))
            put("trigger", LedgerDigest.trigger(row).wire)
            if (row.kind == LedgerKind.TURN && steps != null) {
                val summary = LedgerDigest.summary(row, steps, ::isSideEffect)
                put("actedOnBehalf", LedgerDigest.actedOnBehalf(row, summary))
                put("summary", JSONObject().apply {
                    put("toolsRun", summary.toolsRun)
                    put("toolsBlocked", summary.toolsBlocked)
                    put("toolErrors", summary.toolErrors)
                    put("sideEffects", summary.sideEffects)
                    put("sideEffectTools", JSONArray(summary.sideEffectTools))
                    put("frames", summary.frames)
                })
            }
        }
    }

    /**
     * `getLedgerEntries`: the first [limit] of [rows] (newest first, as the repository reads
     * them; rows past [limit] are there only to attribute steps), as many as fit.
     */
    fun ledgerEntries(rows: List<LedgerEntry>, limit: Int, maxChars: Int = MAX_CHARS): String {
        val steps = LedgerDigest.attribute(rows)
        return cap(rows.take(limit).map { ledgerEntry(it, steps[it.id]) }, maxChars).array.toString()
    }

    /**
     * `getLedgerSession`: the whole frame list, and the newest rows that fit in what it leaves —
     * at most [maxRows] of them. `truncated` says rows were left out, and a launcher that reads
     * it says "Showing the newest N steps of this run".
     */
    fun ledgerSession(replay: ReplaySession, maxRows: Int, maxChars: Int = MAX_CHARS): JSONObject {
        val steps = LedgerDigest.attribute(replay.entries)
        val frames = JSONArray()
        for (f in replay.frames) {
            frames.put(JSONObject().apply {
                put("id", f.id)
                put("index", f.index)
                put("timestampMs", f.timestampMs)
                put("sizeBytes", f.sizeBytes)
            })
        }
        val missing = JSONArray()
        for (m in replay.missingFrames) missing.put(m)
        val shell = JSONObject().apply {
            put("sessionId", replay.sessionId)
            put("intent", replay.turn?.let { LedgerDigest.displayIntent(it) } ?: "")
            put("truncated", false)
            put("startedMs", replay.startedMs)
            put("endedMs", replay.endedMs)
            put("entries", JSONArray())
            put("frames", frames)
            // Named by the rows, gone from disk. Surfaced rather than hidden: a replay that
            // quietly plays a shorter version misrepresents the run.
            put("missingFrames", missing)
        }
        // Newest first until the budget the rest of the reply leaves, then back in order.
        val newestFirst = replay.entries.takeLast(maxRows).asReversed().map { ledgerEntry(it, steps[it.id]) }
        val kept = cap(newestFirst, (maxChars - shell.toString().length).coerceAtLeast(0))
        val entries = JSONArray()
        for (i in kept.kept - 1 downTo 0) entries.put(kept.array.get(i))
        return shell.apply {
            put("entries", entries)
            put("truncated", kept.kept < replay.entries.size)
        }
    }

    // ── Heartbeat logs ───────────────────────────────────────────────

    /** `getHeartbeatLogs`: [entries] newest first, as many as fit. */
    fun heartbeatLogs(entries: List<HeartbeatLogEntry>, maxChars: Int = MAX_CHARS): String =
        cap(entries.map { entry ->
            val toolCalls = JSONArray()
            for (tc in entry.toolCalls) {
                toolCalls.put(JSONObject().apply {
                    put("toolName", tc.toolName)
                    put("result", tc.result)
                })
            }
            JSONObject().apply {
                put("timestampMs", entry.timestampMs)
                put("outcome", entry.outcome)
                put("prompt", entry.prompt)
                put("responseText", entry.responseText)
                entry.error?.let { put("error", it) }
                put("durationMs", entry.durationMs)
                put("toolCalls", toolCalls)
            }
        }, maxChars).array.toString()
}
