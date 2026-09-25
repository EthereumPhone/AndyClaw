package org.ethereumphone.andyclaw.skills

import kotlinx.serialization.json.JsonObject

interface AndyClawSkill {
    val id: String
    val name: String
    val baseManifest: SkillManifest
    val privilegedManifest: SkillManifest?

    suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult

    /**
     * Called after an agent loop run completes (normally, via error, or cancellation).
     * Skills that acquire resources (e.g. a virtual display) should release them here.
     * Default is a no-op.
     */
    fun cleanup() {}

    /**
     * Called as one agent run ends — normally, via error, or cancellation — with that run's id.
     * Runs share skill instances, so a skill that takes something for a run (the agent display,
     * a recording) gives back here only what [runId] took. Default is a no-op.
     */
    fun onRunFinished(runId: String, end: RunEnd) {}
}

/** How an agent run ended, as [AndyClawSkill.onRunFinished] hears it. */
enum class RunEnd {
    OK,
    FAILED,
    /** The user pressed STOP. */
    STOPPED,
}
