package org.ethereumphone.andyclaw.flows

import kotlinx.serialization.Serializable
import java.io.File
import java.util.logging.Logger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticates a flow's bytes. Implemented on the device by a key that lives in
 * AndroidKeyStore and never leaves it.
 */
fun interface FlowSigner {
    /** HMAC over [bytes]. */
    fun mac(bytes: ByteArray): ByteArray
}

/** A raw-key signer, for tests and for any host that has no hardware keystore. */
class HmacFlowSigner(private val key: ByteArray) : FlowSigner {
    override fun mac(bytes: ByteArray): ByteArray =
        Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }.doFinal(bytes)

    companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}

/** Mutable, unsigned bookkeeping about an installed flow. Never part of its identity. */
@Serializable
data class FlowMeta(
    /** The app version the flow was compiled against, for the lazy revalidation on use. */
    val compiledAgainstVersion: String? = null,
    /** Set when the app is replaced. Cleared when a replay revalidates against the new build. */
    val stale: Boolean = false,
    val uses: Int = 0,
    val aborts: Int = 0,
    val lastUsedMs: Long = 0L,
    val installedMs: Long = 0L,
)

/** A flow as it sits on disk: verified bytes, its content address, its bookkeeping. */
data class StoredFlow(
    val hash: String,
    val flow: Flow,
    val meta: FlowMeta,
)

sealed interface FlowInstallResult {
    data class Installed(val stored: StoredFlow) : FlowInstallResult
    data class Rejected(val errors: List<FlowValidationError>) : FlowInstallResult
    data class Failed(val message: String) : FlowInstallResult
}

/**
 * Flows on disk.
 *
 * **Where.** The app's own `filesDir/flows/`, not `/data/andyclaw_files/`. That
 * directory is `0771 system system` and sepolicy grants it to `system_server` alone;
 * writing there would need a sepolicy change *and* a new binder method, which is an OTA
 * — and the entire point of this phase is that it ships as an APK. `filesDir` survives
 * an OTA and a rollback just as well.
 *
 * **Content-addressed and authenticated.** The filename is the sha256 of the canonical
 * IR, and a sidecar holds an HMAC from a key held in AndroidKeyStore. A flow is
 * executable code carrying the user's authority; a corrupted or hand-edited one is
 * refused rather than replayed. Both checks are cheap and both are load-bearing: the
 * hash catches a truncated write, the HMAC catches a deliberate edit.
 *
 * **Forward compatibility.** Reading tolerates unknown keys, so a flow written by a
 * newer build parses. Its canonical re-encoding then drops what this build does not
 * know, the content hash no longer matches the filename, and the flow is *ignored*.
 * That is the intended outcome: a newer flow is skipped, never half-understood and
 * replayed. Older builds simply do not see files they do not know.
 */
