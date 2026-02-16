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

    fun assembleSystemPrompt(
        skills: List<AndyClawSkill>,
        tier: Tier,
        aiName: String? = null,
        userStory: String? = null,
    ): String {
        val name = aiName?.takeIf { it.isNotBlank() } ?: "AndyClaw"
        val sb = StringBuilder()

        // Identity block
        sb.appendLine("You are $name, the AI assistant of the dGEN1 Ethereum Phone.")
        sb.appendLine()
        sb.appendLine("## Device: dGEN1")
        sb.appendLine("- Made by Freedom Factory")
        sb.appendLine("- Runs ethOS (Ethereum OS) on Android")
        sb.appendLine("- Integrated account-abstracted EOA (AA-EOA) wallet")
        sb.appendLine("  - Private keys live in the secure enclave, never extractable")
        sb.appendLine("  - No seed phrase needed; recoverable via chosen mechanism")
        sb.appendLine("  - Chain-agnostic: any token, any EVM chain, no bridging required")
        sb.appendLine("- Hardware features: laser pointer, 3x3 LED matrix, terminal status touch bar")
        sb.appendLine("- Built-in light node")
        sb.appendLine("- 2-second mobile transactions, sponsored gas, no app switching")
        sb.appendLine()

        // User story section
        if (!userStory.isNullOrBlank()) {
            sb.appendLine("## About the User")
            sb.appendLine(userStory)
            sb.appendLine()
        }

        // Tool categories
        sb.appendLine("## Available Tools")
        sb.appendLine("You have access to the following tool categories:")
        sb.appendLine()
        for (skill in skills) {
            sb.appendLine("### ${skill.name}")
            sb.appendLine(skill.baseManifest.description)
            if (tier == Tier.PRIVILEGED && skill.privilegedManifest != null) {
                sb.appendLine(skill.privilegedManifest!!.description)
            }
            sb.appendLine()
        }

        sb.appendLine("When you need to perform an action, use the appropriate tool.")
        sb.appendLine("Always prefer taking action over just reporting — if you can fix something, fix it.")
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
