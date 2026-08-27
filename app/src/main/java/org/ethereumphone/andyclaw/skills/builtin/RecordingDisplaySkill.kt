package org.ethereumphone.andyclaw.skills.builtin

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.ethereumphone.andyclaw.flows.FlowRecorder
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier

/**
 * Watches a display session so it can become a flow, without changing what the session
 * does.
 *
 * This is a decorator rather than an edit to [AgentDisplaySkill] for one reason: there
 * must be exactly one code path that drives the device. If recording were reimplemented
 * alongside the injection calls it would drift from them, and a flow that does not match
 * what actually ran is worse than no flow.
 *
 * Recording starts when the display is created and stops when it is destroyed, so it
 * costs nothing outside a display session. Inside one, it adds a single accessibility
 * tree read per action — the same call the skill has just made, against a session whose
 * per-step cost is a model round trip measured in seconds.
 */
class RecordingDisplaySkill(
    private val inner: AgentDisplaySkill,
    val recorder: FlowRecorder,
) : AndyClawSkill {

    override val id: String get() = inner.id
    override val name: String get() = inner.name
    override val baseManifest: SkillManifest get() = inner.baseManifest
    override val privilegedManifest: SkillManifest? get() = inner.privilegedManifest

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        if (tool in START_TOOLS && !recorder.isRecording) {
            recorder.start()
            Log.i(TAG, "recording started for $tool")
        }

        val treeBefore = if (recorder.isRecording) rawTree() else null
        val result = inner.execute(tool, params, tier)

        if (recorder.isRecording) {
            val treeAfter = rawTree()
            recorder.record(
                tool = tool,
                viewId = params["view_id"]?.jsonPrimitive?.contentOrNull,
                text = params["text"]?.jsonPrimitive?.contentOrNull,
                x = params["x"]?.jsonPrimitive?.doubleOrNull,
                y = params["y"]?.jsonPrimitive?.doubleOrNull,
                packageName = params["package_name"]?.jsonPrimitive?.contentOrNull,
                treeBefore = treeBefore,
                treeAfter = treeAfter,
                ok = result !is SkillResult.Error,
            )
        }

        if (tool in STOP_TOOLS) recorder.stop()
        return result
    }

    override fun cleanup() {
        recorder.stop()
        inner.cleanup()
    }

    private fun rawTree(): String? = try {
        AgentDisplayBinder.serviceOrNull()?.accessibilityTree
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "RecordingDisplaySkill"

        private val START_TOOLS = setOf("agent_display_create")

        private val STOP_TOOLS = setOf(
            "agent_display_destroy",
            "agent_display_destroy_and_promote",
        )
    }
}
