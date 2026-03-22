package org.ethereumphone.andyclaw.skills.customtools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition

class CustomToolAdapter(
    private val toolDef: CustomToolDefinition,
    private val executor: CustomToolExecutor,
) : AndyClawSkill {

    override val id = "custom:${toolDef.name}"
    override val name = "Custom: ${toolDef.name}"

    override val baseManifest = SkillManifest(
        description = toolDef.description,
        tools = listOf(
            ToolDefinition(
                name = toolDef.name,
                description = toolDef.description,
                inputSchema = toolDef.parameters,
                requiresApproval = true,
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return executor.execute(toolDef.code, params)
    }
}
