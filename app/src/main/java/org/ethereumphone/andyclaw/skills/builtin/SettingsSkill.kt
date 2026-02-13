package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import android.provider.Settings
import kotlinx.serialization.json.JsonArray
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

class SettingsSkill(private val context: Context) : AndyClawSkill {
    override val id = "settings"
    override val name = "System Settings"

    override val baseManifest = SkillManifest(
        description = "Read system settings from the device.",
        tools = listOf(
            ToolDefinition(
                name = "get_system_setting",
                description = "Get a system setting value by name (e.g., screen_brightness, volume_music).",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "name" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Setting name"))),
                        "namespace" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Namespace: system, secure, or global (default: system)"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("name"))),
                )),
            ),
            ToolDefinition(
                name = "list_settings",
                description = "List available settings in a namespace.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "namespace" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Namespace: system, secure, or global (default: system)"))),
                    )),
                )),
            ),
        ),
    )

    override val privilegedManifest = SkillManifest(
        description = "Write system and secure settings (privileged OS only).",
        tools = listOf(
            ToolDefinition(
                name = "write_system_setting",
                description = "Write a system setting value (privileged OS only).",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "name" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Setting name"))),
                        "value" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Setting value"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("name"), JsonPrimitive("value"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "write_secure_setting",
                description = "Write a secure setting value (privileged OS only).",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "name" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Setting name"))),
                        "value" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Setting value"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("name"), JsonPrimitive("value"))),
                )),
                requiresApproval = true,
            ),
        ),
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "get_system_setting" -> getSetting(params)
            "list_settings" -> listSettings(params)
            "write_system_setting" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("write_system_setting requires privileged OS")
                else writeSetting(params, "system")
            }
            "write_secure_setting" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("write_secure_setting requires privileged OS")
                else writeSetting(params, "secure")
            }
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun getSetting(params: JsonObject): SkillResult {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: name")
        val namespace = params["namespace"]?.jsonPrimitive?.contentOrNull ?: "system"
        return try {
            val value = when (namespace) {
                "system" -> Settings.System.getString(context.contentResolver, name)
                "secure" -> Settings.Secure.getString(context.contentResolver, name)
                "global" -> Settings.Global.getString(context.contentResolver, name)
                else -> return SkillResult.Error("Invalid namespace: $namespace. Use system, secure, or global.")
            }
            SkillResult.Success(buildJsonObject {
                put("name", name)
                put("namespace", namespace)
                put("value", value ?: "null")
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to get setting: ${e.message}")
        }
    }

    private fun listSettings(params: JsonObject): SkillResult {
        val namespace = params["namespace"]?.jsonPrimitive?.contentOrNull ?: "system"
        return try {
            val uri = when (namespace) {
                "system" -> Settings.System.CONTENT_URI
                "secure" -> Settings.Secure.CONTENT_URI
                "global" -> Settings.Global.CONTENT_URI
                else -> return SkillResult.Error("Invalid namespace: $namespace")
            }
            val settings = mutableListOf<JsonObject>()
            val cursor = context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)
            cursor?.use {
                while (it.moveToNext() && settings.size < 100) {
                    settings.add(buildJsonObject {
                        put("name", it.getString(0) ?: "")
                        put("value", it.getString(1) ?: "")
                    })
                }
            }
            SkillResult.Success(buildJsonObject {
                put("namespace", namespace)
                put("count", settings.size)
                put("settings", JsonArray(settings))
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to list settings: ${e.message}")
        }
    }

    private fun writeSetting(params: JsonObject, namespace: String): SkillResult {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: name")
        val value = params["value"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: value")
        return try {
            when (namespace) {
                "system" -> Settings.System.putString(context.contentResolver, name, value)
                "secure" -> Settings.Secure.putString(context.contentResolver, name, value)
                else -> return SkillResult.Error("Invalid namespace for write: $namespace")
            }
            SkillResult.Success(buildJsonObject {
                put("success", true)
                put("name", name)
                put("value", value)
                put("namespace", namespace)
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to write setting: ${e.message}")
        }
    }
}
