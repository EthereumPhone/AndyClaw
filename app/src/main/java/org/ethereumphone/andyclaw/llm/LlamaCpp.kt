package org.ethereumphone.andyclaw.llm

import android.util.Log
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge

/**
 * Wrapper around [LlamaBridge] (Llamatik) for local LLM inference.
 *
 * Manages model lifecycle and exposes generate/stream methods.
 * All methods are thread-safe via synchronized blocks.
 */
class LlamaCpp {

    companion object {
        private const val TAG = "LlamaCpp"
    }

    @Volatile
    var isModelLoaded: Boolean = false
        private set

    @Synchronized
    fun load(modelPath: String): Boolean {
        if (isModelLoaded) {
            Log.d(TAG, "Model already loaded, shutting down first")
            unload()
        }
        Log.i(TAG, "Loading model via Llamatik: $modelPath")
        val result = LlamaBridge.initGenerateModel(modelPath)
        isModelLoaded = result
        Log.i(TAG, "loadModel result: $result")
        return result
    }

    @Synchronized
    fun unload() {
        if (!isModelLoaded) return
        LlamaBridge.shutdown()
        isModelLoaded = false
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
}
