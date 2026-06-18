package org.ethereumphone.andyclaw.llm

import android.util.Log
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge

/**
 * Wrapper around [LlamaBridge] (Llamatik) for local LLM inference.
 *
 * Manages model lifecycle and exposes generate/stream methods. Tracks the
 * currently-loaded model path + runtime config so callers (e.g. [LocalLlmClient])
 * can detect when a reload is needed — for instance after the user picks a
 * different GGUF or changes a hardware-level parameter like `n_ctx` or
 * `n_gpu_layers` that can only be applied at init time.
 *
 * All methods are thread-safe via synchronized blocks.
 */
class LlamaCpp {

    companion object {
        private const val TAG = "LlamaCpp"
    }

    @Volatile
    var isModelLoaded: Boolean = false
        private set

    /** Absolute path of the GGUF currently loaded, or null. */
    @Volatile
    var loadedModelPath: String? = null
        private set

    /** Runtime config the current model was loaded with, or null. */
    @Volatile
    var loadedConfig: LocalLlmRuntimeConfig? = null
        private set

    /**
     * Load a GGUF into the native runtime. If a model is already loaded it is
     * unloaded first. When [config] is non-null, the new
     * `initGenerateModelWithConfig` JNI overload is called so context size,
     * batch size, threads, GPU layers, and mmap take effect; otherwise we fall
     * back to the legacy `initGenerateModel(path)` (Llamatik defaults).
     */
    @Synchronized
    fun load(modelPath: String, config: LocalLlmRuntimeConfig? = null): Boolean {
        if (isModelLoaded) {
            Log.d(TAG, "Model already loaded, shutting down first")
            unload()
        }
        Log.i(TAG, "Loading model via Llamatik: $modelPath (config=$config)")
        val result = if (config != null) {
            LlamaBridge.initGenerateModelWithConfig(
                modelPath  = modelPath,
                nCtx       = config.nCtx,
                nBatch     = config.nBatch,
                nThreads   = config.nThreads,
                nGpuLayers = config.nGpuLayers,
                useMmap    = config.useMmap,
            )
        } else {
            LlamaBridge.initGenerateModel(modelPath)
        }
        isModelLoaded   = result
        loadedModelPath = if (result) modelPath else null
        loadedConfig    = if (result) config    else null
        Log.i(TAG, "loadModel result: $result")
        return result
    }

    @Synchronized
    fun unload() {
        if (!isModelLoaded) return
        LlamaBridge.shutdown()
        isModelLoaded   = false
        loadedModelPath = null
        loadedConfig    = null
        Log.i(TAG, "Model unloaded")
    }

    @Synchronized
    fun generate(prompt: String): String {
        check(isModelLoaded) { "Model not loaded" }
        return LlamaBridge.generate(prompt)
    }

    fun generateStream(prompt: String, callback: GenStream) {
        check(isModelLoaded) { "Model not loaded" }
        LlamaBridge.generateStream(prompt, callback)
    }

    /**
     * Count tokens for [text] using the loaded model's tokenizer.
     * Returns -1 if the model is not loaded.
     */
    fun tokenize(text: String): Int {
        if (!isModelLoaded) return -1
        return LlamaBridge.tokenize(text)
    }
}
