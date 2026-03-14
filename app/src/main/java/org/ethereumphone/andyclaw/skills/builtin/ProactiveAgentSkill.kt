package org.ethereumphone.andyclaw.skills.builtin

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

class ProactiveAgentSkill : AndyClawSkill {
    override val id = "proactive_agent"
    override val name = "Proactive Agent"

    private val triggers = mutableListOf<Trigger>()

    data class Trigger(val id: String, val event: String, val action: String)

    override val baseManifest = SkillManifest(
        description = "Proactive triggers are only available on privileged OS builds.",
        tools = emptyList(),
    )

    override val privilegedManifest = SkillManifest(
        description = "Register, list, and remove proactive triggers that fire on device events (privileged OS only).",
        tools = listOf(
            ToolDefinition(
                name = "register_trigger",
                description = "Register a proactive trigger that fires when a specified event occurs (stub).",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "event" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Event type (e.g., time, location, app_opened, notification)"))),
                        "action" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Action to take when triggered"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("event"), JsonPrimitive("action"))),
                )),
            ),
            ToolDefinition(
                name = "list_triggers",
                description = "List all registered proactive triggers.",
                inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
            ),
            ToolDefinition(
                name = "remove_trigger",
                description = "Remove a proactive trigger by ID.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "trigger_id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("ID of the trigger to remove"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("trigger_id"))),
                )),
            ),
        ),
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        if (tier != Tier.PRIVILEGED) {
            return SkillResult.Error("$tool requires privileged OS")
        }
        return when (tool) {
            "register_trigger" -> registerTrigger(params)
            "list_triggers" -> listTriggers()
            "remove_trigger" -> removeTrigger(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun registerTrigger(params: JsonObject): SkillResult {
        val event = params["event"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: event")
        val action = params["action"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: action")
        val id = "trigger_${System.currentTimeMillis()}"
        triggers.add(Trigger(id, event, action))
        return SkillResult.Success(buildJsonObject {
            put("trigger_id", id)
            put("event", event)
            put("action", action)
            put("status", "registered (stub - triggers are not yet executed)")
        }.toString())
    }

    private fun listTriggers(): SkillResult {
        val list = triggers.map {
            buildJsonObject {
                put("id", it.id)
                put("event", it.event)
                put("action", it.action)
            }
        }
        return SkillResult.Success(JsonArray(list).toString())
    }

    private fun removeTrigger(params: JsonObject): SkillResult {
        val id = params["trigger_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: trigger_id")
        val removed = triggers.removeAll { it.id == id }
        return if (removed) {
            SkillResult.Success(buildJsonObject { put("removed", id) }.toString())
        } else {
            SkillResult.Error("Trigger not found: $id")
        }
    }
}
