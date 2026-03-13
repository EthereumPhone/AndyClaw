package org.ethereumphone.andyclaw.skills.builtin

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.ethereumphone.andyclaw.led.Emoticons
import org.ethereumphone.andyclaw.led.LedMatrixController
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition

/**
 * Built-in skill that gives the AI agent control over the dGEN1 terminal status bar display.
 *
 * The terminal is a 428×142 pixel back-screen designed for emoticons, status text,
 * and short visual feedback. The agent should use this reactively — showing emoticons
 * that match the conversational mood (e.g. cheer on success, cry on failure).
 */
class TerminalTextSkill(
    private val controller: LedMatrixController,
) : AndyClawSkill {

    companion object {
        private const val TAG = "TerminalTextSkill"
    }

    override val id = "terminal_text"
    override val name = "Terminal Display"

    override val baseManifest = SkillManifest(
        description = "Control the dGEN1 terminal status bar (428×142 px back-screen) to display emoticons or short text. Only available on dGEN1 running ethOS.",
        tools = listOf(
            ToolDefinition(
                name = "setTerminalText",
                description = buildString {
                    append("Display text or emoticon on the dGEN1 terminal. ")
                    append("MUST use on first response; use mood-appropriate emoticons thereafter. ")
                    append("Never announce setting the terminal text.\n\n")
                    append("Available emoticons:\n")
                    append(Emoticons.AVAILABLE_EMOTICONS)
                },
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("text") {
                            put("type", "string")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("text")) }
                },
            ),

            ToolDefinition(
                name = "clearTerminalText",
                description = "Clear the terminal display and restore the default status bar.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                },
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        if (!controller.isDisplayAvailable) {
            return SkillResult.Error(
                "Terminal display is not available. This feature requires a dGEN1 device running ethOS."
            )
        }
        Log.d(TAG, "Executing tool: $tool")
        return when (tool) {
            "setTerminalText" -> executeSetText(params)
            "clearTerminalText" -> executeClear()
            else -> SkillResult.Error("Unknown terminal text tool: $tool")
        }
    }

    private suspend fun executeSetText(params: JsonObject): SkillResult {
        val text = params["text"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: text")

        return if (controller.setTerminalText(text)) {
            SkillResult.Success("Terminal display updated.")
        } else {
            SkillResult.Error("Failed to update terminal display.")
        }
    }

    private suspend fun executeClear(): SkillResult {
        return if (controller.clearTerminalText()) {
            SkillResult.Success("Terminal display cleared.")
        } else {
            SkillResult.Error("Failed to clear terminal display.")
        }
    }
}
