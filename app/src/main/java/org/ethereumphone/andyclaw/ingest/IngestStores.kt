package org.ethereumphone.andyclaw.ingest

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * How ambient ingestion is doing, for the launcher's settings and for the cooldowns.
 *
 * [lastSuccessMs] is what the per-signal cooldowns count from, and it is persisted: kept in
 * memory it was zero after every process start, so each restart swept mail again. Only a
 * success moves it — a failed fetch used to burn the cooldown, so one dropped connection
 * meant no fresh cards for the next six hours.
 */
data class IngestState(
    val state: String = OK,
    val lastAttemptMs: Long = 0L,
    val lastSuccessMs: Long = 0L,
    val lastFailureMs: Long = 0L,
) {
    companion object {
        const val OK = "ok"
        const val NO_ACCOUNT = "no_account"
        const val AUTH_EXPIRED = "auth_expired"
        const val OFFLINE = "offline"
        /** Reported for a switched-off feature; never stored. */
        const val OFF = "off"
    }
}

/** Where [IngestState] lives. */
interface IngestStateStore {
    fun load(): IngestState
    fun save(state: IngestState)

    class InMemory(private var state: IngestState = IngestState()) : IngestStateStore {
        override fun load() = state
        override fun save(state: IngestState) {
            this.state = state
        }
    }
}

/**
 * The mail messages an ingest has already read in full, so the next one does not download
 * them — and their attachments, up to 8 MB each — again. Bounded and aged out: the query
 * looks back 60 days, so an id older than that can never come back anyway.
 */
interface IngestSeenStore {
    fun contains(id: String): Boolean
    fun markAll(ids: Collection<String>, nowMs: Long)
    fun clear()

    class InMemory : IngestSeenStore {
        private val ids = LinkedHashMap<String, Long>()
        override fun contains(id: String) = synchronized(ids) { id in ids }
        override fun markAll(ids: Collection<String>, nowMs: Long) = synchronized(this.ids) { ids.forEach { this.ids[it] = nowMs } }
        override fun clear() = synchronized(ids) { ids.clear() }
    }
}

/**
 * Both stores as small JSON files under one directory.
 *
 * Rollback-safe in both directions (`CLAUDE.md` §0.2): a missing or unreadable file is an
 * empty store, unknown keys are ignored, and every write is a temp file renamed into place,
 * so a build that does not know these files leaves them alone and one that finds them from a
 * newer build reads what it understands.
 */
class FileIngestStores(private val dir: File) : IngestStateStore, IngestSeenStore {

    private val stateFile get() = File(dir, "ingest_state.json")
    private val seenFile get() = File(dir, "ingest_seen.json")
    private val lock = Any()
    private var seen: LinkedHashMap<String, Long>? = null
    // Read once, then served from memory: every signal checks it, and a calendar sync can
    // send dozens of change signals in a second.
    private var state: IngestState? = null

    override fun load(): IngestState = synchronized(lock) {
        state?.let { return it }
        val o = read(stateFile)
        val loaded = if (o == null) IngestState() else IngestState(
            state = o.str("state")?.ifBlank { null } ?: IngestState.OK,
            lastAttemptMs = o.long("lastAttemptMs"),
            lastSuccessMs = o.long("lastSuccessMs"),
            lastFailureMs = o.long("lastFailureMs"),
        )
        state = loaded
        loaded
    }

    override fun save(state: IngestState) = synchronized(lock) {
        this.state = state
        write(stateFile, buildJsonObject {
            put("v", 1)
            put("state", state.state)
            put("lastAttemptMs", state.lastAttemptMs)
            put("lastSuccessMs", state.lastSuccessMs)
            put("lastFailureMs", state.lastFailureMs)
        })
    }

    private fun seenMap(): LinkedHashMap<String, Long> {
        seen?.let { return it }
        val map = LinkedHashMap<String, Long>()
        (read(seenFile)?.get("ids") as? JsonObject)?.forEach { (id, at) ->
            (at as? JsonPrimitive)?.longOrNull?.let { map[id] = it }
        }
        seen = map
        return map
    }

    override fun contains(id: String): Boolean = synchronized(lock) { id in seenMap() }

    override fun markAll(ids: Collection<String>, nowMs: Long) = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized
        val map = seenMap()
        ids.forEach { map[it] = nowMs }
        map.entries.removeAll { nowMs - it.value > SEEN_TTL_MS }
        while (map.size > MAX_SEEN) map.remove(map.keys.first())
        write(seenFile, buildJsonObject {
            put("v", 1)
            putJsonObject("ids") { map.forEach { (k, v) -> put(k, v) } }
        })
    }

    override fun clear() = synchronized(lock) {
        seen = LinkedHashMap()
        seenFile.delete()
        Unit
    }

    private fun read(file: File): JsonObject? = try {
        if (file.isFile) json.parseToJsonElement(file.readText()) as? JsonObject else null
    } catch (e: Exception) {
        Log.w(TAG, "unreadable ${file.name}, starting empty: ${e.message}")
        null
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

    private fun write(file: File, json: JsonObject) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not write ${file.name}: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "IngestStores"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private const val MAX_SEEN = 1_000
        private const val SEEN_TTL_MS = 90L * 24 * 60 * 60 * 1000
    }
}
