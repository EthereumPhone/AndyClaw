package org.ethereumphone.andyclaw.skills.builtin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.soul.SoulManager

class SoulSkill(
    private val soulManager: SoulManager,
) : AndyClawSkill {

    override val id = "soul"
    override val name = "Soul"

    override val baseManifest = SkillManifest(
        description = "Manage the AI's personality and identity. " +
            "The soul defines how you speak, behave, and present yourself across all conversations. " +
            "When the user asks you to change your personality, tone, communication style, or character traits, " +
            "use update_soul to persist the change. Read the current soul with read_soul before making modifications.",
        tools = listOf(
            ToolDefinition(
                name = "update_soul",
                description = "Update the AI's personality and identity. Write the full soul content — this replaces " +
                    "the entire soul definition. Use this when the user asks you to change how you behave, speak, " +
                    "or present yourself. The content should be written in markdown and describe personality traits, " +
                    "tone, communication style, values, and any other character attributes.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("content") {
                            put("type", "string")
                            put("description", "Full markdown content defining the AI's personality, tone, and character traits")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("content")) }
                },
            ),
            ToolDefinition(
                name = "read_soul",
                description = "Read the current soul/personality definition. Use this to check the current personality " +
                    "before making modifications with update_soul.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                },
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "update_soul" -> executeUpdateSoul(params)
            "read_soul" -> executeReadSoul()
            else -> SkillResult.Error("Unknown soul tool: $tool")
        }
    }

    private fun executeUpdateSoul(params: JsonObject): SkillResult {
        val content = params["content"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: content")

        if (content.isBlank()) {
            return SkillResult.Error("Soul content cannot be blank. To remove the soul, provide meaningful content or ask the user to confirm removal.")
        }

        soulManager.write(content)
        return SkillResult.Success(
            "Soul updated successfully. The new personality will take effect in the next conversation.\n\n" +
                "Current soul:\n$content"
        )
    }

    private fun executeReadSoul(): SkillResult {
        val content = soulManager.read()
        return if (content != null) {
            SkillResult.Success("Current soul:\n\n$content")
        } else {
            SkillResult.Success("No soul is currently set. The AI is using its default personality. " +
                "Use update_soul to define a custom personality.")
        }
    }
}
