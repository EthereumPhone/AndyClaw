package org.ethereumphone.andyclaw.skills

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object PromptAssembler {

    fun assembleTools(skills: List<AndyClawSkill>, tier: Tier): List<JsonObject> {
        val tools = mutableListOf<JsonObject>()
        for (skill in skills) {
            val manifest = skill.baseManifest
            for (tool in manifest.tools) {
                tools.add(toolToJson(tool))
            }
            if (tier == Tier.PRIVILEGED) {
                skill.privilegedManifest?.tools?.forEach { tool ->
                    tools.add(toolToJson(tool))
                }
            }
        }
        return tools
    }

    fun assembleSystemPrompt(skills: List<AndyClawSkill>, tier: Tier): String {
        val sb = StringBuilder()
        sb.appendLine("You are AndyClaw, an AI assistant running on an Android device.")
        sb.appendLine("You have access to the following tool categories:")
        sb.appendLine()
        for (skill in skills) {
            sb.appendLine("## ${skill.name}")
            sb.appendLine(skill.baseManifest.description)
            if (tier == Tier.PRIVILEGED && skill.privilegedManifest != null) {
                sb.appendLine(skill.privilegedManifest!!.description)
            }
            sb.appendLine()
        }
        sb.appendLine("When you need to perform an action, use the appropriate tool.")
        sb.appendLine("Always explain what you're doing before using a tool.")
        sb.appendLine("If a tool requires approval, inform the user and wait for confirmation.")
        return sb.toString()
    }

    fun assembleToolsJsonArray(skills: List<AndyClawSkill>, tier: Tier): JsonArray {
        return JsonArray(assembleTools(skills, tier))
    }

    private fun toolToJson(tool: ToolDefinition): JsonObject {
        return buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("input_schema", tool.inputSchema)
        }
    }
}
