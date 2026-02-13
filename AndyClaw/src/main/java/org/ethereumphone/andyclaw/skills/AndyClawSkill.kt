package org.ethereumphone.andyclaw.skills

import kotlinx.serialization.json.JsonObject

interface AndyClawSkill {
    val id: String
    val name: String
    val baseManifest: SkillManifest
    val privilegedManifest: SkillManifest?

    suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult
}
