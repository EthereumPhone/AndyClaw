package org.ethereumphone.andyclaw.llm.reflex

import android.util.Log
import java.time.ZonedDateTime

/**
 * One user message → what the reflex models would do with it.
 *
 * - [Decision.Instant]: an on-device call, fully resolved. Whether it actually runs is the
 *   caller's choice (shadow mode, the per-label allowlist).
 * - [Decision.Agent]: the agent handles it, with M1's best tools as a routing hint.
 *
 * Abstains by default: a missing model, a low confidence, heads that disagree, an M2 answer that
 * does not validate, a resolver that refuses, or any throw all end in [Decision.Agent].
 */
class ReflexRouter(
    private val m1: ReflexModel,
    private val m2: () -> ActorModel?,
    private val resolver: ReflexResolver,
) {
    sealed interface Decision {
        /** M1's best tools for the message, best first, without "none". */
        val tools: List<String>
        val label: String
        val confidence: Float
        val m1Ms: Long

        data class Instant(
            val call: ReflexResolver.Call,
            override val label: String,
            override val confidence: Float,
            override val tools: List<String>,
            override val m1Ms: Long,
            val m2Ms: Long,
        ) : Decision

        data class Agent(
            override val tools: List<String>,
            override val label: String,
            override val confidence: Float,
            override val m1Ms: Long,
            /** Why the on-device path did not take it. */
            val reason: String,
        ) : Decision
    }

    /** Runs M1 only: the routing hint and the gate, no generation. Null if M1 cannot run. */
    fun classify(text: String): Pair<ReflexHeads.Result, Long>? {
        val t0 = System.nanoTime()
        val r = runCatching { m1.classify(text) }.onFailure { Log.w(TAG, "M1 failed", it) }.getOrNull() ?: return null
        return r to (System.nanoTime() - t0) / 1_000_000
    }

    /**
     * [labelEnabled]: labels this turn may fire — their tool is enabled for the turn. [useActor]:
     * false keeps M2 from running (it shares the generation slot with the local chat model).
     */
    fun decide(
        text: String,
        now: ZonedDateTime,
        labelEnabled: (String) -> Boolean,
        useActor: Boolean = true,
        stillWanted: () -> Boolean = { true },
    ): Decision? {
        val (r, m1Ms) = classify(text) ?: return null
        val labels = m1.heads.labels
        val tools = labels.topTools(r, k = ROUTING_TOOLS, minProb = ROUTING_MIN_PROB)
        val gate = labels.decide(r, labelEnabled)
        fun agent(reason: String) = Decision.Agent(tools, gate.label, gate.confidence, m1Ms, reason)
        if (!gate.fired) {
            return agent(
                when {
                    gate.label == ReflexSpec.NEEDS_AGENT -> "needs_agent"
                    !labelEnabled(gate.label) -> "disabled"
                    gate.confidence < labels.tau -> "low_confidence"
                    else -> "heads_disagree"
                },
            )
        }
        return try {
            if (gate.label in ReflexSpec.INTENTS) {
                val actor = (if (useActor) m2() else null) ?: return agent("no_actor")
                val t0 = System.nanoTime()
                val args = actor.fill(gate.label, text, stillWanted)
                val m2Ms = (System.nanoTime() - t0) / 1_000_000
                if (args == null) return agent("actor_invalid")
                val call = resolver.resolveIntent(gate.label, args, text, now) ?: return agent("resolver_refused")
                Decision.Instant(call, gate.label, gate.confidence, tools, m1Ms, m2Ms)
            } else {
                val call = resolver.resolveAction(gate.label, now) ?: return agent("resolver_refused")
                Decision.Instant(call, gate.label, gate.confidence, tools, m1Ms, 0)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "on-device path failed for ${gate.label}", e)
            agent("error")
        }
    }

    companion object {
        private const val TAG = "ReflexRouter"
        /** Tools offered to the agent ahead of search. M1's recall@5 on the held-out set is ~0.9. */
        const val ROUTING_TOOLS = 5
        const val ROUTING_MIN_PROB = 0.02f
    }
}
