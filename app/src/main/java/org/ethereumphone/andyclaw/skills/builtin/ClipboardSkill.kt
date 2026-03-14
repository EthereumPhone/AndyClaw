package org.ethereumphone.andyclaw.skills.builtin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition

class ClipboardSkill(private val context: Context) : AndyClawSkill {
    override val id = "clipboard"
    override val name = "Clipboard"

    override val baseManifest = SkillManifest(
        description = "Read from and write to the system clipboard.",
        tools = listOf(
            ToolDefinition(
                name = "read_clipboard",
                description = "Read the current contents of the system clipboard.",
                inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
            ),
            ToolDefinition(
                name = "write_clipboard",
                description = "Write text to the system clipboard.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "text" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The text to copy to clipboard"),
                        ))
                    )),
                    "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("text"))),
                )),
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "read_clipboard" -> readClipboard()
            "write_clipboard" -> writeClipboard(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun readClipboard(): SkillResult {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip == null || clip.itemCount == 0) {
                SkillResult.Success(buildJsonObject { put("content", ""); put("has_content", false) }.toString())
            } else {
                val text = clip.getItemAt(0).coerceToText(context).toString()
                SkillResult.Success(buildJsonObject { put("content", text); put("has_content", true) }.toString())
            }
        } catch (e: Exception) {
            SkillResult.Error("Failed to read clipboard: ${e.message}")
        }
    }

    private fun writeClipboard(params: JsonObject): SkillResult {
        val text = params["text"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: text")
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("AndyClaw", text)
            clipboard.setPrimaryClip(clip)
            SkillResult.Success(buildJsonObject { put("success", true) }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to write to clipboard: ${e.message}")
        }
    }
}
