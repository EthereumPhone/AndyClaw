package org.ethereumphone.andyclaw.llm

import android.util.Log
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Wrapper around [LlamaBridge] (Llamatik) for local LLM inference.
 *
 * Manages model lifecycle and exposes generate/stream methods. Tracks the
 * currently-loaded model path + runtime config so callers (e.g. [LocalLlmClient])
 * can detect when a reload is needed — for instance after the user picks a
 * different GGUF or changes a hardware-level parameter like `n_ctx` or
 * `n_gpu_layers` that can only be applied at init time.
 *
 * Every native call holds one process-wide lock ([withNativeLock]). Llamatik keeps a
 * single global model and context and locks nothing itself: a second decode on that
 * context, or a shutdown under a running decode loop, took the whole process down.
 */
class LlamaCpp {

    companion object {
        private const val TAG = "LlamaCpp"
        private const val LOCK_POLL_MS = 100L

        /** Process-wide, like the native state it guards. */
        private val nativeLock = ReentrantLock()
        private val cancelLock = Any()
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

    /** An [unload] that found the lock taken; whoever holds it unloads when it lets go. */
    @Volatile
    private var unloadPending = false

    /** The generation [cancelGeneration] may stop; null while none is decoding. Guarded by [cancelLock]. */
    private var activeGeneration: Any? = null

    /**
     * Load a GGUF into the native runtime. If a model is already loaded it is
     * unloaded first. When [config] is non-null, the new
     * `initGenerateModelWithConfig` JNI overload is called so context size,
     * batch size, threads, GPU layers, and mmap take effect; otherwise we fall
     * back to the legacy `initGenerateModel(path)` (Llamatik defaults).
     */
    fun load(modelPath: String, config: LocalLlmRuntimeConfig? = null): Boolean = withNativeLock {
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
        result
    }

    /**
     * Frees the model now, or, while a generation holds it, as soon as that generation
     * ends. Never waits for one: Settings calls this on the main thread.
     */
    fun unload() {
        unloadPending = true
        unloadIfPending()
    }

    fun generate(prompt: String): String = withNativeLock {
        check(isModelLoaded) { "Model not loaded" }
        LlamaBridge.generate(prompt)
    }

    /**
     * Streams a completion into [callback]. [generation] names this call to
     * [cancelGeneration], so a cancel can only ever stop the decode it was meant for.
     */
    fun generateStream(prompt: String, callback: GenStream, generation: Any = Any()) = withNativeLock {
        check(isModelLoaded) { "Model not loaded" }
        synchronized(cancelLock) { activeGeneration = generation }
        try {
            LlamaBridge.generateStream(prompt, callback)
        } finally {
            synchronized(cancelLock) { activeGeneration = null }
        }
    }

    /**
     * Asks the native decode loop to stop after its current token, if [generation] is the
     * one decoding; it then ends through `onComplete`. The loop clears the request when it
     * starts, so a caller repeats this until its generation has returned.
     */
    fun cancelGeneration(generation: Any) {
        synchronized(cancelLock) {
            if (activeGeneration === generation) LlamaBridge.nativeCancelGenerate()
        }
    }

    /** Push the sampling knobs into Llamatik. Cheap; no model reload. */
    fun updateGenerateParams(config: LocalLlmRuntimeConfig) = withNativeLock {
        LlamaBridge.updateGenerateParams(
            temperature   = config.temperature,
            maxTokens     = config.maxTokens,
            topP          = config.topP,
            topK          = config.topK,
            repeatPenalty = config.repeatPenalty,
        )
    }

    /**
     * Count tokens for [text] using the loaded model's tokenizer.
     * Returns -1 if the model is not loaded.
     */
    fun tokenize(text: String): Int = withNativeLock {
        if (!isModelLoaded) -1 else LlamaBridge.tokenize(text)
    }

    /**
     * Runs [block] holding the native lock, so a load, a generation and its tokenizer calls
     * all see one model. The lock is waited for in slices: once [stillWanted] says no — the
     * caller was cancelled while another generation held the model — it gives up instead of
     * waiting that generation out.
     */
    fun <T> withNativeLock(stillWanted: () -> Boolean = { true }, block: () -> T): T {
        while (!nativeLock.tryLock(LOCK_POLL_MS, TimeUnit.MILLISECONDS)) {
            if (!stillWanted()) throw CancellationException("Local model no longer wanted")
        }
        try {
            return block()
        } finally {
            val outermost = nativeLock.holdCount == 1
            nativeLock.unlock()
            if (outermost) unloadIfPending()
        }
    }

    /** Runs after every outermost release too, so an [unload] that raced one is never lost. */
    private fun unloadIfPending() {
        if (!unloadPending || !nativeLock.tryLock()) return
        try {
            if (!unloadPending) return
            unloadPending = false
            if (!isModelLoaded) return
            LlamaBridge.shutdown()
            isModelLoaded   = false
            loadedModelPath = null
            loadedConfig    = null
            Log.i(TAG, "Model unloaded")
        } finally {
            nativeLock.unlock()
        }
    }
}
