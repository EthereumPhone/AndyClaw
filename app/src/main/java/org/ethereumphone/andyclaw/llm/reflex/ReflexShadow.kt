package org.ethereumphone.andyclaw.llm.reflex

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import kotlin.math.abs

/**
 * Shadow mode: on every eligible message, what the on-device path would have done, set beside
 * what the agent actually did. Nothing is executed and nothing leaves the phone; the counts and the
 * last [MAX_RECENT] disagreements live in `filesDir/reflex_shadow.json` and are shown in Settings.
 *
 * This is the evidence for switching instant execution on, label by label (the integration
 * plan's exit criterion: ≥ 99 % agreement over ≥ 300 fired decisions, and no disagreement that
 * changed something the user would mind).
 */
class ReflexShadow(private val file: File) {

    enum class Verdict { AGREE, DISAGREE, MISSED, CORRECT_ABSTAIN }

    /** A call the agent ran, as the shadow sees it. */
    data class AgentCall(val tool: String, val input: JsonObject?, val ok: Boolean)

    @Serializable
    data class LabelCounts(var agree: Int = 0, var disagree: Int = 0)

    @Serializable
    data class Entry(
        val atMs: Long,
        val verdict: String,
        val text: String,
        val label: String,
        val confidence: Float,
        val reflex: String,
        val agent: String,
    )

    @Serializable
    data class Stats(
        val version: Int = 1,
        val byLabel: MutableMap<String, LabelCounts> = mutableMapOf(),
        var missed: Int = 0,
        var correctAbstain: Int = 0,
        val recent: MutableList<Entry> = mutableListOf(),
    ) {
        val fired: Int get() = byLabel.values.sumOf { it.agree + it.disagree }
        val agreed: Int get() = byLabel.values.sumOf { it.agree }
    }

    private val lock = Any()
    private var cached: Stats? = null

    fun stats(): Stats = synchronized(lock) { load().copy() }

    fun clear() = synchronized(lock) {
        cached = Stats()
        file.delete()
    }

    /**
     * Records one turn. [decision] is the on-device path's answer (an [ReflexRouter.Decision.Agent]
     * with a fired gate counts as fired when M2 or the resolver refused: that is still the path
     * declining something it claimed). [agentCalls] are the calls the agent ran this turn.
     */
    fun record(text: String, decision: ReflexRouter.Decision, agentCalls: List<AgentCall>): Verdict {
        val verdict = judge(decision, agentCalls)
        synchronized(lock) {
            val s = load()
            when (verdict) {
                Verdict.AGREE -> s.byLabel.getOrPut(decision.label) { LabelCounts() }.agree++
                Verdict.DISAGREE -> s.byLabel.getOrPut(decision.label) { LabelCounts() }.disagree++
                Verdict.MISSED -> s.missed++
                Verdict.CORRECT_ABSTAIN -> s.correctAbstain++
            }
            if (verdict == Verdict.DISAGREE || verdict == Verdict.MISSED) {
                s.recent.add(
                    0,
                    Entry(
                        atMs = System.currentTimeMillis(),
                        verdict = verdict.name,
                        text = text.take(200),
                        label = decision.label,
                        confidence = decision.confidence,
                        reflex = describe(decision),
                        agent = agentCalls.joinToString("; ") { "${it.tool}(${it.input ?: ""})${if (it.ok) "" else " failed"}" }
                            .take(400).ifEmpty { "no tool" },
                    ),
                )
                while (s.recent.size > MAX_RECENT) s.recent.removeAt(s.recent.size - 1)
            }
            save(s)
        }
        Log.i(TAG, "shadow $verdict label=${decision.label} p=%.3f".format(decision.confidence))
        return verdict
    }

    private fun load(): Stats {
        cached?.let { return it }
        val s = runCatching { JSON.decodeFromString(Stats.serializer(), file.readText()) }.getOrNull() ?: Stats()
        cached = s
        return s
    }

    private fun save(s: Stats) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSON.encodeToString(Stats.serializer(), s))
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "shadow stats not saved: ${it.message}") }
    }

    companion object {
        private const val TAG = "ReflexShadow"
        const val MAX_RECENT = 50
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Tools the on-device path can produce at all; an agent call to one of them it did not make is a miss. */
        val ON_DEVICE_TOOLS: Set<String> =
            ReflexSpec.ACTIONS.values.map { it.tool }.toSet() + ReflexSpec.INTENTS.values.toSet()

        fun describe(d: ReflexRouter.Decision): String = when (d) {
            is ReflexRouter.Decision.Instant -> "${d.call.tool}(${d.call.input})"
            is ReflexRouter.Decision.Agent -> "agent (${d.reason})"
        }

        /** The verdict for one turn. Pure, so the rules are pinned by `ReflexShadowTest`. */
        fun judge(d: ReflexRouter.Decision, agentCalls: List<AgentCall>): Verdict {
            val ran = agentCalls.filter { it.ok }
            if (d is ReflexRouter.Decision.Instant) {
                return if (ran.any { it.tool == d.call.tool && sameCall(d.call.tool, d.call.input, it.input) }) {
                    Verdict.AGREE
                } else Verdict.DISAGREE
            }
            return if (ran.any { it.tool in ON_DEVICE_TOOLS }) Verdict.MISSED else Verdict.CORRECT_ABSTAIN
        }

        /**
         * Whether the agent's call does what ours would have, within tolerance: alarm to the
         * minute, reminder within a minute, volume within one step, the same package, the same
         * switch. A calendar read only has to be a calendar read.
         */
        internal fun sameCall(tool: String, ours: JsonObject, theirs: JsonObject?): Boolean {
            val t = theirs ?: return ours.isEmpty()
            fun long(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
            fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.lowercase()
            return when (tool) {
                "list_events", "get_device_info", "get_connectivity_status", "get_storage_info",
                "list_reminders", "lock_screen", "led_clear" -> true
                "set_alarm" -> long(ours, "hour") == long(t, "hour") && long(ours, "minutes") == long(t, "minutes")
                "create_reminder" -> {
                    val a = long(ours, "time"); val b = long(t, "time")
                    a != null && b != null && abs(a - b) <= 60_000
                }
                "set_volume" -> {
                    val a = long(ours, "level"); val b = long(t, "level")
                    str(ours, "stream") == str(t, "stream") && a != null && b != null && abs(a - b) <= 1
                }
                "get_token_price" -> str(ours, "token") == str(t, "token")
                // priority_only is optional and defaults to false.
                "set_dnd_mode" -> str(ours, "enabled") == str(t, "enabled") &&
                    (str(ours, "priority_only") == "true") == (str(t, "priority_only") == "true")
                // Every key we set must match; the agent may add optional ones.
                else -> ours.keys.all { k -> str(ours, k) == str(t, k) }
            }
        }
    }
}
