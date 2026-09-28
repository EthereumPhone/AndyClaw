package org.ethereumphone.andyclaw.autopilot

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What the planner model hands the autopilot: a goal broken into sub-goals, plus every literal
 * the task needs typed. Jev never produces text — it only decides *where* a value goes — so all
 * text lives in [values] and a sub-goal names the keys it may type via [PlanStep.typeKeys].
 */
data class AutopilotPlan(
    val packageName: String,
    val goal: String,
    val steps: List<PlanStep>,
    val values: Map<String, String> = emptyMap(),
    /** Reply for the user when the goal is reached, so the planner need not be asked again. */
    val say: String? = null,
    val finish: Finish = Finish.KEEP,
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    /**
     * Where in the app to start, as an intent URI (`intent:#Intent;action=…;end`). A settings
     * page has a public action; opening it directly skips the navigation the plan would
     * otherwise guess at (DND took 17 model calls through Settings' menus, one intent here).
     */
    val startIntent: String? = null,
) {
    enum class Finish { KEEP, DESTROY, PROMOTE }

    companion object {
        const val DEFAULT_MAX_STEPS = 25
        const val HARD_MAX_STEPS = 40
        const val MAX_SUBGOALS = 12

        /**
         * Parses the `agent_display_autopilot` tool input. Returns an error message instead of
         * throwing, because that message goes straight back to the planner to fix its call.
         */
        fun fromToolInput(input: JsonObject): Result<AutopilotPlan> {
            val pkg = input.str("package_name")
                ?: return Result.failure(IllegalArgumentException("package_name is required"))
            val goal = input.str("goal")
                ?: return Result.failure(IllegalArgumentException("goal is required"))
            val values = (input["values"] as? JsonObject)
                ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
                ?.toMap()
                .orEmpty()
            val rawSteps = (input["steps"] as? JsonArray).orEmpty()
            val steps = rawSteps.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val doText = o.str("do") ?: return@mapNotNull null
                val typeKeys = when (val t = o["type"]) {
                    is JsonPrimitive -> listOfNotNull(t.contentOrNull)
                    is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    else -> emptyList()
                }
                val unknown = typeKeys.filter { it !in values }
                if (unknown.isNotEmpty()) {
                    return Result.failure(IllegalArgumentException(
                        "step \"$doText\" types ${unknown.joinToString()} but values has no such key"))
                }
                PlanStep(
                    doText = doText,
                    doneWhen = o.str("done_when"),
                    typeKeys = typeKeys,
                    needsPlanner = o.str("needs") == "planner",
                )
            }
            // A plan with no steps is a single sub-goal: the goal itself.
            val effectiveSteps = steps.ifEmpty { listOf(PlanStep(doText = goal)) }
            if (effectiveSteps.size > MAX_SUBGOALS) {
                return Result.failure(IllegalArgumentException("at most $MAX_SUBGOALS steps"))
            }
            val finish = when (input.str("finish")?.lowercase()) {
                "destroy" -> Finish.DESTROY
                "promote" -> Finish.PROMOTE
                else -> Finish.KEEP
            }
            val maxSteps = ((input["max_steps"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_MAX_STEPS)
                .coerceIn(1, HARD_MAX_STEPS)
            val startIntent = input.str("start_intent")?.let { raw ->
                startIntentUri(raw) ?: return Result.failure(IllegalArgumentException(
                    "start_intent must be an intent action such as android.settings.WIFI_SETTINGS, " +
                        "or an intent: URI"))
            }
            return Result.success(AutopilotPlan(pkg, goal, effectiveSteps, values, input.str("say"), finish, maxSteps, startIntent))
        }

        /** `android.settings.X` becomes `intent:#Intent;action=android.settings.X;end`; an intent: URI is kept. */
        fun startIntentUri(raw: String): String? = when {
            raw.startsWith("intent:") -> raw
            ACTION.matches(raw) -> "intent:#Intent;action=$raw;end"
            else -> null
        }

        private val ACTION = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }
}

data class PlanStep(
    /** What to do, in words: "Open the conversation with Anna". */
    val doText: String,
    /** How to tell it is done: "Anna's conversation is open". */
    val doneWhen: String? = null,
    /** Keys of [AutopilotPlan.values] this sub-goal may type. */
    val typeKeys: List<String> = emptyList(),
    /** Needs comparison, counting or arithmetic — Jev cannot do that, so ask the planner. */
    val needsPlanner: Boolean = false,
)
