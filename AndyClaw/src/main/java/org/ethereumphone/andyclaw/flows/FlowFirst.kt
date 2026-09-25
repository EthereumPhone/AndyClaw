package org.ethereumphone.andyclaw.flows

import org.ethereumphone.andyclaw.ExecutionEngine.Provenance

/**
 * Which compiled flow, if any, the autopilot should replay instead of driving the app.
 *
 * A successful autopilot run compiles itself into a flow, but nothing ever replayed it: the next
 * identical request went through Jev and the planner again. Now the autopilot looks first, and
 * replays a flow that is certainly this task — same id, which the compiler derives from the app,
 * the goal and the value keys — when it can run unattended:
 *
 * - not stale (a stale flow revalidates when it is called as a tool, not behind the autopilot);
 * - every value it takes is in the plan;
 * - it needs no approval card, or the user's no-confirm covers it — a replay started from
 *   inside the autopilot cannot raise a card, so it must not need one.
 */
object FlowFirst {

    fun select(
        flowId: String,
        values: Map<String, String>,
        flows: List<StoredFlow>,
        noConfirm: Boolean,
        provenance: Provenance,
    ): StoredFlow? {
        val stored = flows.filter { it.flow.flow == flowId }.maxByOrNull { it.flow.version } ?: return null
        if (stored.meta.stale) return null
        if (stored.flow.params.any { values[it].isNullOrEmpty() }) return null
        if (FlowToolEffect.requiresApproval(stored.flow) &&
            !FlowCheckpointPolicy.mayCross(approvedThisCall = false, noConfirm = noConfirm, provenance = provenance)
        ) return null
        return stored
    }
}
