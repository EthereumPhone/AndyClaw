package org.ethereumphone.andyclaw.llm

import android.util.Log

/**
 * JNI bindings for the llama.cpp inference engine.
 *
 * Manages the native model lifecycle: load, inference, unload.
 * All methods are thread-safe via synchronized blocks on the instance.
 */
class LlamaCpp {

    companion object {
        private const val TAG = "LlamaCpp"

        init {
            try {
                System.loadLibrary("llama_bridge")
                Log.i(TAG, "llama_bridge native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "llama_bridge native library not available: ${e.message}")
            }
        }
    }

    @Volatile
    var isModelLoaded: Boolean = false
        private set

    @Synchronized
    fun load(modelPath: String, contextSize: Int = 4096): Boolean {
        if (isModelLoaded) {
            Log.d(TAG, "Model already loaded, unloading first")
            unload()
        }
        val result = loadModel(modelPath, contextSize)
        isModelLoaded = result
        Log.i(TAG, "loadModel result: $result")
        return result
    }

    @Synchronized
    fun unload() {
        if (!isModelLoaded) return
        unloadModel()
        isModelLoaded = false
        Log.i(TAG, "Model unloaded")
    }

    @Synchronized
    fun complete(requestJson: String): String {
        check(isModelLoaded) { "Model not loaded" }
        return chatCompletion(requestJson)
    }

    @Synchronized
    fun completeStream(requestJson: String, callback: LlamaStreamCallback) {
        check(isModelLoaded) { "Model not loaded" }
        chatCompletionStream(requestJson, callback)
    }

    // JNI methods
    private external fun loadModel(modelPath: String, nCtx: Int): Boolean
    private external fun unloadModel()
    private external fun chatCompletion(requestJson: String): String
    private external fun chatCompletionStream(requestJson: String, callback: LlamaStreamCallback)
}

/**
 * Callback interface for streaming tokens from llama.cpp JNI.
 */
interface LlamaStreamCallback {
    fun onToken(token: String)
    fun onComplete(responseJson: String)
    fun onError(error: String)
}
