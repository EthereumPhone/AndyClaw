package org.ethereumphone.andyclaw.heartbeat

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HeartbeatToolCall(
    val toolName: String,
    val result: String,
)

@Serializable
data class HeartbeatLogEntry(
    val timestampMs: Long,
    val outcome: String,
    val prompt: String,
    val responseText: String,
    val error: String? = null,
    val toolCalls: List<HeartbeatToolCall> = emptyList(),
    val durationMs: Long = 0L,
)

/**
 * Thread-safe, file-backed store for heartbeat run logs.
 *
 * Persists up to [MAX_ENTRIES] entries as JSON in `heartbeat-logs.json`,
 * trimming the oldest entries when the cap is exceeded.
 */
class HeartbeatLogStore(private val filesDir: File) {

    companion object {
        private const val TAG = "HeartbeatLogStore"
        private const val FILENAME = "heartbeat-logs.json"
        private const val MAX_ENTRIES = 100
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File get() = File(filesDir, FILENAME)

    private val lock = Any()
    private var entries: MutableList<HeartbeatLogEntry> = mutableListOf()
    private var loaded = false

    fun append(entry: HeartbeatLogEntry) {
        synchronized(lock) {
            ensureLoaded()
            entries.add(entry)
            // Trim oldest entries if over cap
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
            save()
        }
    }

    fun getAll(): List<HeartbeatLogEntry> {
        synchronized(lock) {
            ensureLoaded()
            return entries.toList().reversed() // newest first
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            loaded = true
            try {
                file.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete log file: ${e.message}")
            }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        try {
            val raw = file.readText()
            entries = json.decodeFromString(ListSerializer(HeartbeatLogEntry.serializer()), raw).toMutableList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse heartbeat logs, starting fresh: ${e.message}")
            entries = mutableListOf()
        }
    }

    private fun save() {
        try {
            val raw = json.encodeToString(ListSerializer(HeartbeatLogEntry.serializer()), entries)
            file.writeText(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save heartbeat logs: ${e.message}")
        }
    }
}