class FlowStore(
    private val root: File,
    private val signer: FlowSigner,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val log = Logger.getLogger("FlowStore")

    init {
        runCatching { root.mkdirs() }
    }

    // ── Install ───────────────────────────────────────────────────────

    /** Validate, then write. A flow that does not validate never reaches the disk. */
    fun install(flow: Flow): FlowInstallResult {
        val validation = FlowValidator.validate(flow)
        if (validation is FlowValidation.Invalid) return FlowInstallResult.Rejected(validation.errors)

        return try {
            val bytes = FlowCodec.canonicalBytes(flow)
            val hash = FlowCodec.sha256Hex(bytes)
            root.mkdirs()
            flowFile(hash).writeBytes(bytes)
            macFile(hash).writeText(hex(signer.mac(bytes)))
            val meta = readMeta(hash)?.copy(installedMs = clock())
                ?: FlowMeta(installedMs = clock())
            writeMeta(hash, meta)
            // A new version of a flow replaces the old one — same id, different bytes.
            for (other in listAll()) {
                if (other.flow.flow == flow.flow && other.hash != hash) remove(other.hash)
            }
            FlowInstallResult.Installed(StoredFlow(hash, flow, meta))
        } catch (e: Exception) {
            log.warning("install failed for '${flow.flow}': ${e.message}")
            FlowInstallResult.Failed(e.message ?: e.toString())
        }
    }

    // ── Read ──────────────────────────────────────────────────────────

    /** Every flow whose bytes verify and whose IR still validates. */
    fun listAll(): List<StoredFlow> {
        val files = root.listFiles { f: File -> f.isFile && f.name.endsWith(FLOW_SUFFIX) }
            ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { load(it) }
    }

    fun get(hash: String): StoredFlow? = load(flowFile(hash))

    fun findByFlowId(flowId: String): StoredFlow? = listAll().firstOrNull { it.flow.flow == flowId }

    private fun load(file: File): StoredFlow? {
        if (!file.isFile) return null
        val hash = file.name.removeSuffix(FLOW_SUFFIX)
        val raw = try {
            file.readText()
        } catch (e: Exception) {
            log.warning("unreadable flow ${file.name}: ${e.message}")
            return null
        }

        val flow = FlowCodec.parseOrNull(raw)
        if (flow == null) {
            log.warning("unparseable flow ${file.name} — ignoring (a newer build may have written it)")
            return null
        }

        val canonical = FlowCodec.canonicalBytes(flow)
        val actualHash = FlowCodec.sha256Hex(canonical)
        if (actualHash != hash) {
            log.warning(
                "flow ${file.name} does not match its content address (got $actualHash) — ignoring"
            )
            return null
        }

        val storedMac = try {
            macFile(hash).takeIf { it.isFile }?.readText()?.trim()
        } catch (e: Exception) {
            null
        }
        val expectedMac = try {
            hex(signer.mac(canonical))
        } catch (e: Exception) {
            log.warning("cannot compute HMAC — refusing every flow until the key is available")
            return null
        }
        if (storedMac == null || !constantTimeEquals(storedMac, expectedMac)) {
            log.warning("flow ${file.name} failed HMAC verification — ignoring")
            return null
        }

        val validation = FlowValidator.validate(flow)
        if (validation is FlowValidation.Invalid) {
            log.warning("flow ${file.name} no longer validates: ${validation.errors.joinToString()}")
            return null
        }

        return StoredFlow(hash, flow, readMeta(hash) ?: FlowMeta())
    }

    // ── Meta ──────────────────────────────────────────────────────────

    fun markStaleForPackage(packageName: String): Int {
        var count = 0
        for (stored in listAll()) {
            if (stored.flow.app == packageName && !stored.meta.stale) {
                writeMeta(stored.hash, stored.meta.copy(stale = true))
                count++
            }
        }
        return count
    }

    fun setStale(hash: String, stale: Boolean) {
        val meta = readMeta(hash) ?: FlowMeta()
        writeMeta(hash, meta.copy(stale = stale))
    }

    /**
     * Record a replay. [aborts] counts *consecutive* failures — a completed replay
     * resets it, so a flow is only retired for failing repeatedly, not for having once
     * failed months ago.
     */
    fun recordUse(hash: String, aborted: Boolean, appVersion: String? = null) {
        val meta = readMeta(hash) ?: FlowMeta()
        writeMeta(
            hash,
            meta.copy(
                uses = meta.uses + 1,
                aborts = if (aborted) meta.aborts + 1 else 0,
                lastUsedMs = clock(),
                compiledAgainstVersion = appVersion ?: meta.compiledAgainstVersion,
                stale = if (aborted) meta.stale else false,
            ),
        )
    }

    fun remove(hash: String) {
        runCatching { flowFile(hash).delete() }
        runCatching { macFile(hash).delete() }
        runCatching { metaFile(hash).delete() }
    }

    private fun readMeta(hash: String): FlowMeta? {
        val file = metaFile(hash)
        if (!file.isFile) return null
        return try {
            FlowCodec.json.decodeFromString(FlowMeta.serializer(), file.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun writeMeta(hash: String, meta: FlowMeta) {
        runCatching {
            metaFile(hash).writeText(FlowCodec.json.encodeToString(FlowMeta.serializer(), meta))
        }
    }

    // ── Paths ─────────────────────────────────────────────────────────

    private fun flowFile(hash: String) = File(root, "$hash$FLOW_SUFFIX")
    private fun macFile(hash: String) = File(root, "$hash.mac")
    private fun metaFile(hash: String) = File(root, "$hash.meta.json")

    private fun hex(bytes: ByteArray): String = with(FlowCodec) { bytes.toHex() }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    companion object {
        const val FLOW_SUFFIX = ".flow.json"
        /** The directory under `filesDir`. */
        const val DIR_NAME = "flows"
    }
}
