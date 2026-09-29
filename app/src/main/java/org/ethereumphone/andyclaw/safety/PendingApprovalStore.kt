package org.ethereumphone.andyclaw.safety

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * The queue behind the "raise a card for the user to approve" leg of the trust model.
 *
 * When a run triggered by untrusted content asks for something irreversible, a headless runner
 * has nobody to ask — auto-approving would make the gate a no-op, and dropping it silently would
 * make the agent look broken. So the request is refused and recorded here, for the home screen to
 * render as a card, and APPROVE executes **exactly the call that was refused** — the tool and
 * the input as stored, under the provenance it came with, never re-derived by a model
 * ([PendingApprovalExecutor]).
 *
 * That makes this file something that can cause an action, so:
 * - An entry is executable only if this build wrote it (version [VERSION]), its input was small
 *   and free of secrets when it was stored, and its [ApprovalMac] signature still checks out.
 *   Anything else — an entry an older build rewrote, a tampered one — can be declined, never run.
 * - It runs at most once. [claim] moves it to EXECUTING in the same atomic write that lets it
 *   run; an EXECUTING entry found by a new process may have run and is never run again.
 * - It expires after [TTL_MS], and one conversation can hold at most [MAX_PER_CONVERSATION]
 *   of the [MAX_PENDING] slots, so a stranger cannot bury the owner's cards.
 * - Finished requests leave this file for [OUTCOMES_FILENAME], so a rollback to a build that
 *   only knows this file cannot bring an executed request back as pending.
 *
 * Storage is `filesDir/pending_approvals.json` — the app's own sandbox; `/data/andyclaw_files/`
 * belongs to `system_server`. The format is additive: an older build reads the fields it knows
 * and ignores the rest, and fields this build does not know are written back untouched.
 */
