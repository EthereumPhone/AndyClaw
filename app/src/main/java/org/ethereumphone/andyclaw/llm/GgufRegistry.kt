package org.ethereumphone.andyclaw.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Filename written by [ModelDownloadManager] for the default download. */
const val DEFAULT_BUILTIN_GGUF = "qwen2.5-1.5b-instruct-q2_k.gguf"

/**
 * Multi-model GGUF registry — backs the "bring your own model" feature.
 *
 * Scans `filesDir/models/` for `.gguf` files and exposes them as a list. The
 * single default download managed by [ModelDownloadManager] appears here as a
 * builtin entry; any user-imported GGUFs appear alongside it. Importing copies
 * the chosen content URI into the same directory so llama.cpp gets a plain
 * filesystem path (SAF URIs aren't openable by native code).
 */
class GgufRegistry(
    private val context: Context,
    /** Filename of the built-in default download — see [DEFAULT_BUILTIN_GGUF]. */
    private val builtinFilename: String = DEFAULT_BUILTIN_GGUF,
) {
    companion object {
        private const val TAG = "GgufRegistry"
        private const val BUFFER_SIZE = 8 * 1024
    }

    private val modelsDir: File = File(context.filesDir, "models").also { it.mkdirs() }

    private val _models = MutableStateFlow<List<GgufModel>>(emptyList())
    val models: StateFlow<List<GgufModel>> = _models.asStateFlow()

    init { refresh() }

    /** Re-scan `filesDir/models/` and update [models]. Builtin entry sorts first. */
    fun refresh() {
        val files = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?: emptyArray()
        _models.value = files.map { f ->
            GgufModel(
                filename = f.name,
                absolutePath = f.absolutePath,
                displayName = friendlyName(f.name),
                sizeBytes = f.length(),
                isBuiltin = f.name == builtinFilename,
            )
        }.sortedWith(compareByDescending<GgufModel> { it.isBuiltin }.thenBy { it.displayName.lowercase() })
    }

    /**
     * Copy a content:// URI from a SAF picker into `filesDir/models/<filename>`
     * and refresh the registry. Returns the new entry on success.
     *
     * llama.cpp needs a plain filesystem path, so we copy rather than hold the
     * URI — at the cost of doubling disk briefly during import.
     */
    suspend fun importFromUri(uri: Uri): GgufModel? = withContext(Dispatchers.IO) {
        try {
            val origName = queryDisplayName(uri) ?: "imported-${System.currentTimeMillis()}.gguf"
            val safeName = ensureGgufExtension(sanitize(origName))
            val dest = uniqueDest(safeName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output, BUFFER_SIZE) }
            } ?: error("openInputStream returned null for $uri")
            Log.i(TAG, "imported gguf: ${dest.name} (${dest.length()} bytes)")
            refresh()
            _models.value.firstOrNull { it.filename == dest.name }
        } catch (e: Exception) {
            Log.e(TAG, "import failed for $uri", e)
            null
        }
    }

    /**
     * Copy a GGUF from an open file descriptor (cross-process import from the
     * launcher via AIDL) into `filesDir/models/<filename>`, then refresh.
     * Synchronous — call off the main thread. Writes to a `.tmp` first and
     * renames on full-copy success so a cancelled/half copy never appears in
     * the registry. Returns the new entry, or null on failure.
     */
    fun importFromFd(fd: android.os.ParcelFileDescriptor, displayName: String): GgufModel? {
        return try {
            val safeName = ensureGgufExtension(sanitize(displayName))
            val dest = uniqueDest(safeName)
            val tmp = File(dest.parentFile, "${dest.name}.tmp")
            java.io.FileInputStream(fd.fileDescriptor).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, BUFFER_SIZE) }
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                error("rename ${tmp.name} -> ${dest.name} failed")
            }
            Log.i(TAG, "imported gguf (fd): ${dest.name} (${dest.length()} bytes)")
            refresh()
            _models.value.firstOrNull { it.filename == dest.name }
        } catch (e: Exception) {
            Log.e(TAG, "importFromFd failed", e)
            null
        }
    }

    /**
     * Delete an entry from `filesDir/models/`. Refuses to delete the builtin —
     * for that, call [ModelDownloadManager.deleteModel] so the download-state
     * flow is reset consistently.
     */
    fun delete(filename: String): Boolean {
        if (filename == builtinFilename) return false
        val f = File(modelsDir, filename)
        if (!f.exists()) return false
        val ok = f.delete()
        if (ok) {
            Log.i(TAG, "deleted gguf: $filename")
            refresh()
        }
        return ok
    }

    fun find(filename: String): GgufModel? = _models.value.firstOrNull { it.filename == filename }

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    } catch (_: Exception) { null }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun ensureGgufExtension(name: String): String =
        if (name.endsWith(".gguf", ignoreCase = true)) name else "$name.gguf"

    private fun uniqueDest(name: String): File {
        var candidate = File(modelsDir, name)
        if (!candidate.exists()) return candidate
        val base = name.removeSuffix(".gguf").removeSuffix(".GGUF")
        var i = 1
        while (true) {
            candidate = File(modelsDir, "$base-$i.gguf")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun friendlyName(filename: String): String = when (filename) {
        builtinFilename -> "Qwen 2.5 1.5B Instruct (Q2_K)"
        else -> filename.removeSuffix(".gguf").removeSuffix(".GGUF")
    }
}

/** A `.gguf` model file known to the registry. */
data class GgufModel(
    val filename: String,
    val absolutePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val isBuiltin: Boolean,
)

/**
 * Per-model runtime config passed to llama.cpp at init time.
 *
 * Mirrors the parameter set of `LlamaBridge.initGenerateModelWithConfig`.
 * Defaults match the Llamatik C-side defaults so an unset config is a no-op.
 */
data class LocalLlmRuntimeConfig(
    // Sampling (applied per-generation via LlamaBridge.updateGenerateParams)
    val temperature: Float = 0.3f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val maxTokens: Int = 256,
    val repeatPenalty: Float = 1.0f,
    // Hardware (applied at init via LlamaBridge.initGenerateModelWithConfig)
    val nCtx: Int = 4096,
    val nBatch: Int = 2048,
    val nThreads: Int = 0,    // 0 = let llama.cpp pick
    val nGpuLayers: Int = 0,  // 0 = CPU only, -1 = all on GPU
    val useMmap: Boolean = true,
) {
    /**
     * True iff every hardware-level field matches [other]. These are the fields
     * that require a model reload (set at `initGenerateModelWithConfig` time);
     * sampling fields are applied per-generation via `updateGenerateParams`
     * and must NOT trigger a reload.
     *
     * Used by [LocalLlmClient.ensureModelLoaded] to gate the reload decision:
     * compare the hardware subset only, so changing temperature/topP/etc.
     * never reloads a 750MB model.
     */
    fun hardwareEquals(other: LocalLlmRuntimeConfig?): Boolean {
        if (other == null) return false
        return nCtx == other.nCtx &&
            nBatch == other.nBatch &&
            nThreads == other.nThreads &&
            nGpuLayers == other.nGpuLayers &&
            useMmap == other.useMmap
    }

    companion object {
        val DEFAULT = LocalLlmRuntimeConfig()
    }
}
