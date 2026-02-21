package org.ethereumphone.andyclaw.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages downloading and storing the local GGUF model file.
 *
 * Downloads the Qwen3-4B Q4_K_M quantization (~2.5 GB) from HuggingFace
 * with progress tracking.
 */
class ModelDownloadManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "ModelDownloadManager"
        private const val MODEL_FILENAME = "qwen3-4b-q4_k_m.gguf"
        private const val MODEL_URL =
            "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/qwen3-4b-q4_k_m.gguf"
        private const val BUFFER_SIZE = 8192
    }

    private val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
    val modelFile = File(modelsDir, MODEL_FILENAME)

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    val isModelDownloaded: Boolean get() = modelFile.exists() && modelFile.length() > 0

    /** Approximate model file size in bytes. */
    val modelSizeBytes: Long = 2_700_000_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS) // 10 min for large download
        .build()

    /**
     * Download the model file with progress updates.
     * No-op if the model is already downloaded.
     */
    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        if (isModelDownloaded) {
            Log.i(TAG, "Model already downloaded at ${modelFile.absolutePath}")
            _downloadProgress.value = 1f
            return@withContext true
        }

        if (_isDownloading.value) {
            Log.w(TAG, "Download already in progress")
            return@withContext false
        }

        _isDownloading.value = true
        _downloadError.value = null
        _downloadProgress.value = 0f

        val tempFile = File(modelsDir, "$MODEL_FILENAME.tmp")

        try {
            val request = Request.Builder()
                .url(MODEL_URL)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw RuntimeException("Download failed: HTTP ${response.code}")
            }

            val contentLength = response.body?.contentLength() ?: modelSizeBytes
            var downloaded = 0L

            response.body?.byteStream()?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        _downloadProgress.value = (downloaded.toFloat() / contentLength).coerceIn(0f, 1f)
                    }
                }
            }

            // Rename temp file to final
            if (tempFile.renameTo(modelFile)) {
                Log.i(TAG, "Model downloaded successfully: ${modelFile.absolutePath}")
                _downloadProgress.value = 1f
                true
            } else {
                throw RuntimeException("Failed to rename temp file")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            _downloadError.value = e.message ?: "Download failed"
            tempFile.delete()
            false
        } finally {
            _isDownloading.value = false
        }
    }

    /** Delete the downloaded model file. */
    fun deleteModel() {
        if (modelFile.exists()) {
            modelFile.delete()
            Log.i(TAG, "Model file deleted")
        }
        _downloadProgress.value = 0f
    }
}
