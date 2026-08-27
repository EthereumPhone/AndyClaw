package org.ethereumphone.andyclaw.safety

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The queue behind the "raise a card for the user to approve" leg of the trust model.
 *
 * When a run triggered by untrusted content asks for something irreversible, a
 * headless runner has nobody to ask — auto-approving would make the gate a no-op, and
 * dropping it silently would make the agent look broken. So the request is refused and
 * recorded here, for the ambient surface to render as an `ActionConfirmCard`.
 *
 * Storage is `filesDir/pending_approvals.json` — the app's own sandbox.
 * `/data/andyclaw_files/` belongs to `system_server` and the APK cannot write it.
 * The file survives an OTA and a rollback: it is a flat JSON array of objects and an
 * older build that has never heard of it simply never opens it.
 *
 * Bounded at [MAX_ENTRIES]; oldest evicted. Never holds signing material — only the
 * tool name, the request description and a truncated preview of the arguments.
 */
class PendingApprovalStore(context: Context) {

    companion object {
        private const val TAG = "PendingApprovalStore"
        private const val FILENAME = "pending_approvals.json"
        private const val MAX_ENTRIES = 50
        private const val MAX_INPUT_PREVIEW = 512
    }

    data class Entry(
        val id: String,
        val timestampMs: Long,
        /** Which trigger produced it: `heartbeat`, `telegram`, `xmtp`, … */
        val source: String,
        val provenance: String,
        val toolName: String,
        val description: String,
        val conversationId: String?,
        val inputPreview: String?,
    )

    private val file = File(context.filesDir, FILENAME)

    @Synchronized
    fun add(
        source: String,
        provenance: String,
        toolName: String,
        description: String,
        conversationId: String? = null,
        inputPreview: String? = null,
    ): Entry {
        val entry = Entry(
            id = UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            source = source,
            provenance = provenance,
            toolName = toolName,
            description = description.take(1000),
            conversationId = conversationId,
            inputPreview = inputPreview?.take(MAX_INPUT_PREVIEW),
        )
        val all = (readAll() + entry).takeLast(MAX_ENTRIES)
        write(all)
        Log.i(TAG, "Queued pending approval '$toolName' from $source ($provenance), ${all.size} pending")
        return entry
    }

    @Synchronized
    fun getAll(): List<Entry> = readAll()

    @Synchronized
    fun remove(id: String): Boolean {
        val all = readAll()
        val kept = all.filterNot { it.id == id }
        if (kept.size == all.size) return false
        write(kept)
        return true
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
    }

    // ── Persistence ──────────────────────────────────────────────────

    private fun readAll(): List<Entry> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                Entry(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    timestampMs = obj.optLong("timestampMs", 0L),
                    source = obj.optString("source", "unknown"),
                    provenance = obj.optString("provenance", "UNTRUSTED"),
                    toolName = obj.optString("toolName", ""),
                    description = obj.optString("description", ""),
                    conversationId = obj.optString("conversationId", "").ifBlank { null },
                    inputPreview = obj.optString("inputPreview", "").ifBlank { null },
                )
            }
        } catch (e: Exception) {
            // A truncated or hand-edited file must not take the agent down with it.
            Log.w(TAG, "Could not read $FILENAME, starting empty: ${e.message}")
            emptyList()
        }
    }

    private fun write(entries: List<Entry>) {
        try {
            val array = JSONArray()
            for (e in entries) {
                array.put(
                    JSONObject().apply {
                        put("id", e.id)
                        put("timestampMs", e.timestampMs)
                        put("source", e.source)
                        put("provenance", e.provenance)
                        put("toolName", e.toolName)
                        put("description", e.description)
                        e.conversationId?.let { put("conversationId", it) }
                        e.inputPreview?.let { put("inputPreview", it) }
                    }
                )
            }
            file.writeText(array.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Could not write $FILENAME: ${e.message}")
        }
    }
}
