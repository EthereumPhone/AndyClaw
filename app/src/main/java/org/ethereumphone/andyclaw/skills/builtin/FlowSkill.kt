package org.ethereumphone.andyclaw.skills.builtin

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.ethereumphone.andyclaw.flows.AgentDisplayFlowDriver
import org.ethereumphone.andyclaw.flows.Flow
import org.ethereumphone.andyclaw.flows.FlowAbortReason
import org.ethereumphone.andyclaw.flows.FlowCheckpointHandler
import org.ethereumphone.andyclaw.flows.FlowCheckpointPolicy
import org.ethereumphone.andyclaw.flows.FlowFirst
import org.ethereumphone.andyclaw.flows.FlowInterpreter
import org.ethereumphone.andyclaw.flows.FlowMetrics
import org.ethereumphone.andyclaw.flows.FlowRepository
import org.ethereumphone.andyclaw.flows.FlowRunAccounting
import org.ethereumphone.andyclaw.flows.FlowRunResult
import org.ethereumphone.andyclaw.flows.FlowStopSignal
import org.ethereumphone.andyclaw.flows.FlowToolEffect
import org.ethereumphone.andyclaw.flows.StoredFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import org.ethereumphone.andyclaw.ExecutionEngine.currentProvenance
import org.ethereumphone.andyclaw.ExecutionEngine.currentUserApproval
import org.ethereumphone.andyclaw.agent.currentRunToken
import org.ethereumphone.andyclaw.autopilot.AgentDisplayCapabilities
import org.ethereumphone.andyclaw.autopilot.AutopilotFlowCompiler
import org.ethereumphone.andyclaw.autopilot.AutopilotPlan
import org.ethereumphone.andyclaw.autopilot.AutopilotToolHandler
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.ExecutionEngine.rethrowIfCancelled

/**
 * Rung 3, as tools the model can actually pick.
 *
 * Every compiled flow shows up here as its own named tool — `signal_send_to_existing_thread`,
 * not `agent_display_create("org.thoughtcrime.securesms")`. That naming *is* the routing:
 * BM25 tool search ranks a specific name far above a generic one, so the model reaches for
 * the flow without being told to. `routeGateCheck` is the backstop for when it doesn't.
 *
 * The manifest is a getter, not a val, because the flow set changes at runtime — a
 * discovery session compiles one, an app upgrade retires another — and
 * `NativeSkillRegistry.register` is re-run on every change so the tool list and the search
 * index follow.
 */
