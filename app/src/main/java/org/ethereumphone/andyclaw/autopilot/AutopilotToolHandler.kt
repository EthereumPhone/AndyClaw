package org.ethereumphone.andyclaw.autopilot

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.ExecutionEngine.currentProvenance
import org.ethereumphone.andyclaw.ExecutionEngine.rethrowIfCancelled
import org.ethereumphone.andyclaw.agent.currentRunToken
import org.ethereumphone.andyclaw.flows.FlowInstallResult
import org.ethereumphone.andyclaw.flows.FlowRepository
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayBinder
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayLease
import kotlin.coroutines.coroutineContext

/**
 * Runs `agent_display_autopilot`: parse the plan, drive the app with Jev, and hand back a
 * result small enough that the conversation does not grow by a screen per step.
 */
class AutopilotToolHandler(
    private val jev: () -> JevClient?,
    private val enabled: () -> Boolean,
    /** Where successful runs are compiled to; null leaves compilation off. */
    private val flows: () -> FlowRepository? = { null },
    private val config: AutopilotConfig = AutopilotConfig(),
    /** For whether the app is installed and what it is called; null leaves both unchecked. */
    private val context: Context? = null,
) {

    suspend fun handle(params: JsonObject): SkillResult {
        if (!enabled()) {
            return SkillResult.Error(
                "agent_display_autopilot is turned off. Use agent_display_create and the other agent_display tools.")
        }
        val plan = AutopilotPlan.fromToolInput(params).getOrElse {
            return SkillResult.Error("Invalid autopilot plan: ${it.message}")
        }
        AgentDisplayCapabilities.ensureListener()
        if (!AgentDisplayLease.claimForCaller()) return SkillResult.Error(AgentDisplayLease.BUSY)
        val token = currentRunToken()
        if (token?.stopRequested == true) return SkillResult.Error(AgentDisplayLease.STOPPED)

        val run = coroutineContext[AutopilotRunContext]
        val planner = run?.let { LlmAutopilotPlanner(it.client, it.modelId, it.onModelCall) }
        val device = AppAutopilotDevice(
            context = context,
            stopCheck = token?.let { t -> { t.stopRequested } }
                ?: AgentDisplayCapabilities.stopGeneration.let { base -> { AgentDisplayCapabilities.stoppedSince(base) } },
        )
        // The name the user knows, and only for an app that is actually installed: the package
        // comes from the model, and nothing it made up should reach the rear screen.
        val appLabel = if (device.isLaunchable(plan.packageName)) device.appLabel(plan.packageName) else null
        val events = AutopilotEventSink { event ->
            org.ethereumphone.andyclaw.autopilot.replay.ReplayRecorder.onEvent(event)
            logEvent(event)
            updateHud(event, appLabel, token?.id)
            run?.events?.onEvent(event)
        }

        // The executor emits exactly one DONE or FAILED however it ends, a cancellation included.
        val result = AutopilotExecutor(
            device = device,
            jev = jev(),
            planner = planner,
            config = config,
            events = events,
        ).run(plan)

        Log.i(TAG, "run done status=${result.status} steps=${result.steps} ms=${result.durationMs} " +
            "jevCalls=${result.jevCalls} jevP50=${result.jevMsP50} planner=${result.plannerCalls} " +
            "esc=${result.escalations.joinToString(",")} reason=${result.reason}")
        AutopilotMetrics.record(result)
        var flowTool: String? = null
        try {
            if (result.reason != "sensitive_app") {
                // The finished screen closes the replay; a quick read on the IO pool.
                device.snapshot()
                device.captureFrame(result.steps + 1)
            }
            // What the plan asked to happen to the display is for a finished task. A hand-over
            // needs the screen as it is — the model carries on from it — and a stopped or failed
            // run leaves it to the end of the turn, which puts the display away.
            if (result.status == AutopilotResult.Status.SUCCESS) finishDisplay(plan.finish)
            flowTool = compileFlow(result)
        } catch (e: Exception) {
            // The run itself is over and reported; what follows it must not turn it into an error.
            rethrowIfCancelled(e)
            Log.w(TAG, "after-run step failed: ${e.message}", e)
        }
        val json = if (flowTool == null) result.toToolResultJson() else {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(result.toToolResultJson()) as JsonObject
            JsonObject(obj + ("flow" to JsonPrimitive(flowTool))).toString()
        }
        return SkillResult.Success(json)
    }

    /**
     * A clean run becomes a flow, so the same task next time replays without a model or Jev.
     * Only the user's own requests are compiled: a flow is replayed later with the user's
     * authority, and it must not be something a message or webhook taught the agent.
     */
    private suspend fun compileFlow(result: AutopilotResult): String? {
        if (result.status != AutopilotResult.Status.SUCCESS) return null
        val provenance = currentProvenance()
        if (provenance != Provenance.USER && provenance != Provenance.TRUSTED) return null
        val repository = flows() ?: return null
        val plan = result.plan ?: return null
        val range = repository.suggestedRangeFor(plan.packageName) ?: return null
        val id = AutopilotFlowCompiler.flowIdFor(plan.packageName, plan.goal, plan.values)
        return when (val compiled = AutopilotFlowCompiler.compile(result, id, range)) {
            is AutopilotFlowCompiler.Result.Skipped -> {
                Log.i(TAG, "not compiled into a flow: ${compiled.reason}")
                null
            }
            is AutopilotFlowCompiler.Result.Compiled -> when (val installed = repository.install(
                // A recompile after the app changed replaces the old flow; say so in the version.
                compiled.flow.copy(version = (repository.store.findByFlowId(id)?.flow?.version ?: 0) + 1),
            )) {
                is FlowInstallResult.Installed -> {
                    Log.i(TAG, "compiled flow ${compiled.flow.toolName} (${compiled.flow.steps.size} steps)")
                    compiled.flow.toolName
                }
                is FlowInstallResult.Rejected -> {
                    Log.i(TAG, "flow rejected: ${installed.errors.joinToString { it.code }}")
                    null
                }
                is FlowInstallResult.Failed -> {
                    Log.w(TAG, "flow install failed: ${installed.message}")
                    null
                }
            }
        }
    }

    private fun finishDisplay(finish: AutopilotPlan.Finish) {
        try {
            when (finish) {
                AutopilotPlan.Finish.KEEP -> Unit
                AutopilotPlan.Finish.DESTROY -> AgentDisplayBinder.serviceOrNull()?.destroyAgentDisplay()
                AutopilotPlan.Finish.PROMOTE -> AgentDisplayBinder.serviceOrNull()?.destroyAgentDisplayAndPromote()
            }
        } catch (e: Exception) {
            Log.w(TAG, "finish=$finish failed: ${e.message}")
        }
    }

    /**
     * The rear-screen HUD. Only numbers, a phase, an action verb and short labels go out; the OS
     * whitelists, filters and rate-limits them again before anything reaches the screen.
     */
    private fun updateHud(e: AutopilotEvent, appLabel: String?, runId: String?) {
        val phase = when (e.kind) {
            AutopilotEvent.Kind.STARTED -> "PLANNING"
            AutopilotEvent.Kind.ACTING, AutopilotEvent.Kind.SETTLED, AutopilotEvent.Kind.SUBGOAL_DONE -> "RUNNING"
            AutopilotEvent.Kind.ESCALATED -> "ESCALATING"
            AutopilotEvent.Kind.DONE -> "DONE"
            AutopilotEvent.Kind.FAILED -> when (e.outcome) {
                AutopilotOutcome.Outcome.STOPPED.wire, AutopilotOutcome.Outcome.CANCELLED.wire -> "STOPPED"
                // The model carries on driving: not an error, and the rear STOP must stay armed.
                // An OS older than v3 does not know HANDOFF and would drop the whole update.
                AutopilotOutcome.Outcome.HANDOFF.wire -> if (AgentDisplayCapabilities.hasV3) "HANDOFF" else "ESCALATING"
                else -> "ERROR"
            }
        }
        val hud = org.json.JSONObject()
            .put("phase", phase)
            .put("step", e.step)
            .put("elapsedMs", e.elapsedMs)
        appLabel?.let { hud.put("appLabel", it) }
        if (e.step > 0) {
            hud.put("msPerStep", e.elapsedMs / e.step)
            hud.put("stepsPerSec", e.step * 1000.0 / e.elapsedMs.coerceAtLeast(1))
        }
        e.action?.let { hud.put("action", AgentHud.verb(it)) }
        e.target?.name?.let { hud.put("target", it) }
        e.confidence?.takeIf { it.isFinite() }?.let { hud.put("confidence", it) }
        AgentHud.autopilot(runId, hud)
    }

    /** One line per step for `adb logcat -s AutopilotStep` — the raw material for AndyBench. */
    private fun logEvent(e: AutopilotEvent) {
        if (e.kind != AutopilotEvent.Kind.SETTLED && e.kind != AutopilotEvent.Kind.ESCALATED) return
        val t = e.timings
        Log.i(STEP_TAG, "run=${e.runId} step=${e.step} sub=${e.subgoalIndex + 1}/${e.subgoals.size} " +
            "kind=${e.kind} action=${e.action} target=${e.target?.name?.take(30)} conf=${e.confidence?.let { "%.2f".format(it) }} " +
            "src=${e.source} jev=${t?.jevMs} act=${t?.actMs} settle=${t?.settleMs} step=${t?.stepMs} " +
            "elapsed=${e.elapsedMs} reason=${e.reason}")
    }

    companion object {
        private const val TAG = "AutopilotTool"
        private const val STEP_TAG = "AutopilotStep"
    }
}

/** Process-wide counters, appended to the `AgentRunMetrics` summary line. */
object AutopilotMetrics {
    private val lock = Any()
    private var runs = 0
    private var steps = 0
    private var jevCalls = 0
    private var plannerCalls = 0
    private var escalations = 0
    private val jevP50s = ArrayList<Long>()

    fun record(result: AutopilotResult) = synchronized(lock) {
        runs++
        steps += result.steps
        jevCalls += result.jevCalls
        plannerCalls += result.plannerCalls
        escalations += result.escalations.size
        if (result.jevCalls > 0) jevP50s += result.jevMsP50
    }

    /** `apRuns=… apSteps=… jevCalls=… jevMsP50=… apPlanner=… apEsc=…`, then resets. */
    fun snapshotAndReset(): String = synchronized(lock) {
        val p50 = jevP50s.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
        val line = "apRuns=$runs apSteps=$steps jevCalls=$jevCalls jevMsP50=$p50 apPlanner=$plannerCalls apEsc=$escalations"
        runs = 0; steps = 0; jevCalls = 0; plannerCalls = 0; escalations = 0; jevP50s.clear()
        line
    }
}
