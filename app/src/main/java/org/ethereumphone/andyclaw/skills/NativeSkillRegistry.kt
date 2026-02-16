package org.ethereumphone.andyclaw.skills

import kotlinx.serialization.json.JsonObject

class NativeSkillRegistry {

    private val skills = mutableListOf<AndyClawSkill>()

    fun register(skill: AndyClawSkill) {
        skills.removeAll { it.id == skill.id }
        skills.add(skill)
    }

    fun unregister(skillId: String) {
        skills.removeAll { it.id == skillId }
    }

    fun getAll(): List<AndyClawSkill> = skills.toList()

    fun getEnabled(enabledSkillIds: Set<String>): List<AndyClawSkill> =
        skills.filter { it.id in enabledSkillIds }

    fun getTools(tier: Tier): List<ToolDefinition> {
        val tools = mutableListOf<ToolDefinition>()
        for (skill in skills) {
            tools.addAll(skill.baseManifest.tools)
            if (tier == Tier.PRIVILEGED) {
                skill.privilegedManifest?.tools?.let { tools.addAll(it) }
            }
        }
        return tools
    }

    fun findSkillForTool(toolName: String, tier: Tier): AndyClawSkill? {
        for (skill in skills) {
            if (skill.baseManifest.tools.any { it.name == toolName }) return skill
            if (tier == Tier.PRIVILEGED) {
                if (skill.privilegedManifest?.tools?.any { it.name == toolName } == true) return skill
            }
        }
        return null
    }

    suspend fun executeTool(toolName: String, params: JsonObject, tier: Tier): SkillResult {
        val skill = findSkillForTool(toolName, tier)
            ?: return SkillResult.Error("No skill found for tool: $toolName")
        return skill.execute(toolName, params, tier)
    }
}
