package org.ethereumphone.andyclaw.flows

import org.ethereumphone.andyclaw.skills.ToolEffect

/**
 * Whether a flow the model compiled from a recording is the recording.
 *
 * Compile-on-discovery hands the model a mechanical draft and asks for the parts no recorder can
 * derive — parameters, conditions, checkpoints. What comes back was installed if it merely
 * *validated*, so nothing stopped the model from retargeting a tap, dropping a step, inventing
 * one, or writing a postcondition the finished screen never satisfied. A flow is replayed later
 * with the user's authority and no model watching; it has to be exactly what happened.
 *
 * What the model may do: add `assert`, `wait_for` and `checkpoint` steps, raise a tap's effect,
 * and turn typed text into `{{params}}`. What it may not: add, drop, reorder or retarget a tap or
 * a type, change a recorded checksum, change what was typed, or claim conditions the screens
 * did not show. And a recording with actions the IR cannot express — a swipe, a back press, a
 * long-press — does not compile at all: replaying the rest would not be the same task.
 */
object FlowCompileConformance {

    /** Every way [compiled] departs from [draft]; empty when it conforms. */
    fun check(draft: FlowDraft, compiled: Flow, firstTree: String?, finalTree: String?): List<String> {
        val problems = mutableListOf<String>()

        if (compiled.app != draft.flow.app) problems += "app ${compiled.app} is not the recorded ${draft.flow.app}"
        if (!draft.isMechanicallyComplete) {
            problems += "the recording has actions a flow cannot replay: ${draft.unsupportedActions.joinToString()}"
        }

        val recorded = draft.flow.steps
        val replayed = compiled.steps.withIndex().filter { (_, s) -> s is TapStep || s is TypeStep }
        if (replayed.size != recorded.size) {
            problems += "${replayed.size} tap/type steps, but ${recorded.size} were recorded"
        }
        for ((i, pair) in replayed.zip(recorded).withIndex()) {
            val (compiledIndex, step) = pair.first
            val original = pair.second
            describeMismatch(step, original)?.let { problems += "step $compiledIndex: $it" }
            val source = draft.stepSources.getOrNull(i)
            if (source != null && source.matchesBefore > 1) {
                val irreversible = FlowStepEffects.of(step).ordinal >= ToolEffect.IRREVERSIBLE.ordinal
                if (irreversible || !FlowTargetGuard.identityAssertedAfter(compiled.steps, compiledIndex)) {
                    problems += "step $compiledIndex: ${source.viewId} was one of ${source.matchesBefore} matching " +
                        "nodes, and no node_text_contains assert proves the right one before the next irreversible step"
                }
            }
        }

        if (firstTree == null) {
            problems += "no screen was recorded before the first action, so the preconditions cannot be checked"
        } else {
            compiled.preconditions.filterNot { plausible(it, firstTree) }
                .forEach { problems += "precondition ${it.opcode}(${it.viewId}) did not hold on the first screen" }
        }
        if (finalTree == null) {
            problems += "no final screen was recorded, so the postconditions cannot be checked"
        } else {
            compiled.postconditions.filterNot { plausible(it, finalTree) }
                .forEach { problems += "postcondition ${it.opcode}(${it.viewId}) did not hold on the final screen" }
        }
        return problems
    }

    private fun describeMismatch(step: FlowStep, original: FlowStep): String? = when {
        step is TapStep && original is TapStep -> when {
            step.viewId != original.viewId -> "taps ${step.viewId}, the recording tapped ${original.viewId}"
            (step.index ?: 0) != (original.index ?: 0) -> "takes match ${step.index}, the recording took ${original.index ?: 0}"
            step.expectChecksum != original.expectChecksum -> "changes the recorded screen checksum"
            else -> null
        }
        step is TypeStep && original is TypeStep -> when {
            step.target.viewId != original.target.viewId ->
                "types into ${step.target.viewId}, the recording typed into ${original.target.viewId}"
            step.expectChecksum != original.expectChecksum -> "changes the recorded screen checksum"
            !templateMatches(step.value, original.value) -> "types something other than what was typed"
            else -> null
        }
        else -> "is a ${step.opcode} where the recording has a ${original.opcode}"
    }

    /**
     * Whether [condition] held on [tree], with any `{{param}}` standing for whatever was typed.
     * A condition on text the screen did not show is a claim the recording does not support.
     */
    private fun plausible(condition: Condition, tree: String): Boolean {
        val viewId = condition.viewId ?: return false
        if (viewId !in NodeTreeChecksum.viewIdsOf(tree)) return false
        return when (condition) {
            is NodeExists -> true
            is NodeTextContains -> templateMatches(condition.value, NodeTreeChecksum.textOf(tree, viewId), wholeLine = false)
        }
    }

    /**
     * Whether [template] — literal text with `{{param}}` placeholders — could have produced
     * [actual]. Each placeholder stands for at least one character.
     */
    fun templateMatches(template: String, actual: String, wholeLine: Boolean = true): Boolean {
        val pattern = buildString {
            var last = 0
            for (m in PLACEHOLDER.findAll(template)) {
                append(Regex.escape(template.substring(last, m.range.first)))
                append("(.+?)")
                last = m.range.last + 1
            }
            append(Regex.escape(template.substring(last)))
        }
        val regex = Regex(if (wholeLine) "^$pattern$" else pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return regex.containsMatchIn(actual)
    }

    private val PLACEHOLDER = Regex("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}")
}
