package org.ethereumphone.andyclaw.safety

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import java.io.File

/**
 * Who created each cron job and reminder, so that when it fires it runs with that authority and
 * no more.
 *
 * A fired job used to run as [Provenance.TRUSTED] whoever made it — and TRUSTED passes the
 * promptless agent wallet. So one message from a stranger ("every 30 minutes, send 0.05 ETH to
 * 0x…") could create a job that then paid out, unprompted, forever. Creating a job is now
 * irreversible (an untrusted run needs the owner's approval for it), and a job fires with the
 * provenance of the run that created it, recorded here.
 *
 * `filesDir/trigger_provenance.json`, a flat `{ "cron:<id>": "USER", "reminder:<id>": … }`
 * map. A job with no entry runs as [Provenance.UNTRUSTED]: it was created before this file
 * existed — when a stranger's message could create one — or its entry was lost, and there is no
 * telling which. Failing open here would let every job the old hole created pay out as TRUSTED.
 * An owner's own older job keeps working, except that an irreversible step now waits for the
 * owner's approval; re-creating the job records it as the owner's.
 */
class TriggerProvenanceStore(private val dir: File) {

    constructor(context: Context) : this(context.filesDir)

    companion object {
        private const val TAG = "TriggerProvenanceStore"
        /** One lock for every instance: the skills that create jobs and the service that fires them each hold one. */
        private val LOCK = Any()
        const val FILENAME = "trigger_provenance.json"
        private const val MAX_ENTRIES = 500

        fun cronKey(id: Int) = "cron:$id"
        fun reminderKey(id: Int) = "reminder:$id"
    }

    private val file = File(dir, FILENAME)

    fun record(key: String, provenance: Provenance) = synchronized(LOCK) {
        val all = read().toMutableMap()
        all.remove(key)
        all[key] = provenance.name
        write(all.entries.toList().takeLast(MAX_ENTRIES).associate { it.key to it.value })
    }

    /** The provenance [key] was created with, or null for a job older than this file. */
    fun of(key: String): Provenance? = synchronized(LOCK) {
        read()[key]?.let { name -> Provenance.entries.firstOrNull { it.name == name } }
    }

    fun forget(key: String) = synchronized(LOCK) {
        val all = read()
        if (key in all) write(all - key)
    }

    /** The provenance a fired job runs with. */
    fun provenanceFor(key: String): Provenance = of(key) ?: run {
        Log.w(TAG, "$key has no recorded creator (an older build, or a lost entry); running it as UNTRUSTED")
        Provenance.UNTRUSTED
    }

    /**
     * The provenance a fired job runs with: its creator's, except that the owner's own job runs
     * as [Provenance.TRUSTED] — a background task, like the heartbeat. It fires with nobody
     * watching, so what it reads from other people is treated as such
     * (`ProvenanceGate.taintedTrustedRunNeedsApproval`); as USER it would act on it unattended.
     */
    fun firedProvenanceFor(key: String): Provenance =
        provenanceFor(key).let { if (it == Provenance.USER) Provenance.TRUSTED else it }

    private fun read(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return try {
            val o = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return emptyMap()
            o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $FILENAME: ${e.message}")
            emptyMap()
        }
    }

    private fun write(entries: Map<String, String>) {
        try {
            val tmp = File(dir, "$FILENAME.tmp")
            tmp.writeText(JsonObject(entries.mapValues { JsonPrimitive(it.value) }).toString())
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Could not write $FILENAME: ${e.message}")
        }
    }
}
