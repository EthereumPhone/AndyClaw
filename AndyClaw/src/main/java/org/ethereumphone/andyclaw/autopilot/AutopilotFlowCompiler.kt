package org.ethereumphone.andyclaw.autopilot

import org.ethereumphone.andyclaw.flows.CheckpointStep
import org.ethereumphone.andyclaw.flows.Flow
import org.ethereumphone.andyclaw.flows.FlowCodec
import org.ethereumphone.andyclaw.flows.FlowIntent
import org.ethereumphone.andyclaw.flows.FlowIntentStep
import org.ethereumphone.andyclaw.flows.FlowStep
import org.ethereumphone.andyclaw.flows.FlowStepEffects
import org.ethereumphone.andyclaw.flows.FlowValidation
import org.ethereumphone.andyclaw.flows.FlowValidator
import org.ethereumphone.andyclaw.flows.NodeExists
import org.ethereumphone.andyclaw.flows.NodeTextContains
import org.ethereumphone.andyclaw.flows.Selector
import org.ethereumphone.andyclaw.flows.TapStep
import org.ethereumphone.andyclaw.flows.TypeStep
import org.ethereumphone.andyclaw.skills.ToolEffect

/**
 * Turns a successful autopilot run into a flow, mechanically — no model call.
 *
 * The run already knows everything a flow needs: which element each step hit (by view id),
 * which plan value each typed text came from (so it becomes a `{{param}}`), and what the
 * finished screen looked like. The next time the same task comes up, the flow replays in
 * well under a second with no model and no Jev.
 *
 * Deliberately conservative. A run is only compiled when every step is a tap or a type on an
 * element with a view id that was unique on its screen, nothing was undone, and the result can
 * be asserted. Anything else is left to the autopilot, which is fast enough on its own.
 */
object AutopilotFlowCompiler {

    sealed interface Result {
        data class Compiled(val flow: Flow) : Result
        data class Skipped(val reason: String) : Result
    }

    fun compile(result: AutopilotResult, flowId: String, appVersionRange: String): Result {
        if (result.status != AutopilotResult.Status.SUCCESS) return Result.Skipped("not_successful")
        val plan = result.plan ?: return Result.Skipped("no_plan")
        val actions = result.actions
        if (actions.isEmpty()) return Result.Skipped("no_actions")
        if (result.escalations.isNotEmpty()) return Result.Skipped("needed_the_planner")
        // A flow replays from the app's own start; its first target would not be there.
        if (plan.startIntent != null) return Result.Skipped("started_at_intent")

        val steps = ArrayList<FlowStep>()
        for (a in actions) {
            val target = a.target ?: return Result.Skipped("untargeted_action")
            val viewId = target.viewId ?: return Result.Skipped("no_view_id")
            if (a.screenBefore.elements.count { it.viewId == viewId } != 1) return Result.Skipped("view_id_not_unique")
            if (StepPolicy.isSensitive(target)) return Result.Skipped("sensitive")
            val step: FlowStep = when (val o = a.option) {
                is StepOption.Tap -> TapStep(
                    viewId = viewId,
                    // The label says "Send" even where the view id is opaque: raise the
                    // classification so the checkpoint lands where it belongs.
                    effect = if (StepPolicy.isCommitLike(target)) ToolEffect.IRREVERSIBLE.name else null,
                )
                is StepOption.Type -> TypeStep(target = Selector(viewId = viewId), value = "{{${o.valueKey}}}")
                else -> return Result.Skipped("unsupported_action:${o.key.substringBefore(':')}")
            }
            if (FlowStepEffects.of(step) == ToolEffect.IRREVERSIBLE) {
                steps += CheckpointStep(name = "commit_${steps.size}")
            }
            steps += step
        }

        val first = actions.first()
        val preconditions = listOf(NodeExists(viewId = first.target!!.viewId))
        val postcondition = postcondition(result, plan) ?: return Result.Skipped("nothing_to_assert")

        val flow = Flow(
            flow = flowId,
            version = 1,
            app = plan.packageName,
            appVersionRange = appVersionRange,
            params = plan.values.keys.filter { key -> actions.any { (it.option as? StepOption.Type)?.valueKey == key } },
            preconditions = preconditions,
            steps = steps,
            postconditions = listOf(postcondition),
            intent = FlowIntent(
                goal = intentGoal(plan.goal, plan.values),
                steps = plan.steps.map {
                    FlowIntentStep(parameterize(it.doText, plan.values), it.doneWhen?.let { d -> parameterize(d, plan.values) }, it.typeKeys)
                },
            ),
        )
        return when (val v = FlowValidator.validate(flow)) {
            is FlowValidation.Valid -> Result.Compiled(flow)
            is FlowValidation.Invalid -> Result.Skipped("invalid:" + v.errors.joinToString { it.code })
        }
    }

