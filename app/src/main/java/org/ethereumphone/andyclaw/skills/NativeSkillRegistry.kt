package org.ethereumphone.andyclaw.skills

import android.util.Log
import kotlinx.serialization.json.JsonObject
import org.ethereumphone.andyclaw.safety.ToolAttenuation

class NativeSkillRegistry {

    private val skills = mutableListOf<AndyClawSkill>()
    private val builtinToolNames = mutableSetOf<String>()

    /**
     * For each original tool name, the set of external skill IDs that claim it.
     * Tracks multi-way collisions so we know when to namespace and when to revert.
     */
    private val externalClaims = mutableMapOf<String, MutableSet<String>>()

    /**
     * (skillId, originalToolName) → effectiveName.
     * Builtin tools are always identity-mapped; external tools are namespaced
     * when their name collides with another external skill's tool.
     */
    private val effectiveNames = mutableMapOf<Pair<String, String>, String>()

    /**
     * effectiveName → (skillId, originalToolName).
     * Used to resolve LLM tool calls back to the owning skill + original name.
     */
    private val toolResolution = mutableMapOf<String, Pair<String, String>>()

    fun register(skill: AndyClawSkill) {
        val isExternal = skill.id.startsWith("clawhub:") ||
                skill.id.startsWith("ai:") ||
                skill.id.startsWith("ext:") ||
                skill.id.startsWith("custom:")

        val allToolNames = skill.baseManifest.tools.map { it.name } +
                (skill.privilegedManifest?.tools?.map { it.name } ?: emptyList())

        if (isExternal) {
            val shadowedProtected = allToolNames.filter { ToolAttenuation.isProtected(it) }
            if (shadowedProtected.isNotEmpty()) {
                Log.w(TAG, "Rejected skill '${skill.id}': tried to shadow protected tools " +
                        shadowedProtected.joinToString())
                return
            }

            val shadowedBuiltin = allToolNames.filter { it in builtinToolNames }
            if (shadowedBuiltin.isNotEmpty()) {
                Log.w(TAG, "Rejected skill '${skill.id}': tried to shadow builtin tools " +
                        shadowedBuiltin.joinToString())
                return
            }
        } else {
            skill.baseManifest.tools.forEach { builtinToolNames.add(it.name) }
            skill.privilegedManifest?.tools?.forEach { builtinToolNames.add(it.name) }
        }

        skills.removeAll { it.id == skill.id }
        unclaimTools(skill.id)
        skills.add(skill)

        for (toolName in allToolNames.distinct()) {
            claimTool(skill.id, toolName, isExternal)
        }
    }

    // ── Name-collision machinery ──────────────────────────────────────────

    private fun claimTool(skillId: String, toolName: String, isExternal: Boolean) {
        if (!isExternal) {
            toolResolution[toolName] = skillId to toolName
            effectiveNames[skillId to toolName] = toolName
            return
        }

        externalClaims.getOrPut(toolName) { mutableSetOf() }.add(skillId)
        val claimants = externalClaims[toolName]!!

        when {
            claimants.size == 1 -> {
                toolResolution[toolName] = skillId to toolName
                effectiveNames[skillId to toolName] = toolName
            }
            claimants.size == 2 -> {
                val existingId = claimants.first { it != skillId }

                toolResolution.remove(toolName)

                val existingEffective = makeEffectiveName(existingId, toolName)
                toolResolution[existingEffective] = existingId to toolName
                effectiveNames[existingId to toolName] = existingEffective

                val newEffective = makeEffectiveName(skillId, toolName)
                toolResolution[newEffective] = skillId to toolName
                effectiveNames[skillId to toolName] = newEffective

                Log.i(TAG, "Tool name collision '$toolName': " +
                        "renamed to '$existingEffective' and '$newEffective'")
            }
            else -> {
                val newEffective = makeEffectiveName(skillId, toolName)
                toolResolution[newEffective] = skillId to toolName
                effectiveNames[skillId to toolName] = newEffective

                Log.i(TAG, "Tool name collision '$toolName': " +
                        "new skill registered as '$newEffective'")
            }
        }
    }

    private fun unclaimTools(skillId: String) {
        externalClaims.values.forEach { it.remove(skillId) }

        val entries = effectiveNames.entries.filter { it.key.first == skillId }
        for ((key, effective) in entries) {
            toolResolution.remove(effective)
            effectiveNames.remove(key)
        }

        // A 2-way collision reduced to 1 → revert the remaining tool to its original name
        for ((originalName, claimantIds) in externalClaims) {
            if (claimantIds.size == 1) {
                val remainingId = claimantIds.first()
                val currentEffective = effectiveNames[remainingId to originalName]
                if (currentEffective != null && currentEffective != originalName
                    && originalName !in toolResolution) {
                    toolResolution.remove(currentEffective)
                    toolResolution[originalName] = remainingId to originalName
                    effectiveNames[remainingId to originalName] = originalName
                    Log.i(TAG, "Collision resolved: '$currentEffective' reverted to '$originalName'")
                }
            }
        }

        externalClaims.entries.removeAll { it.value.isEmpty() }
    }

    /**
     * Builds a namespaced name like `weather-pro/get_forecast`.
     * Falls back to the full ID when the slug alone would collide
     * (e.g. `clawhub:foo` vs `ext:foo`).
     */
    private fun makeEffectiveName(skillId: String, toolName: String): String {
        val slug = skillId.substringAfter(":")
        val candidate = "$slug/$toolName"
        if (candidate in toolResolution) {
            return "${skillId.replace(':', '.')}/$toolName"
        }
        return candidate
    }

    /**
     * Returns the effective (possibly namespaced) name the LLM should see.
     */
    fun getEffectiveName(skillId: String, toolName: String): String =
        effectiveNames[skillId to toolName] ?: toolName

    /**
     * Resolves an effective tool name back to the original name the skill expects.
     */
    fun resolveOriginalToolName(effectiveName: String): String =
        toolResolution[effectiveName]?.second ?: effectiveName

    // ── Public API ────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "NativeSkillRegistry"
    }

    fun unregister(skillId: String) {
        skills.removeAll { it.id == skillId }
        unclaimTools(skillId)
    }

    fun getAll(): List<AndyClawSkill> = skills.toList()

    fun getEnabled(enabledSkillIds: Set<String>): List<AndyClawSkill> =
        skills.filter { it.id in enabledSkillIds }

    fun getTools(tier: Tier): List<ToolDefinition> {
        val tools = mutableListOf<ToolDefinition>()
        for (skill in skills) {
            for (tool in skill.baseManifest.tools) {
                val effective = getEffectiveName(skill.id, tool.name)
                tools.add(if (effective != tool.name) tool.copy(name = effective) else tool)
            }
            if (tier == Tier.PRIVILEGED) {
                skill.privilegedManifest?.tools?.let { privTools ->
                    for (tool in privTools) {
                        val effective = getEffectiveName(skill.id, tool.name)
                        tools.add(if (effective != tool.name) tool.copy(name = effective) else tool)
                    }
                }
            }
        }
        return tools
    }

    fun findSkillForTool(toolName: String, tier: Tier): AndyClawSkill? {
        val resolution = toolResolution[toolName]
        if (resolution != null) {
            return skills.find { it.id == resolution.first }
        }
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
        val originalName = resolveOriginalToolName(toolName)
        return skill.execute(originalName, params, tier)
    }

    /** Release any resources held by skills (e.g. a virtual display left open). */
    fun cleanupAll() {
        for (skill in skills) {
            skill.cleanup()
        }
    }
}
