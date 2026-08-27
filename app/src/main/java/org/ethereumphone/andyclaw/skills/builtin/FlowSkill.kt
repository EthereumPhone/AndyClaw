package org.ethereumphone.andyclaw.skills.builtin

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.ethereumphone.andyclaw.flows.AgentDisplayFlowDriver
import org.ethereumphone.andyclaw.flows.Flow
import org.ethereumphone.andyclaw.flows.FlowCheckpointHandler
import org.ethereumphone.andyclaw.flows.FlowInterpreter
import org.ethereumphone.andyclaw.flows.FlowMetrics
import org.ethereumphone.andyclaw.flows.FlowRepository
import org.ethereumphone.andyclaw.flows.FlowRunResult
import org.ethereumphone.andyclaw.flows.FlowToolEffect
import org.ethereumphone.andyclaw.flows.StoredFlow
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition

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
    private var cachedManifest: SkillManifest? = null

    override val privilegedManifest: SkillManifest?
        get() {
            val flows = repository.flows()
            synchronized(this) {
                if (cachedFor !== flows) {
                    cachedManifest = if (flows.isEmpty()) null else SkillManifest(
                        description = "Replay a previously compiled UI flow: a recorded, " +
                            "deterministic sequence of accessibility actions. No screenshots, no " +
                            "model calls, and it aborts rather than guessing if the screen has " +
                            "changed.",
                        tools = flows.map { toolFor(it) },
                    )
                    cachedFor = flows
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

        FlowMetrics.onInvocation()
        val interpreter = FlowInterpreter(driver, checkpointHandler(tool))
        val result = try {
            interpreter.run(flow, arguments)
        } catch (e: Exception) {
            Log.w(TAG, "flow '${flow.flow}' threw", e)
            return SkillResult.Error("Flow '${flow.flow}' failed: ${e.message}")
        }
        FlowMetrics.onResult(result)

        val appVersion = driver.installedVersion(flow.app)
        repository.recordRun(stored.hash, result, appVersion)

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
                Log.w(TAG, "flow '${flow.flow}' aborted: ${result.reason} ${result.message}")
                SkillResult.Error(
                    "Flow '${flow.flow}' aborted at step ${result.stepIndex ?: "-"} " +
                        "(${result.reason}): ${result.message}. The screen is not the one this flow " +
                        "was compiled against, so nothing further was replayed. This flow is now " +
                        "marked stale — do the task on the agent display instead " +
                        "(agent_display_create), and it will be recompiled from what you do."
                )
            }
        }
    }

    override fun cleanup() {
        driver.release()
    }

    // ── Tool definitions ──────────────────────────────────────────────

    private fun toolFor(stored: StoredFlow): ToolDefinition {
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
            requiresApproval = FlowToolEffect.requiresApproval(flow),
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
     * Crossing a `checkpoint:` is allowed exactly when this tool call was gated by the
     * engine's approval check — which it was, because [toolFor] sets `requiresApproval`
     * for any flow that actuates anything, and the check runs before `execute` is
     * reached. The handler re-derives that rather than assuming it, so a flow that
     * somehow reached execution without the gate stops at its own boundary instead of
     * sending.
     */
    private fun checkpointHandler(toolName: String) = FlowCheckpointHandler { flow, name, _ ->
        val gated = FlowToolEffect.requiresApproval(flow)
        if (!gated) {
            Log.w(TAG, "checkpoint '$name' in '$toolName' refused — flow was not approval-gated")
        }
        gated
    }

    companion object {
        private const val TAG = "FlowSkill"
        const val SKILL_ID = "flows"
        /** `agent-os-design.md` §3's rung 3: compiled flow. */
        const val RUNG = 3
    }
}
