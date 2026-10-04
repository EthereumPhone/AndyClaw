package org.ethereumphone.andyclaw.llm.reflex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * M1's two linear heads over the encoder's L2-normalised CLS embedding, and the label lists they
 * were trained with. `reflex-heads.bin` and `reflex-labels.json` are a pair: ship them together.
 *
 * Heads file, little-endian: `int32 dim, nTools, nActions`, then `toolW[nTools×dim]`,
 * `toolB[nTools]`, `actionW[nActions×dim]`, `actionB[nActions]`, all float32, row-major.
 */
class ReflexHeads(
    val dim: Int,
    private val toolW: FloatArray,
    private val toolB: FloatArray,
    private val actionW: FloatArray,
    private val actionB: FloatArray,
    val labels: ReflexLabels,
) {
    /** Softmax probabilities, indexed like [ReflexLabels.tools] and [ReflexLabels.actions]. */
    class Result(val tools: FloatArray, val actions: FloatArray)

    init {
        require(toolB.size == labels.tools.size) { "heads have ${toolB.size} tools, labels ${labels.tools.size}" }
        require(actionB.size == labels.actions.size) { "heads have ${actionB.size} actions, labels ${labels.actions.size}" }
    }

    /** [embedding] is the raw pooled vector; it is normalised here, as in training. */
    fun apply(embedding: FloatArray): Result {
        require(embedding.size == dim) { "embedding has ${embedding.size} dims, heads expect $dim" }
        var n = 0.0
        for (x in embedding) n += x * x
        val norm = sqrt(n).toFloat().takeIf { it > 0f } ?: 1f
        val e = FloatArray(dim) { embedding[it] / norm }
        return Result(softmax(affine(toolW, toolB, e)), softmax(affine(actionW, actionB, e)))
    }

    private fun affine(w: FloatArray, b: FloatArray, e: FloatArray): FloatArray =
        FloatArray(b.size) { row ->
            var acc = b[row]
            val off = row * dim
            for (i in 0 until dim) acc += w[off + i] * e[i]
            acc
        }

    companion object {
        fun parse(heads: ByteArray, labels: ReflexLabels): ReflexHeads {
            val buf = ByteBuffer.wrap(heads).order(ByteOrder.LITTLE_ENDIAN)
            val dim = buf.int
            val nTools = buf.int
            val nActions = buf.int
            require(dim in 1..4096 && nTools in 1..4096 && nActions in 1..4096) { "bad heads header" }
            val expected = 12L + 4L * (nTools.toLong() * dim + nTools + nActions.toLong() * dim + nActions)
            require(heads.size.toLong() == expected) { "heads file is ${heads.size} bytes, header says $expected" }
            fun floats(n: Int) = FloatArray(n).also { buf.asFloatBuffer().get(it); buf.position(buf.position() + 4 * n) }
            val toolW = floats(nTools * dim)
            val toolB = floats(nTools)
            val actionW = floats(nActions * dim)
            val actionB = floats(nActions)
            return ReflexHeads(dim, toolW, toolB, actionW, actionB, labels)
        }

        internal fun softmax(x: FloatArray): FloatArray {
            val max = x.max()
            val out = FloatArray(x.size) { exp((x[it] - max).toDouble()).toFloat() }
            val sum = out.sum()
            for (i in out.indices) out[i] /= sum
            return out
        }
    }
}

/** `reflex-labels.json`: the label lists, the confidence threshold and the gate. */
class ReflexLabels(
    val tools: List<String>,
    val actions: List<String>,
    val tau: Float,
    val gateK: Int,
    val actionTool: Map<String, String>,
) {
    /**
     * The on-device label for [r], or null for "the agent". Both heads must agree: the action is
     * confident ([tauFor]) and its tool is in the tool head's top [gateK]. Labels outside
     * [enabled] (the allowlist, the tools this phone has) never fire.
     */
    fun decide(r: ReflexHeads.Result, enabled: (String) -> Boolean = { true }, tauFor: (String) -> Float = { tau }): Gate {
        val best = r.actions.indices.maxBy { r.actions[it] }
        val label = actions[best]
        val p = r.actions[best]
        val fired = label != ReflexSpec.NEEDS_AGENT &&
            p >= tauFor(label) &&
            enabled(label) &&
            actionTool[label]?.let { tool -> tools.indexOf(tool) in topIndices(r.tools, gateK) } == true
        return Gate(label, p, fired)
    }

    /** The best label, its probability, and whether it passed the gate. */
    data class Gate(val label: String, val confidence: Float, val fired: Boolean)

    /** Tool names by probability, best first, without "none". */
    fun topTools(r: ReflexHeads.Result, k: Int, minProb: Float = 0f): List<String> =
        topIndices(r.tools, k + 1)
            .filter { tools[it] != NO_TOOL && r.tools[it] >= minProb }
            .take(k)
            .map { tools[it] }

    companion object {
        const val NO_TOOL = "none"

        fun parse(json: String): ReflexLabels {
            val o: JsonObject = Json.parseToJsonElement(json).jsonObject
            return ReflexLabels(
                tools = o["tools"]!!.jsonArray.map { it.jsonPrimitive.content },
                actions = o["actions"]!!.jsonArray.map { it.jsonPrimitive.content },
                tau = o["tau"]!!.jsonPrimitive.double.toFloat(),
                gateK = o["gate_k"]!!.jsonPrimitive.int,
                actionTool = o["action_tool"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
            )
        }

        internal fun topIndices(p: FloatArray, k: Int): List<Int> =
            p.indices.sortedByDescending { p[it] }.take(k)
    }
}
