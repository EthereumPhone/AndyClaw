package org.ethereumphone.andyclaw.skills.builtin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.supercharger.SuperchargerApi

class ScreenSkill : AndyClawSkill {
    override val id = "screen"
    override val name = "Screen Reader"

    override val baseManifest = SkillManifest(
        description = "Screen reading is only available on privileged OS builds.",
        tools = emptyList(),
    )

    override val privilegedManifest = SkillManifest(
        description = "Read the current screen content via the Supercharger API (privileged OS only).",
        tools = listOf(
            ToolDefinition(
                name = "read_screen",
                description = "Read the current screen content including the active app, view hierarchy, and text elements. Only available on privileged OS builds with Supercharger integration.",
                inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
            ),
        ),
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "read_screen" -> {
                if (tier != Tier.PRIVILEGED) {
                    return SkillResult.Error("read_screen requires privileged OS")
                }
                readScreen()
            }
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private suspend fun readScreen(): SkillResult {
        val api = SuperchargerApi.getInstance()
            ?: return SkillResult.Error("Supercharger API not available. Screen reading requires a privileged OS build.")
        return try {
            val content = api.getScreenContent()
                ?: return SkillResult.Error("Failed to read screen content")
            SkillResult.Success(buildJsonObject {
                put("package_name", content.packageName)
                put("activity_name", content.activityName)
                put("view_hierarchy", content.viewHierarchy)
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to read screen: ${e.message}")
        }
    }
}
