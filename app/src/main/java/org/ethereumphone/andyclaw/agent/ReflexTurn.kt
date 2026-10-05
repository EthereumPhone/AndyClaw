package org.ethereumphone.andyclaw.agent

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.ethereumphone.andyclaw.llm.reflex.ReflexRouter
import org.ethereumphone.andyclaw.llm.reflex.ReflexRuntime
import org.ethereumphone.andyclaw.llm.reflex.ReflexShadow
import org.ethereumphone.andyclaw.llm.reflex.ReflexSpec
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The reflex models' part in one [AgentLoop] turn of the user's own words.
 *
 * - [tools]: M1's best tools for the message, loaded for the agent ahead of any search.
 * - [instant]: a resolved on-device call to run now instead of asking a model, when instant
 *   actions are on and the label is allowed. Otherwise null.
 * - Shadow: what the on-device path would have done, compared with what the agent ran when the
 *   turn ends ([finish]). Its M2 call runs beside the agent's, never in front of it.
 */
class ReflexTurn private constructor(
    private val runtime: ReflexRuntime,
    private val text: String,
    val tools: List<String>,
    val instant: ReflexRouter.Decision.Instant?,
    private val shadow: Deferred<ReflexRouter.Decision?>?,
) {
    private val agentCalls = CopyOnWriteArrayList<ReflexShadow.AgentCall>()

    /** A tool the agent ran this turn. */
    fun noteAgentCall(tool: String, input: JsonObject?, ok: Boolean) {
        agentCalls += ReflexShadow.AgentCall(tool, input, ok)
    }

    /**
     * The turn is over. [completed]: it ended with an answer, not an error, a cancel or a STOP —
     * only those are compared, since an interrupted agent did not show what it would have done.
     */
    fun finish(completed: Boolean) {
        val pending = shadow ?: return
        if (!completed) { pending.cancel(); return }
        val calls = agentCalls.toList()
        runtime.scope.launch {
            val decision = runCatching { pending.await() }.getOrNull() ?: return@launch
            runCatching { runtime.shadow.record(text, decision, calls) }
                .onFailure { Log.w(TAG, "shadow record failed: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "ReflexTurn"
        /** M1 was trained on short commands. */
        const val MAX_CHARS = 200

        /**
         * Words that point back into the conversation. M1 reads one sentence: "do it at 8
         * instead", "yes", "the same for bluetooth" mean nothing to it.
         */
        private val REFERS_BACK = Regex(
            "\\b(it|its|that|this|those|these|them|instead|again|same|also|another|" +
                "yes|yeah|yep|no|nope|ok|okay|sure|please do|go ahead|then|him|her|there)\\b",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Whether [text] can be read on its own. The home screen keeps one conversation going for
         * days, so this is decided by the words, not by the conversation being new: "wifi off"
         * after twenty other commands is still self-contained. Not when it refers back, and not
         * when the last reply asked something — then the message is an answer.
         */
        fun isSelfContained(text: String, previousReply: String?): Boolean {
            if (previousReply?.trimEnd()?.endsWith("?") == true) return false
            return !REFERS_BACK.containsMatchIn(text)
        }

        /**
         * Null when the models are not ready (the first call starts loading them) or the message is
         * not one M1 can read. Runs M1 on the caller's thread (~15 ms) and, only when an intent
         * is about to be executed, M2 (~0.5 s; ~3 s if it was unloaded).
         *
         * [previousReply]: the assistant's last text in this conversation, or null if none.
         * [enabledTools]: the tools this turn may call, by their original names. [localModel]:
         * the chat itself runs on the local model, whose generation slot M2 would evict.
         */
        fun start(
            runtime: ReflexRuntime,
            text: String,
            previousReply: String?,
            enabledTools: Set<String>,
            localModel: Boolean,
            instantOn: Boolean,
            stillWanted: () -> Boolean = { true },
        ): ReflexTurn? {
            val message = text.trim()
            if (message.isEmpty() || message.length > MAX_CHARS) return null
            val router = runtime.routerIfReady() ?: return null
            val now = ZonedDateTime.now()
            val labelEnabled = { label: String -> ReflexSpec.toolFor(label) in enabledTools }
            // M1 only: the routing hint, the gate, and the M1-only actions resolved.
            val quick = router.decide(message, now, labelEnabled, useActor = false)
            // Counted for Settings ("read 148 requests"), labels and times only.
            runCatching { runtime.activity.noteRead() }
            if (quick == null) return null
            if (!isSelfContained(message, previousReply)) {
                return ReflexTurn(runtime, message, quick.tools, null, null)
            }
            val needsActor = quick is ReflexRouter.Decision.Agent && quick.reason == "no_actor"
            val fired = quick is ReflexRouter.Decision.Instant || needsActor
            if (instantOn && fired && quick.label in ReflexRuntime.INSTANT_LABELS) {
                val decision = if (needsActor) router.decide(message, now, labelEnabled, useActor = !localModel, stillWanted = stillWanted) else quick
                val instant = decision as? ReflexRouter.Decision.Instant
                Log.i(TAG, "instant ${quick.label}: ${decision?.let { ReflexShadow.describe(it) }}")
                // A refusal is still a decision worth comparing; an instant call is not shadowed.
                val shadow = if (instant == null) CompletableDeferred(decision) else null
                return ReflexTurn(runtime, message, quick.tools, instant, shadow)
            }
            val shadow: Deferred<ReflexRouter.Decision?> = if (needsActor && !localModel) {
                runtime.scope.async { router.decide(message, now, labelEnabled, useActor = true) }
            } else CompletableDeferred(quick)
            return ReflexTurn(runtime, message, quick.tools, null, shadow)
        }
    }
}
