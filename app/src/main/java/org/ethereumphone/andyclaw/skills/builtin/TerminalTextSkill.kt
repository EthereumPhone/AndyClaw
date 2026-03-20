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
        description = buildString {
            append("Control the dGEN1 terminal status bar (428×142 px back-screen). ")
            append("Display emoticons, symbols, or short text. ")
            append("Use this to show mood-appropriate emoticons as reactions — ")
            append("e.g. cheer ※\\(^o^)/※ when something works, cry (╥﹏╥) when it doesn't, ")
            append("dance ᕕ(⌐■_■)ᕗ ♪♬ for fun moments, shrug ¯\\_(ツ)_/¯ for uncertainty. ")
            append("Only available on dGEN1 hardware running ethOS.")
        },
        tools = listOf(
            ToolDefinition(
                name = "setTerminalText",
                description = buildString {
                    append("Display an ASCII emoticon on the dGEN1 terminal status bar. ")
                    append("Pass a keyword and it will be auto-resolved to the matching ASCII emoticon. ")
                    append("Use this to show mood, emotion, or action-appropriate ASCII art.\n\n")
                    append("You MUST use this in your first response to any conversation. ")
                    append("After the first response, use as appropriate to the mood. ")
                    append("Never announce when you set the terminal text.\n\n")
                    append("Choose keywords that match the context of your response:\n")
                    append("- Emotions: happy, sad, angry, love, excited, confused, cool, surprised, shy, nervous, chill, proud\n")
                    append("- Actions: searching, internet, sending, thinking, coding, music, dance, magic, greeting, celebrate\n")
                    append("- Reactions: success, done, fail, error, oops, wow, lol, shrug, facepalm, nice, bruh, oof, great\n")
                    append("- Vibes: zen, lazy, sleep, gaming, flexing, evil, nerd, zombie, party\n\n")
                    append("Full emoticon list (keyword → emoticon):\n")
                    append(Emoticons.AVAILABLE_EMOTICONS)
                },
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("text") {
                            put("type", "string")
                            put(
                                "description",
                                "The emoticon, symbol, or text to display. " +
                                "Short emoticons render best on the 428×142 px screen."
                            )
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
