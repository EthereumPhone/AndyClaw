package org.ethereumphone.andyclaw.autopilot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Jev — TypeSafe's "System One" model — answers a set of typed questions about one piece of
 * state, all in parallel, in ~100 ms. It cannot write text, plan, count or look at images; it
 * can only pick among options it is given, with calibrated probabilities. The autopilot uses it
 * for exactly that: "which of these elements advances this sub-goal?"
 *
 * Wire format (via our backend's `/api/jev`, which forwards to OpenRouter's Decisions API):
 * request `{state, questions:{id:{type, instructions, criteria?}}}`,
 * response `{answers:{id:{...}}, usage, jev:{upstream_ms,total_ms}}`.
 */
sealed interface JevQuestion {
    val instructions: String

    /** Yes/no, answered as a probability. */
    data class Noul(override val instructions: String) : JevQuestion

    /** Pick one of [options] (id -> description). At most 255 options. */
    data class Choice(override val instructions: String, val options: Map<String, String>) : JevQuestion {
        init {
            require(options.isNotEmpty()) { "a choice needs at least one option" }
            require(options.size <= MAX_OPTIONS) { "a choice takes at most $MAX_OPTIONS options, got ${options.size}" }
        }
    }

    companion object {
        const val MAX_OPTIONS = 255
    }
}

data class JevRequest(val state: String, val questions: Map<String, JevQuestion>) {

    fun toJson(): JsonObject = buildJsonObject {
        put("state", state)
        putJsonObject("questions") {
            questions.forEach { (id, q) ->
                putJsonObject(id) {
                    when (q) {
                        is JevQuestion.Noul -> {
                            put("type", "noul")
                            put("instructions", q.instructions)
                        }
                        is JevQuestion.Choice -> {
                            put("type", "choice")
                            put("instructions", q.instructions)
                            putJsonObject("criteria") { q.options.forEach { (k, v) -> put(k, v) } }
                        }
                    }
                }
            }
        }
    }
}

sealed interface JevAnswer {
    data class Noul(val p: Double) : JevAnswer

    data class Choice(
        val choice: String,
        val probabilities: Map<String, Double>,
        val confidence: Double,
    ) : JevAnswer {
        /** Probability gap between the chosen option and the runner-up. */
        val margin: Double
            get() {
                val top = probabilities[choice] ?: confidence
                val second = probabilities.filterKeys { it != choice }.values.maxOrNull() ?: 0.0
                return (top - second).coerceAtLeast(0.0)
            }
    }
}

data class JevResponse(
    val answers: Map<String, JevAnswer>,
    /** Wall clock on the device, request to parsed answer. */
    val rttMs: Long,
    /** Time the backend spent waiting on Jev, when it reports it. */
    val upstreamMs: Long? = null,
    val inputTokens: Int = 0,
) {
    fun choice(id: String): JevAnswer.Choice? = answers[id] as? JevAnswer.Choice
    fun noul(id: String): Double? = (answers[id] as? JevAnswer.Noul)?.p
}

/** The one thing the autopilot needs from the network. */
fun interface JevClient {
    suspend fun evaluate(request: JevRequest): JevResponse
}

/** Jev cannot be used right now (disabled server-side, no balance, auth). Not worth retrying. */
class JevUnavailableException(message: String, val statusCode: Int? = null) : Exception(message)

object JevResponseParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Lenient on purpose: an unknown extra field must not break a step, and a malformed answer
     * for one question only drops that question (the policy treats a missing answer as "unsure").
     */
    fun parse(body: String, questions: Map<String, JevQuestion>, rttMs: Long): JevResponse {
        val root = json.parseToJsonElement(body).jsonObject
        val answersObj = root["answers"] as? JsonObject ?: JsonObject(emptyMap())
        val answers = mutableMapOf<String, JevAnswer>()
        for ((id, q) in questions) {
            val a = answersObj[id] as? JsonObject ?: continue
            when (q) {
                is JevQuestion.Noul -> a.num("noul")?.let { answers[id] = JevAnswer.Noul(it.coerceIn(0.0, 1.0)) }
                is JevQuestion.Choice -> {
                    val choice = (a["choice"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                    if (choice !in q.options) continue // never act on an option we did not offer
                    val probs = (a["probabilities"] as? JsonObject)
                        ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }
                        ?.toMap()
                        .orEmpty()
                    val confidence = a.num("confidence") ?: probs[choice] ?: 0.0
                    answers[id] = JevAnswer.Choice(choice, probs, confidence.coerceIn(0.0, 1.0))
                }
            }
        }
        val usage = root["usage"] as? JsonObject
        val jev = root["jev"] as? JsonObject
        return JevResponse(
            answers = answers,
            rttMs = rttMs,
            upstreamMs = jev?.num("upstream_ms")?.toLong(),
            inputTokens = (usage?.get("input_tokens") as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
