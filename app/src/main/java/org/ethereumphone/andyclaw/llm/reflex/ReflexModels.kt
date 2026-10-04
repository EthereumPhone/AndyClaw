package org.ethereumphone.andyclaw.llm.reflex

import android.util.Log
import com.llamatik.library.platform.LlamaBridge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.ethereumphone.andyclaw.llm.LlamaCpp
import org.ethereumphone.andyclaw.llm.LocalLlmRuntimeConfig
import kotlinx.coroutines.launch
import java.io.File

/**
 * M1, the reflex encoder: a 33 M-parameter BERT (`reflex-encoder-q8_0.gguf`) in Llamatik's
 * embedding slot, plus [ReflexHeads]. ~15 ms a sentence on the dGEN1.
 *
 * The slot is shared with nothing, but `LlamaBridge.shutdown()` (an unload of the local chat
 * model) frees it as well: [LlamaCpp.shutdowns] says when, and the next call loads it again.
 * Never re-init while our model is still loaded — Llamatik overwrites the pointer and leaks it.
 */
class ReflexModel(private val encoder: File, val heads: ReflexHeads) {

    /** [LlamaCpp.shutdowns] when our model was loaded; -1 = not loaded. Guarded by the lock. */
    private var loadedAt = -1

    fun classify(text: String): ReflexHeads.Result? = synchronized(LlamaCpp.embedLock) {
        if (loadedAt != LlamaCpp.shutdowns) {
            val t0 = System.nanoTime()
            if (!LlamaBridge.initEmbedModel(encoder.absolutePath)) {
                Log.w(TAG, "encoder failed to load")
                return null
            }
            loadedAt = LlamaCpp.shutdowns
            Log.i(TAG, "encoder loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        val v = LlamaBridge.embed(text)
        if (v.size != heads.dim) {
            Log.w(TAG, "embed returned ${v.size} dims")
            return null
        }
        heads.apply(v)
    }

    /** Raw pooled embedding, for the golden check. */
    fun embedRaw(text: String): FloatArray? = synchronized(LlamaCpp.embedLock) {
        if (loadedAt != LlamaCpp.shutdowns) {
            if (!LlamaBridge.initEmbedModel(encoder.absolutePath)) return null
            loadedAt = LlamaCpp.shutdowns
        }
        LlamaBridge.embed(text).takeIf { it.size == heads.dim }
    }

    private companion object {
        const val TAG = "ReflexModel"
    }
}

/**
 * M2, the actor: Gemma 3 270M fine-tuned to write an intent's arguments as compact JSON
 * (`reflex-actor-q8_0.gguf`, ~350 ms on the dGEN1).
 *
 * It runs in Llamatik's generation slot, which the local chat model also uses: loading one evicts
 * the other, so this goes through [LlamaCpp] and its lock like any other generation, and sets its
 * own sampling every call (the slot's parameters are global).
 */
class ActorModel(
    private val llama: LlamaCpp,
    private val file: File,
    /** Where the idle unload waits; null keeps the model loaded. */
    private val scope: kotlinx.coroutines.CoroutineScope? = null,
    private val idleMs: Long = IDLE_MS,
    /** After an idle unload, which frees Llamatik's embedding slot too: reload M1 off the user's path. */
    private val onUnloaded: () -> Unit = {},
) {
    @Volatile
    private var lastUseNs = 0L
    private var idleJob: kotlinx.coroutines.Job? = null

    /**
     * Loaded, M2 costs about 1 GB of the process (Llamatik sizes the context far beyond the 256
     * tokens asked for); idle, it is unloaded and reloads in ~3 s when next wanted.
     */
    private fun scheduleIdleUnload() {
        val s = scope ?: return
        lastUseNs = System.nanoTime()
        synchronized(this) {
            idleJob?.cancel()
            idleJob = s.launch {
                kotlinx.coroutines.delay(idleMs)
                if (System.nanoTime() - lastUseNs >= idleMs * 1_000_000) {
                    llama.unloadIfLoaded(file.absolutePath)
                    Log.i(TAG, "actor unloaded after ${idleMs / 1000} s idle")
                    onUnloaded()
                }
            }
        }
    }

    /** Raw model text for [intent] and [utterance], or null when the model could not run. */
    fun generateRaw(intent: String, utterance: String, stillWanted: () -> Boolean = { true }): String? =
        llama.withNativeLock(stillWanted) {
            if (llama.loadedModelPath != file.absolutePath || !CONFIG.hardwareEquals(llama.loadedConfig)) {
                val t0 = System.nanoTime()
                if (!llama.load(file.absolutePath, CONFIG)) {
                    Log.w(TAG, "actor failed to load")
                    return@withNativeLock null
                }
                Log.i(TAG, "actor loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
            llama.updateGenerateParams(CONFIG)
            llama.generate(ReflexSpec.m2Prompt(intent, utterance)).trim()
        }.also { scheduleIdleUnload() }

    /** The validated model-schema arguments, or null (unparseable, invalid, or no model). */
    fun fill(intent: String, utterance: String, stillWanted: () -> Boolean = { true }): JsonObject? {
        val out = generateRaw(intent, utterance, stillWanted) ?: return null
        val args = runCatching { Json.parseToJsonElement(out).jsonObject }.getOrNull()
        if (args == null) {
            Log.i(TAG, "actor output is not a JSON object (${out.length} chars)")
            return null
        }
        val why = ReflexSpec.validate(intent, args)
        if (why != null) {
            Log.i(TAG, "actor output rejected for $intent: $why")
            return null
        }
        return args
    }

    companion object {
        private const val TAG = "ActorModel"
        const val IDLE_MS = 60_000L

        /**
         * Greedy and short. Two threads were fastest on the G99; 256 tokens is ample context.
         *
         * Greedy is `topK = 1`, not `temperature = 0`: AndyClaw's Llamatik build samples through
         * `temp → dist`, and a zero temperature aborted the process inside `llama_sampler_sample`
         * on the dGEN1. With one candidate left the temperature changes nothing.
         */
        val CONFIG = LocalLlmRuntimeConfig(
            temperature = 1f, topP = 1f, topK = 1, maxTokens = 48, repeatPenalty = 1f,
            nCtx = 256, nBatch = 256, nThreads = 2, nGpuLayers = 0, useMmap = true,
        )
    }
}
