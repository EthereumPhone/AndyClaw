package org.ethereumphone.andyclaw.flows

import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.autopilot.AutopilotFlowCompiler

/**
 * Which compiled flow, if any, the autopilot should replay instead of driving the app.
 *
 * A successful autopilot run compiles itself into a flow, but nothing ever replayed it: the next
 * identical request went through Jev and the planner again. Now the autopilot looks first, and
 * replays a flow that is certainly this task — see [claims] — when it can run unattended:
 *
 * - not stale (a stale flow revalidates when it is called as a tool, not behind the autopilot);
 * - every value it takes is in the plan;
 * - it needs no approval card, or the user's no-confirm covers it — a replay started from
 *   inside the autopilot cannot raise a card, so it must not need one.
 *
 * **Never by id.** The id was the only test, and an id is a name cut to 40 ASCII characters:
 * "…turn Wi-Fi off" and "…turn Wi-Fi on" shared one, every Cyrillic or CJK goal was `<app>.task`,
 * two apps whose package names end alike shared theirs — and with no-confirm on by default, a
 * user's request replayed the other task's flow unprompted. What a flow is for is its stored goal,
 * so that is what is compared, together with the app and the values. It also keeps every flow
 * already on a device usable: each one the autopilot compiled carries its goal, under whichever
 * id it was given.
 */
object FlowFirst {

    fun select(
        packageName: String,
        goal: String,
        values: Map<String, String>,
        flows: List<StoredFlow>,
        noConfirm: Boolean,
        provenance: Provenance,
    ): StoredFlow? {
        val stored = flows.filter { claims(it.flow, packageName, goal, values) }.maxWithOrNull(NEWEST) ?: return null
        if (stored.meta.stale) return null
        if (stored.flow.params.any { values[it].isNullOrEmpty() }) return null
        if (FlowToolEffect.requiresApproval(stored.flow) &&
            !FlowCheckpointPolicy.mayCross(approvedThisCall = false, noConfirm = noConfirm, provenance = provenance)
        ) return null
        return stored
    }

    /**
     * Whether [flow] is the compiled form of this task: the same app, the same goal once the
     * values are taken out of it — exactly as the compiler stored it ([AutopilotFlowCompiler.intentGoal]) —
     * and exactly these values as its parameters.
     *
     * The values have to match both ways. A value the flow does not take is part of the task it
     * would not do — or a name it would not type, because it taps the row it saw when it was
     * compiled: "Open the chat with {{name}}" compiled for Anna, with no `name` param, would open
     * Anna's chat for Bob. A flow that stores no goal cannot say what it does and is never chosen.
     */
    fun claims(flow: Flow, packageName: String, goal: String, values: Map<String, String>): Boolean {
        val intent = flow.intent ?: return false
        return flow.app == packageName &&
            flow.params.toSet() == values.keys &&
            intent.goal == AutopilotFlowCompiler.intentGoal(goal, values)
    }

    /**
     * Whether [a] and [b] are compiled forms of one task — the same app, stored goal and parameters.
     * A newer one replaces an older one when it is installed ([FlowStore.install]), whatever ids
     * they were given.
     */
    fun sameTask(a: Flow, b: Flow): Boolean {
        val goalA = a.intent?.goal ?: return false
        val goalB = b.intent?.goal ?: return false
        return a.app == b.app && goalA == goalB && a.params.toSet() == b.params.toSet()
    }

    /**
     * One task compiled more than once — an id from before ids carried a hash, or a crash between
     * an install and its cleanup — is replayed as its newest compilation.
     */
    private val NEWEST = compareBy<StoredFlow>({ it.meta.installedMs }, { it.flow.version }, { it.hash })
}
