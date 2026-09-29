package org.ethereumphone.andyclaw.agent

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.ethereumphone.andyclaw.ExecutionEngine.rethrowIfCancelled
import org.ethereumphone.andyclaw.autopilot.JevClient
import org.ethereumphone.andyclaw.autopilot.JevQuestion
import org.ethereumphone.andyclaw.autopilot.JevRequest
import org.ethereumphone.andyclaw.safety.ToolEffects
import org.ethereumphone.andyclaw.skills.ToolEffect

/**
 * Runs the one tool a request obviously needs before the model is asked anything.
 *
 * "What's my battery at?" used to cost two model calls: one to decide on `get_device_info`, one
 * to read its result — and the first re-sent the whole prompt (~16k tokens) to produce a single
 * tool call. One Jev call (~100–400 ms) picks that tool instead; the loop runs it and hands the
 * model the request with the call and its result already in history, so a simple question is one
 * model call.
 *
 * Deliberately narrow, because nobody reviews what runs here:
 * - only tools that take no arguments — Jev picks, it cannot write parameters;
 * - only [ToolEffect.READ] tools that send nothing off the phone ([ToolEffects.isNetworkEgress]);
 *   a wrong pick costs a harmless read, never an action;
 * - only tools the model was going to be offered this turn anyway;
 * - only when the request shares a word with the tool, so an unrelated message never leaves the
 *   device for this;
 * - only above [THRESHOLD], and within [BUDGET_MS] — Jev's hedged tail is ~1.2 s, and a turn must
 *   never wait on this longer than it would have spent on the model call it saves.
 *
 * The call runs through the ordinary execution engine, so the provenance gate, the ledger and the
 * tool callbacks see it like any other; the caller only offers it to `USER` runs.
 */
class JevToolPrefetch(
    private val jev: () -> JevClient?,
    private val enabled: () -> Boolean,
) {
    data class Candidate(val name: String, val description: String)

    data class Pick(val toolName: String, val confidence: Double, val elapsedMs: Long)

    /** The tool to run first, or null. Never throws except for cancellation. */
    suspend fun pick(userMessage: String, tools: List<JsonObject>): Pick? {
        if (!enabled() || userMessage.isBlank()) return null
        val client = jev() ?: return null
        val candidates = rank(userMessage, candidatesFrom(tools))
        if (candidates.isEmpty()) return null
        val started = SystemClock.elapsedRealtime()
        val response = withTimeoutOrNull(BUDGET_MS) {
            try {
                client.evaluate(request(userMessage, candidates))
            } catch (e: Exception) {
                rethrowIfCancelled(e)
                Log.d(TAG, "skipped: ${e.message}")
                null
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        val answer = response?.choice(TOOL)
        Log.i(TAG, "candidates=${candidates.map { it.name }} pick=${answer?.choice} " +
            "conf=${answer?.confidence?.let { "%.2f".format(java.util.Locale.ROOT, it) }} in ${elapsed}ms" +
            if (response == null) " (no answer within ${BUDGET_MS}ms)" else "")
        if (answer == null || answer.choice == NONE || answer.confidence < THRESHOLD) return null
        if (candidates.none { it.name == answer.choice }) return null
        return Pick(answer.choice, answer.confidence, elapsed)
    }

    companion object {
        private const val TAG = "JevToolPrefetch"
        private const val TOOL = "tool"
        const val NONE = "none"

        /** Jev is calibrated; below this a pick is a guess, and a guess is a wasted read. */
        const val THRESHOLD = 0.85

        /** Hard ceiling on the wait. Jev answers in ~100–400 ms when it answers at all. */
        const val BUDGET_MS = 700L

        /** Jev reads every option on every call; a short list is both faster and sharper. */
        const val MAX_CANDIDATES = 12

        /**
         * Tools that are READ and argument-free but must not run unasked: anything that reads a
         * screen (the display belongs to whichever run holds its lease) or the clipboard, and the
         * loop's own meta tools.
         */
        private val EXCLUDED = setOf(
            AgentLoop.SPAWN_SUBAGENT_TOOL_NAME,
            AgentLoop.ASK_USER_TOOL_NAME,
            "search_available_tools",
        )

        private val STOP_WORDS = setOf(
            "the", "and", "for", "you", "your", "my", "me", "is", "are", "what", "whats", "how",
            "can", "please", "tell", "show", "get", "give", "check", "do", "does", "of", "on", "in",
            "to", "it", "this", "that", "current", "currently", "now", "right", "about", "with",
        )

        fun eligible(name: String): Boolean =
            name !in EXCLUDED &&
                !name.startsWith("agent_display_") &&
                name !in ToolEffects.CLIPBOARD_READS &&
                ToolEffects.of(name) == ToolEffect.READ &&
                !ToolEffects.isNetworkEgress(name, JsonObject(emptyMap()))

        /** Argument-free, eligible tools out of the list the model is about to be given. */
        fun candidatesFrom(tools: List<JsonObject>): List<Candidate> = tools.mapNotNull { tool ->
            val name = tool["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!eligible(name)) return@mapNotNull null
            val schema = tool["input_schema"] as? JsonObject
            val required = schema?.get("required") as? JsonArray
            if (required != null && required.isNotEmpty()) return@mapNotNull null
            Candidate(name, tool["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }

        fun tokens(s: String): Set<String> =
            s.lowercase(java.util.Locale.ROOT)
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length > 2 && it !in STOP_WORDS }
                .toSet()

        /** Candidates sharing a word with the request, most shared first; none shared → empty. */
        fun rank(message: String, candidates: List<Candidate>): List<Candidate> {
            val words = tokens(message)
            if (words.isEmpty()) return emptyList()
            return candidates
                .map { c -> c to tokens("${c.name.replace('_', ' ')} ${c.description}").count { it in words } }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(MAX_CANDIDATES)
                .map { it.first }
        }

        fun request(message: String, candidates: List<Candidate>): JevRequest = JevRequest(
            "USER REQUEST: $message",
            mapOf(
                TOOL to JevQuestion.Choice(
                    "Which one of these tools, called with no arguments, returns everything needed " +
                        "to answer the request? Choose none if the request asks to change or send " +
                        "anything, needs information these tools do not return, or needs more than " +
                        "one of them.",
                    candidates.associate { it.name to "${it.name}: ${it.description.take(200)}" } +
                        (NONE to "None of these"),
                ),
            ),
        )
    }
}