    /**
     * What proves the flow worked. Best: a typed value visible on the finished screen in a node
     * with a view id (the sent message). Next: a view id that exists at the end but did not
     * exist when the flow started (a new screen).
     */
    private fun postcondition(result: AutopilotResult, plan: AutopilotPlan) =
        result.finalScreen?.let { end ->
            val typedKeys = result.actions.mapNotNull { (it.option as? StepOption.Type)?.valueKey }
            typedKeys.firstNotNullOfOrNull { key ->
                val value = plan.values[key]?.takeIf { it.length >= 2 } ?: return@firstNotNullOfOrNull null
                end.elements
                    .filter { it.viewId != null && !it.editable }
                    .firstOrNull { e -> listOfNotNull(e.label, e.value, e.summary).any { it.contains(value, ignoreCase = true) } }
                    ?.let { NodeTextContains(viewId = it.viewId, value = "{{$key}}") }
            } ?: run {
                val before = result.actions.first().screenBefore.elements.mapNotNull { it.viewId }.toSet()
                end.elements.firstOrNull { it.viewId != null && it.viewId !in before }
                    ?.let { NodeExists(viewId = it.viewId) }
            }
        }

    /**
     * `org.ethereumhpone.messenger` + "Send 'hi' to Anna" with values {body: hi}
     * -> `messenger.send_body_to_anna_` + 8 hex digits. Typed values become their key names, so
     * the same task with different text maps to the same flow.
     *
     * The readable part is cut short and keeps only ASCII, so on its own it cannot tell tasks
     * apart: "…and turn Wi-Fi off" and "…and turn Wi-Fi on" shared an id, and so did every goal
     * written in Cyrillic or CJK (`<app>.task`) — and an install replaces the flow with the same
     * id. The suffix is a hash of the whole task: the package, the goal as [compile] stores it,
     * and the value keys. The id stays within the old length, so tool names do not grow.
     *
     * Only new compilations get the suffix. An id is part of a flow's content, so the flows
     * already installed keep theirs and still verify; [org.ethereumphone.andyclaw.flows.FlowFirst]
     * does not match on the id at all.
     */
    fun flowIdFor(packageName: String, goal: String, values: Map<String, String> = emptyMap()): String {
        val app = packageName.substringAfterLast('.').lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "app" }
        var g = goal
        values.entries.sortedByDescending { it.value.length }.forEach { (k, v) -> if (v.isNotBlank()) g = wholeWord(v).replace(g, k) }
        val slug = g.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(SLUG_LENGTH).trim('_').ifEmpty { "task" }
        return "$app.${slug}_${taskHash(packageName, goal, values)}"
    }

    /** Readable part of an id; with `_` and the hash it stays within the 40 characters it always had. */
    private const val SLUG_LENGTH = 31
    private const val HASH_LENGTH = 8

    /** The first [HASH_LENGTH] hex digits of sha256 over the task, length-prefixed so no two tasks run together. */
    private fun taskHash(packageName: String, goal: String, values: Map<String, String>): String {
        val parts = listOf(packageName, intentGoal(goal, values)) + values.keys.sorted()
        val canonical = buildString { parts.forEach { append(it.length).append(':').append(it) } }
        return FlowCodec.sha256Hex(canonical.toByteArray(Charsets.UTF_8)).take(HASH_LENGTH)
    }

    /**
     * The goal as a compiled flow stores it (`Flow.intent.goal`): every value taken out as its
     * `{{key}}`. What [org.ethereumphone.andyclaw.flows.FlowFirst] compares a new plan's goal
     * against, so the two can never be worked out differently.
     */
    fun intentGoal(goal: String, values: Map<String, String>): String = parameterize(goal, values)

    /** Replaces literal values with `{{key}}` so a stored intent is reusable. */
    fun parameterize(text: String, values: Map<String, String>): String {
        var t = text
        values.entries.sortedByDescending { it.value.length }.forEach { (k, v) ->
            if (v.isNotBlank()) t = wholeWord(v).replace(t, Regex.escapeReplacement("{{$k}}"))
        }
        return t
    }

    /** [value] as a whole word or phrase — "hi" must not match inside "this". */
    private fun wholeWord(value: String) =
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(value) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
}