class PendingApprovalStore(
    private val dir: File,
    private val mac: ApprovalMac,
    private val clock: () -> Long = System::currentTimeMillis,
    /** True when a tool input carries something that must never be written down: a key, a seed. */
    private val holdsSecret: (String) -> Boolean = { false },
) {

    constructor(context: Context) : this(
        dir = context.filesDir,
        mac = KeystoreApprovalMac(),
        holdsSecret = Companion::holdsSecret,
    )

    companion object {
        private const val TAG = "PendingApprovalStore"
        const val FILENAME = "pending_approvals.json"
        const val OUTCOMES_FILENAME = "approval_outcomes.json"
        /** The only entry version this build will execute. */
        const val VERSION = 2
        const val MAX_PENDING = 10
        const val MAX_PER_CONVERSATION = 3
        const val MAX_INPUT_BYTES = 16 * 1024
        const val TTL_MS = 24 * 60 * 60 * 1000L
        private const val MAX_OUTCOMES = 100
        private const val MAX_INPUT_PREVIEW = 512

        /** The state recorded for a request a dead process may have run. */
        const val UNKNOWN = "UNKNOWN"

        /**
         * What the store refuses to write down. The default leak patterns are for API keys and
         * only WARN on hex, and had nothing for a wallet: a `0x` private key or a recovery phrase
         * the agent had read went to disk and onto a card. A transaction hash has the same shape
         * as a key, so an input carrying one is decline-only as well — the cost is a card that
         * cannot be approved, never a secret at rest.
         */
        fun holdsSecret(text: String): Boolean =
            LeakDetector.holdsKeyMaterial(text) ||
                LeakDetector().scan(text).matches.any { it.action != LeakAction.WARN }

        /** Keys sorted all the way down, so equal inputs give equal bytes and equal signatures. */
        fun canonical(e: JsonElement): String = sorted(e).toString()

        private fun sorted(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.toSortedMap().mapValues { (_, v) -> sorted(v) })
            is JsonArray -> JsonArray(e.map { sorted(it) })
            else -> e
        }

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    enum class State { PENDING, EXECUTING }

    data class Entry(
        val id: String,
        val timestampMs: Long,
        /** Which trigger produced it: `heartbeat`, `telegram`, `xmtp`, … */
        val source: String,
        val provenance: String,
        val toolName: String,
        /** AndyClaw-written and free of parameters; what an older launcher shows. */
        val description: String,
        val conversationId: String?,
        val inputPreview: String?,
        // ── v2 ──
        val v: Int = 1,
        /** The refused call's input, canonical JSON. Absent when it was too big or held a secret. */
        val input: String? = null,
        val dedupeKey: String? = null,
        val count: Int = 1,
        val lastSeenMs: Long = timestampMs,
        val expiresMs: Long? = null,
        /** The ledger session the refused run wrote to; the executed call is recorded there too. */
        val ledgerSessionId: String? = null,
        val effect: String? = null,
        /** Why the gate refused it, as the gate said it. */
        val toolReason: String? = null,
        val state: State = State.PENDING,
        val claimedMs: Long? = null,
        val mac: String? = null,
        /** Everything read from disk, so fields a newer build wrote survive this build's rewrite. */
        internal val raw: JsonObject? = null,
    )

    /** A call a run was not allowed to make on its own. */
    data class Request(
        val source: String,
        val provenance: String,
        val toolName: String,
        val input: JsonObject?,
        val description: String,
        val conversationId: String? = null,
        val ledgerSessionId: String? = null,
        val effect: String? = null,
        val toolReason: String? = null,
    )

    /** How a request that left the queue ended. */
    data class Outcome(
        val id: String,
        val state: String,
        val message: String,
        val finishedMs: Long,
        val toolName: String,
        val ledgerSessionId: String? = null,
        val requestId: String? = null,
    )

    sealed interface Claim {
        data class Claimed(val entry: Entry, val input: JsonObject) : Claim
        data object Missing : Claim
        data object Running : Claim
        data object Expired : Claim
        data object NotExecutable : Claim
        /** The claim could not be written down, so the call must not run. */
        data object Unsaved : Claim
    }

    private val file = File(dir, FILENAME)
    private val outcomesFile = File(dir, OUTCOMES_FILENAME)
    private var recovered = false

    // ── Queueing ────────────────────────────────────────────────────────

    /**
     * Queues [req]. A request identical to one still pending is counted rather than added. Null
     * when there is no room — this conversation's share or the whole queue is full — or the
     * write failed.
     */
    @Synchronized
    fun queue(req: Request): Entry? {
        val now = clock()
        // Expired requests leave first: a fresh request is never counted onto one that is about
        // to be dropped, and they hold no room.
        var all = getAll()
        val canonicalInput = req.input?.let { canonical(it) }
        val storable = canonicalInput?.takeIf { it.toByteArray().size <= MAX_INPUT_BYTES && !holdsSecret(it) }
        val dedupeKey = sha256(
            "${req.source}|${req.conversationId}|${req.provenance}|${req.toolName}|${canonicalInput ?: req.description}"
        )

        all.firstOrNull { it.dedupeKey == dedupeKey && it.state == State.PENDING }?.let { existing ->
            val bumped = existing.copy(count = existing.count + 1, lastSeenMs = now)
            return if (save(all.map { if (it.id == existing.id) bumped else it })) bumped else null
        }
        val conversation = req.conversationId ?: req.source
        if (all.count { (it.conversationId ?: it.source) == conversation } >= MAX_PER_CONVERSATION) {
            Log.w(TAG, "Not queueing '${req.toolName}': $conversation already has $MAX_PER_CONVERSATION pending")
            return null
        }
        if (all.size >= MAX_PENDING) {
            // Strangers opening new chats could otherwise fill the queue and crowd out the
            // owner's own background requests. Those make room by dropping the oldest stranger's.
            val oldestStranger = all.firstOrNull { it.provenance == "UNTRUSTED" && it.state == State.PENDING }
            if (req.provenance == "UNTRUSTED" || oldestStranger == null) {
                Log.w(TAG, "Not queueing '${req.toolName}': $MAX_PENDING already pending")
                return null
            }
            if (!finishLocked(all, oldestStranger, "DROPPED", "Dropped to make room for a request of your own; it didn't run.")) return null
            all = all.filterNot { it.id == oldestStranger.id }
        }
        val unsigned = Entry(
            id = UUID.randomUUID().toString(),
            timestampMs = now,
            source = req.source,
            provenance = req.provenance,
            toolName = req.toolName,
            description = req.description.take(1000),
            conversationId = req.conversationId,
            inputPreview = storable?.take(MAX_INPUT_PREVIEW),
            v = VERSION,
            input = storable,
            dedupeKey = dedupeKey,
            lastSeenMs = now,
            expiresMs = now + TTL_MS,
            ledgerSessionId = req.ledgerSessionId,
            effect = req.effect,
            toolReason = req.toolReason?.take(1000),
        )
        val entry = if (storable != null) unsigned.copy(mac = mac.sign(signingBytes(unsigned))) else unsigned
        if (!save(all + entry)) return null
        Log.i(TAG, "Queued pending approval '${req.toolName}' from ${req.source} (${req.provenance}), " +
            "${all.size + 1} pending, executable=${isExecutable(entry)}")
        return entry
    }

    /** Everything still pending, oldest first. Expired entries are finished on the way. */
    @Synchronized
    fun getAll(): List<Entry> {
        val all = load()
        val now = clock()
        val expired = all.filter { it.state == State.PENDING && it.expiresMs != null && now >= it.expiresMs }
        if (expired.isEmpty()) return all
        val kept = all.filterNot { e -> expired.any { it.id == e.id } }
        if (!save(kept)) return all
        expired.forEach {
            addOutcome(Outcome(it.id, "EXPIRED", "This request expired, so it didn't run.", now, it.toolName, it.ledgerSessionId))
        }
        return kept
    }

    /** Whether APPROVE can run [e]: this build wrote it, it still has its input, and its signature holds. */
    fun isExecutable(e: Entry): Boolean {
        if (e.v != VERSION || e.input == null || e.state != State.PENDING) return false
        if (e.expiresMs == null || clock() >= e.expiresMs) return false
        if (!ApprovalSummaries.reviewable(e.toolName, e.input)) return false
        return mac.verify(signingBytes(e), e.mac)
    }

    // ── Resolving ───────────────────────────────────────────────────────

    /**
     * Takes [id] for execution: PENDING → EXECUTING in one atomic write. Only [Claim.Claimed]
     * may be executed, and only once.
     */
    @Synchronized
    fun claim(id: String): Claim {
        val all = load()
        val e = all.firstOrNull { it.id == id } ?: return Claim.Missing
        // Finished already: a queue file restored from before it ran must not run it again.
        if (loadOutcomes().any { it.id == id }) {
            save(all.filterNot { it.id == id })
            return Claim.Missing
        }
        if (e.state == State.EXECUTING) return Claim.Running
        if (e.expiresMs != null && clock() >= e.expiresMs) {
            finishLocked(all, e, "EXPIRED", "This request expired, so it didn't run.")
            return Claim.Expired
        }
        if (!isExecutable(e)) return Claim.NotExecutable
        val input = runCatching { Json.parseToJsonElement(e.input!!) as? JsonObject }.getOrNull()
            ?: return Claim.NotExecutable
        val claimed = e.copy(state = State.EXECUTING, claimedMs = clock())
        if (!save(all.map { if (it.id == id) claimed else it })) return Claim.Unsaved
        return Claim.Claimed(claimed, input)
    }

    /** A claimed request that did not run after all (the display was busy): back to pending. */
    @Synchronized
    fun release(id: String): Boolean {
        val all = load()
        val e = all.firstOrNull { it.id == id && it.state == State.EXECUTING } ?: return false
        return save(all.map { if (it.id == id) e.copy(state = State.PENDING, claimedMs = null) else it })
    }

    /** [id] ran, failed, was blocked or stopped: it leaves the queue with how it ended. */
    @Synchronized
    fun finish(id: String, state: String, message: String, ledgerSessionId: String? = null, requestId: String? = null): Boolean {
        val all = load()
        val e = all.firstOrNull { it.id == id } ?: return false
        return finishLocked(all, e, state, message, ledgerSessionId, requestId)
    }

    /**
     * The older launcher's APPROVE: recorded as seen, never run. False when [id] is not pending —
     * checked under the lock, so a call already claimed for execution is never marked "not run".
     */
    @Synchronized
    fun acknowledge(id: String, message: String): Boolean {
        val all = load()
        val e = all.firstOrNull { it.id == id } ?: return false
        if (e.state != State.PENDING) return false
        return finishLocked(all, e, "ACKNOWLEDGED", message)
    }

    /** The user declined [id]. False when it is not pending — it may be running. */
    @Synchronized
    fun decline(id: String, message: String = "Declined."): Boolean {
        val all = load()
        val e = all.firstOrNull { it.id == id } ?: return false
        if (e.state != State.PENDING) return false
        return finishLocked(all, e, "DECLINED", message)
    }

    /** Kept for older call sites: removing a pending request is declining it. */
    @Synchronized
    fun remove(id: String): Boolean = decline(id)

    /** How [id] ended, if it has left the queue. */
    @Synchronized
    fun outcome(id: String): Outcome? = loadOutcomes().lastOrNull { it.id == id }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
    }

    // ── Internals ───────────────────────────────────────────────────────

    private fun finishLocked(
        all: List<Entry>,
        e: Entry,
        state: String,
        message: String,
        ledgerSessionId: String? = null,
        requestId: String? = null,
    ): Boolean {
        if (!save(all.filterNot { it.id == e.id })) return false
        addOutcome(Outcome(e.id, state, message, clock(), e.toolName, ledgerSessionId ?: e.ledgerSessionId, requestId))
        return true
    }

    private fun signingBytes(e: Entry): ByteArray = listOf(
        "v${e.v}", e.id, e.source, e.provenance, e.toolName, e.conversationId.orEmpty(),
        e.ledgerSessionId.orEmpty(), e.expiresMs?.toString().orEmpty(), e.effect.orEmpty(), e.input.orEmpty(),
    ).joinToString("\n").toByteArray()

    /**
     * Reads the queue. The first read in a process also settles requests a dead process left
     * EXECUTING: they may have run, so they are recorded as [UNKNOWN] and never run again.
     */
    private fun load(): List<Entry> {
        val all = readEntries()
        if (recovered) return all
        recovered = true
        val stale = all.filter { it.state == State.EXECUTING }
        if (stale.isEmpty()) return all
        val kept = all.filterNot { it.state == State.EXECUTING }
        if (!save(kept)) return all
        stale.forEach {
            Log.w(TAG, "Request ${it.id} (${it.toolName}) was running when the app stopped; not running it again")
            addOutcome(Outcome(it.id, UNKNOWN,
                "This may have run just before the agent restarted. Check the ledger before asking for it again.",
                clock(), it.toolName, it.ledgerSessionId))
        }
        return kept
    }

    private fun readEntries(): List<Entry> {
        if (!file.exists()) return emptyList()
        return try {
            val array = Json.parseToJsonElement(file.readText()) as? JsonArray ?: return emptyList()
            array.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val ts = o.long("timestampMs") ?: 0L
                Entry(
                    id = o.str("id") ?: return@mapNotNull null,
                    timestampMs = ts,
                    source = o.str("source") ?: "unknown",
                    provenance = o.str("provenance") ?: "UNTRUSTED",
                    toolName = o.str("toolName").orEmpty(),
                    description = o.str("description").orEmpty(),
                    conversationId = o.str("conversationId"),
                    inputPreview = o.str("inputPreview"),
                    v = o.int("v") ?: 1,
                    input = o.str("input"),
                    dedupeKey = o.str("dedupeKey"),
                    count = o.int("count") ?: 1,
                    lastSeenMs = o.long("lastSeenMs") ?: ts,
                    expiresMs = o.long("expiresMs"),
                    ledgerSessionId = o.str("ledgerSessionId"),
                    effect = o.str("effect"),
                    toolReason = o.str("toolReason"),
                    state = if (o.str("state") == State.EXECUTING.name) State.EXECUTING else State.PENDING,
                    claimedMs = o.long("claimedMs"),
                    mac = o.str("mac"),
                    raw = o,
                )
            }
        } catch (e: Exception) {
            // A truncated or hand-edited file must not take the agent down with it.
            Log.w(TAG, "Could not read $FILENAME, starting empty: ${e.message}")
            emptyList()
        }
    }

    private fun Entry.toJson(): JsonObject {
        val m = raw?.toMutableMap() ?: mutableMapOf()
        fun set(key: String, value: Any?) {
            when (value) {
                null -> m.remove(key)
                is String -> m[key] = JsonPrimitive(value)
                is Number -> m[key] = JsonPrimitive(value)
                else -> m[key] = JsonPrimitive(value.toString())
            }
        }
        set("id", id)
        set("timestampMs", timestampMs)
        set("source", source)
        set("provenance", provenance)
        set("toolName", toolName)
        set("description", description)
        set("conversationId", conversationId)
        set("inputPreview", inputPreview)
        set("v", v)
        set("input", input)
        set("dedupeKey", dedupeKey)
        set("count", count)
        set("lastSeenMs", lastSeenMs)
        set("expiresMs", expiresMs)
        set("ledgerSessionId", ledgerSessionId)
        set("effect", effect)
        set("toolReason", toolReason)
        set("state", state.name)
        set("claimedMs", claimedMs)
        set("mac", mac)
        return JsonObject(m)
    }

    /** Written to a temp file and renamed over, so a crash mid-write never leaves half a queue. */
    private fun save(entries: List<Entry>): Boolean = writeAtomically(file, JsonArray(entries.map { it.toJson() }))

    private fun loadOutcomes(): List<Outcome> {
        if (!outcomesFile.exists()) return emptyList()
        return try {
            val array = Json.parseToJsonElement(outcomesFile.readText()) as? JsonArray ?: return emptyList()
            array.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                Outcome(
                    id = o.str("id") ?: return@mapNotNull null,
                    state = o.str("state").orEmpty(),
                    message = o.str("message").orEmpty(),
                    finishedMs = o.long("finishedMs") ?: 0L,
                    toolName = o.str("toolName").orEmpty(),
                    ledgerSessionId = o.str("ledgerSessionId"),
                    requestId = o.str("requestId"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $OUTCOMES_FILENAME: ${e.message}")
            emptyList()
        }
    }

    private fun addOutcome(outcome: Outcome) {
        val all = (loadOutcomes() + outcome).takeLast(MAX_OUTCOMES)
        writeAtomically(outcomesFile, JsonArray(all.map {
            JsonObject(buildMap {
                put("id", JsonPrimitive(it.id))
                put("state", JsonPrimitive(it.state))
                put("message", JsonPrimitive(it.message))
                put("finishedMs", JsonPrimitive(it.finishedMs))
                put("toolName", JsonPrimitive(it.toolName))
                it.ledgerSessionId?.let { s -> put("ledgerSessionId", JsonPrimitive(s)) }
                it.requestId?.let { r -> put("requestId", JsonPrimitive(r)) }
            })
        }))
    }

    private fun writeAtomically(target: File, array: JsonArray): Boolean = try {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(array.toString())
        if (tmp.renameTo(target)) {
            true
        } else {
            tmp.delete()
            Log.w(TAG, "Could not replace ${target.name}")
            false
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not write ${target.name}: ${e.message}")
        false
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull?.ifBlank { null }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