class FlowSkill(
    private val repository: FlowRepository,
    private val driver: AgentDisplayFlowDriver,
    /** Re-drives a flow whose screens changed, from the intent it was compiled with. */
    private val autopilot: () -> AutopilotToolHandler? = { null },
    /**
     * The user's "no confirmation" setting: irreversible flows run without an approval card.
     * Untrusted triggers are still stopped by the provenance gate before a flow is reached.
     */
    private val noConfirm: () -> Boolean = { false },
) : AndyClawSkill {

    override val id = SKILL_ID
    override val name = "Compiled Flows"

    /** Flows drive the agent display, which exists on ethOS only. */
    override val baseManifest = SkillManifest(
        description = "Replay compiled UI flows. Privileged-only.",
        tools = emptyList(),
    )

    /**
     * Memoized against the published list, which [FlowRepository] replaces wholesale on
     * every change. `getTools()` runs once per tool call in the hot loop, and rebuilding
     * a schema per flow per call would put JSON construction on the latency path this
     * phase exists to shorten.
     */
    private var cachedFor: List<StoredFlow>? = null
    private var cachedNoConfirm: Boolean? = null
    private var cachedManifest: SkillManifest? = null

    /**
     * Keyed on the no-confirm setting as well: whether a flow's tool needs the approval card
     * depends on it, and a manifest memoized from before the user turned confirmations back on
     * kept offering the flows as needing none.
     */
    override val privilegedManifest: SkillManifest?
        get() {
            val flows = repository.flows()
            val confirmOff = noConfirm()
            synchronized(this) {
                if (cachedFor !== flows || cachedNoConfirm != confirmOff) {
                    cachedManifest = if (flows.isEmpty()) null else SkillManifest(
                        description = "Replay a previously compiled UI flow: a recorded, " +
                            "deterministic sequence of accessibility actions. No screenshots, no " +
                            "model calls, and it aborts rather than guessing if the screen has " +
                            "changed.",
                        tools = flows.map { toolFor(it, confirmOff) },
                    )
                    cachedFor = flows
                    cachedNoConfirm = confirmOff
                }
                return cachedManifest
            }
        }

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        val stored = repository.byToolName(tool)
            ?: return SkillResult.Error("No compiled flow named '$tool'.")
        val flow = stored.flow

        val arguments = flow.params.associateWith { name ->
            params[name]?.jsonPrimitive?.contentOrNull.orEmpty()
        }
        // A value the model left out is the model's slip, not the flow's: say so before anything
        // runs. It used to reach the interpreter, count against the flow, and send the autopilot
        // off to do the task with blanks.
        val missing = flow.params.filter { arguments[it].isNullOrEmpty() }
        if (missing.isNotEmpty()) {
            return SkillResult.Error("Flow '${flow.flow}' needs ${missing.joinToString()}. Call it again with a value for each.")
        }

        // A replay drives the same display as everything else: one run at a time.
        if (!AgentDisplayLease.claimForCaller()) return SkillResult.Error(AgentDisplayLease.BUSY)
        if (currentRunToken()?.stopRequested == true) return SkillResult.Error(AgentDisplayLease.STOPPED)

        val result = try {
            replayOnce(stored, arguments)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            Log.w(TAG, "flow '${flow.flow}' threw", e)
            return SkillResult.Error("Flow '${flow.flow}' failed: ${e.message}")
        }

        return when (result) {
            is FlowRunResult.Completed -> {
                Log.i(TAG, "flow '${flow.flow}' completed in ${result.durationMs}ms, " +
                    "${result.stepsRun} steps, 0 model calls")
                SkillResult.Success(
                    "Flow '${flow.flow}' completed: ${result.stepsRun} steps in ${result.durationMs}ms, " +
                        "no screenshots and no model calls.\n${result.trace.joinToString(" -> ")}"
                )
            }

            is FlowRunResult.Aborted -> {
                Log.w(TAG, "flow '${flow.flow}' aborted: ${result.reason} ${result.message} (committed=${result.committed})")
                if (FlowRunAccounting.mayFallBack(result)) autopilotFallback(flow, arguments)?.let { return it }
                SkillResult.Error(abortMessage(flow, result))
            }
        }
    }

    /**
     * One replay of [stored] — the interpreter, the metrics, the flow's record — and nothing
     * more. What an abort means for the task is the caller's decision.
     */
    private suspend fun replayOnce(stored: StoredFlow, arguments: Map<String, String>): FlowRunResult {
        val flow = stored.flow
        FlowMetrics.onInvocation()
        val interpreter = FlowInterpreter(driver, checkpointHandler(flow.toolName), stop = stopSignal())
        val result = interpreter.run(flow, arguments)
        FlowMetrics.onResult(result)
        repository.recordRun(stored.hash, result, driver.installedVersion(flow.app))
        return result
    }

    /**
     * The autopilot's first move, for a task a compiled flow already does: replay it, with no
     * Jev and no planner, and end the turn on its `say`. Null means "drive the app instead" —
     * no flow fits, or the replay stopped before doing anything that mattered because the
     * screens have moved on (the autopilot then recompiles it).
     */
    suspend fun flowFirst(plan: AutopilotPlan): SkillResult? {
        val flowId = AutopilotFlowCompiler.flowIdFor(plan.packageName, plan.goal, plan.values)
        val stored = FlowFirst.select(flowId, plan.values, repository.flows(), noConfirm(), currentProvenance())
            ?: return null
        val arguments = stored.flow.params.associateWith { plan.values[it].orEmpty() }
        Log.i(TAG, "autopilot: replaying the compiled flow '${stored.flow.flow}' first")
        val result = try {
            replayOnce(stored, arguments)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            Log.w(TAG, "flow-first replay of '${stored.flow.flow}' threw; driving the app instead", e)
            return null
        }
        return when (result) {
            is FlowRunResult.Completed -> SkillResult.Success(
                buildJsonObject {
                    put("status", "success")
                    put("outcome", "success")
                    put("steps", result.stepsRun)
                    put("ms", result.durationMs)
                    plan.say?.let { put("say", it) }
                    put("trace", result.trace.takeLast(12).joinToString(" → "))
                    put("flow", stored.flow.toolName)
                }.toString()
            )
            is FlowRunResult.Aborted ->
                if (FlowRunAccounting.mayFallBack(result)) null else SkillResult.Error(abortMessage(stored.flow, result))
        }
    }

    /** STOP for this replay: the run's own, or for a caller outside a run, any STOP from now on. */
    private suspend fun stopSignal(): FlowStopSignal {
        val token = currentRunToken()
        if (token != null) return FlowStopSignal { token.stopRequested }
        val baseline = AgentDisplayCapabilities.stopGeneration
        return FlowStopSignal { AgentDisplayCapabilities.stoppedSince(baseline) }
    }

    /** What the model is told when a replay did not complete — never an invitation to repeat a send. */
    private fun abortMessage(flow: Flow, result: FlowRunResult.Aborted): String = when {
        result.committed ->
            "Flow '${flow.flow}' performed its final action, but the result could not be confirmed on " +
                "screen (${result.message}). It has most likely already happened. Do NOT repeat it, and do " +
                "not do the task another way: tell the user it was done but could not be confirmed, so they can check."
        result.reason == FlowAbortReason.STOPPED -> "Stopped by the user."
        result.reason == FlowAbortReason.CHECKPOINT_REFUSED ->
            "Flow '${flow.flow}' stopped at its confirmation step (${result.message}). Nothing irreversible was done."
        result.reason == FlowAbortReason.SENSITIVE_TARGET ->
            "Flow '${flow.flow}' stopped: ${result.message}. That step involves payment, a password, a sign-in " +
                "or a private app, which is never automated. Ask the user to do it themselves."
        result.reason == FlowAbortReason.APP_NOT_INSTALLED || result.reason == FlowAbortReason.DISPLAY_UNAVAILABLE ->
            "Flow '${flow.flow}' could not run: ${result.message}."
        else ->
            "Flow '${flow.flow}' aborted at step ${result.stepIndex ?: "-"} " +
                "(${result.reason}): ${result.message}. The screen is not the one this flow " +
                "was compiled against, so nothing further was replayed. This flow is now " +
                "marked stale — do the task on the agent display instead " +
                "(agent_display_create), and it will be recompiled from what you do."
    }

    // ── Tool definitions ──────────────────────────────────────────────

    private fun toolFor(stored: StoredFlow, confirmOff: Boolean): ToolDefinition {
        val flow = stored.flow
        val effect = FlowToolEffect.of(flow)
        return ToolDefinition(
            name = flow.toolName,
            description = describe(stored),
            inputSchema = schemaFor(flow),
            // Irreversible flows are confirmed once, before the replay starts, rather
            // than by the interpreter mid-run — one card for the whole action, which is
            // what `agent-os-design.md` §6 asks for and what keeps the warm path free of
            // a second round trip.
            requiresApproval = FlowToolEffect.requiresApproval(flow) && !confirmOff,
            searchHint = searchHintFor(flow),
            effect = effect,
            rung = RUNG,
            // A stale flow keeps its tool so it can revalidate on next use, but stops
            // standing in front of the display: a route nobody is sure about must not
            // block the fallback.
            targetPackages = if (stored.meta.stale) emptyList() else listOf(flow.app),
        )
    }

    private fun describe(stored: StoredFlow): String {
        val flow = stored.flow
        return buildString {
            append("Replay the compiled '${flow.flow}' flow (v${flow.version}) in ${flow.app}. ")
            append("${flow.steps.size} recorded accessibility steps, replayed with no screenshots ")
            append("and no model calls. ")
            if (flow.params.isNotEmpty()) {
                append("Parameters: ${flow.params.joinToString()}. ")
            }
            if (stored.meta.stale) {
                append("Marked stale after an app update — running it revalidates it, and it will ")
                append("abort cleanly if the UI has moved. ")
            }
            append("Prefer this over driving ${flow.app} on the agent display.")
        }
    }

    private fun searchHintFor(flow: Flow): String {
        val words = flow.flow.split('.', '_').filter { it.isNotBlank() }
        return (words + flow.app.substringAfterLast('.') + listOf("compiled flow", "replay"))
            .joinToString(" ")
    }

    private fun schemaFor(flow: Flow): JsonObject {
        val properties = flow.params.associateWith { name ->
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("string"),
                    "description" to JsonPrimitive("Value for {{$name}}"),
                )
            ) as kotlinx.serialization.json.JsonElement
        }
        return JsonObject(
            buildMap {
                put("type", JsonPrimitive("object"))
                put("properties", JsonObject(properties))
                if (flow.params.isNotEmpty()) {
                    put("required", JsonArray(flow.params.map { JsonPrimitive(it) }))
                }
            }
        )
    }

    // ── Checkpoints ───────────────────────────────────────────────────

    /**
     * Crossing a `checkpoint:` takes proof, not an assumption: the engine's witness that the
     * user approved this very call, or the user's no-confirm for a request that is theirs.
     * It used to assume the card had run whenever the tool was marked as needing one — and the
     * tool list is memoized, so after confirmations were turned back on a replay crossed its
     * checkpoint with no card at all. See [FlowCheckpointPolicy].
     */
    private fun checkpointHandler(toolName: String) = FlowCheckpointHandler { _, name, _ ->
        val approved = currentUserApproval()?.toolName == toolName
        val provenance = currentProvenance()
        val confirmOff = noConfirm()
        val ok = FlowCheckpointPolicy.mayCross(approved, confirmOff, provenance)
        if (!ok) Log.w(TAG, "checkpoint '$name' in '$toolName' refused (approved=$approved, noConfirm=$confirmOff, provenance=$provenance)")
        ok
    }

    /**
     * The screens no longer match what the flow recorded. If the flow knows what it was for,
     * let the autopilot do the task again — it recompiles the flow when it succeeds — instead
     * of handing the whole task back to the model.
     */
    private suspend fun autopilotFallback(flow: Flow, arguments: Map<String, String>): SkillResult? {
        val intent = flow.intent ?: return null
        val handler = autopilot() ?: return null
        fun fill(t: String) = arguments.entries.fold(t) { acc, (k, v) -> acc.replace("{{$k}}", v) }
        val params = buildJsonObject {
            put("package_name", flow.app)
            put("goal", fill(intent.goal))
            putJsonArray("steps") {
                intent.steps.forEach { step ->
                    addJsonObject {
                        put("do", fill(step.doText))
                        step.doneWhen?.let { put("done_when", fill(it)) }
                        step.type.firstOrNull()?.let { put("type", it) }
                    }
                }
            }
            putJsonObject("values") { arguments.forEach { (k, v) -> put(k, v) } }
        }
        Log.i(TAG, "flow '${flow.flow}' falling back to the autopilot")
        // flowLookup = false: the autopilot must not try this same flow first and come back here.
        return handler.handle(params, flowLookup = false)
    }

    companion object {
        private const val TAG = "FlowSkill"
        const val SKILL_ID = "flows"
        /** `agent-os-design.md` §3's rung 3: compiled flow. */
        const val RUNG = 3
    }
}
