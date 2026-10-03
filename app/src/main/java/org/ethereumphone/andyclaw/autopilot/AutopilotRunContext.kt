package org.ethereumphone.andyclaw.autopilot

import org.ethereumphone.andyclaw.llm.LlmClient
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * What the autopilot needs from the agent run that called it: the same model client and model
 * id (so escalations go to the model the user chose, on the provider they chose), a way to count
 * those calls in the run's metrics, and where to send progress events.
 *
 * Published into the tool call's coroutine context by `ExecutionEngineFactory`, next to
 * `ProvenanceContext`, because the skill that runs the autopilot has no other way to see the run.
 */
class AutopilotRunContext(
    val client: LlmClient,
    val modelId: String,
    val onModelCall: () -> Unit = {},
    val events: AutopilotEventSink = AutopilotEventSink { },
    /** The installed app the turn router chose for this run's request ([JevTurnRouter.Route]); none for a run nobody routed. */
    val routedApp: () -> String? = { null },
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AutopilotRunContext>
}

/**
 * In the context of a run that must leave the user's compiled flows alone — AndyBench's: the
 * autopilot neither replays one first nor compiles its run into one. A benchmark that replayed
 * flows measured replays, and one that compiled them filled the user's store with its tasks.
 */
class FlowsOff : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<FlowsOff>
}
