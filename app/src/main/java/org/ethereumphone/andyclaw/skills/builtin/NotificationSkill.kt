package org.ethereumphone.andyclaw.skills.builtin

import android.app.NotificationManager
import android.content.Context
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
import org.ethereumphone.andyclaw.services.AndyClawNotificationListener

class NotificationSkill(private val context: Context) : AndyClawSkill {
    override val id = "notifications"
    override val name = "Notifications"

    override val baseManifest = SkillManifest(
        description = "Read active notifications from the notification shade.",
        tools = listOf(
            ToolDefinition(
                name = "list_notifications",
                description = "List all active notifications. Requires notification listener access to be enabled in Settings.",
                inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
            ),
        ),
    )

    override val privilegedManifest = SkillManifest(
        description = "Dismiss, reply to, and auto-triage notifications (privileged OS only).",
        tools = listOf(
            ToolDefinition(
                name = "dismiss_notification",
                description = "Dismiss a notification by its key.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "key" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Notification key to dismiss"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("key"))),
                )),
            ),
            ToolDefinition(
                name = "reply_to_notification",
                description = "Reply to a notification that has a direct reply action.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "key" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Notification key"))),
                        "reply" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Reply text"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("key"), JsonPrimitive("reply"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "auto_triage",
                description = "Automatically categorize and prioritize current notifications.",
                inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
            ),
            ToolDefinition(
                name = "set_dnd_mode",
                description = "Set Do Not Disturb (DND) mode.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "enabled" to JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive("true to enable DND, false to disable"))),
                        "priority_only" to JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive("If true, allow priority notifications only (default false = total silence)"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("enabled"))),
                )),
                requiresApproval = true,
            ),
        ),
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "list_notifications" -> listNotifications()
            "dismiss_notification" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("dismiss_notification requires privileged OS")
                else dismissNotification(params)
            }
            "reply_to_notification" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("reply_to_notification requires privileged OS")
                else SkillResult.Error("reply_to_notification is not yet implemented")
            }
            "auto_triage" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("auto_triage requires privileged OS")
                else SkillResult.Error("auto_triage is not yet implemented")
            }
            "set_dnd_mode" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("set_dnd_mode requires privileged OS access. Install AndyClaw as a system app on ethOS.")
                else setDndMode(params)
            }
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun listNotifications(): SkillResult {
        val listener = AndyClawNotificationListener.instance
            ?: return SkillResult.Error("Notification listener not active. Enable it in Settings > Apps > Special access > Notification access.")
        return try {
            val notifications = listener.activeNotifications.map { sbn ->
                buildJsonObject {
                    put("key", sbn.key)
                    put("package", sbn.packageName)
                    put("title", sbn.notification.extras.getCharSequence("android.title")?.toString() ?: "")
                    put("text", sbn.notification.extras.getCharSequence("android.text")?.toString() ?: "")
                    put("time", sbn.postTime)
                    put("ongoing", sbn.isOngoing)
                }
            }
            SkillResult.Success(JsonArray(notifications).toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to list notifications: ${e.message}")
        }
    }

    private fun dismissNotification(params: JsonObject): SkillResult {
        val key = params["key"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: key")
        val listener = AndyClawNotificationListener.instance
            ?: return SkillResult.Error("Notification listener not active.")
        return try {
            listener.dismissNotification(key)
            SkillResult.Success(buildJsonObject { put("dismissed", key) }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to dismiss notification: ${e.message}")
        }
    }

    private fun setDndMode(params: JsonObject): SkillResult {
        val enabled = params["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: return SkillResult.Error("Missing required parameter: enabled (boolean)")
        val priorityOnly = params["priority_only"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val filter = when {
                !enabled -> NotificationManager.INTERRUPTION_FILTER_ALL
                priorityOnly -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else -> NotificationManager.INTERRUPTION_FILTER_NONE
            }
            nm.setInterruptionFilter(filter)
            val modeName = when (filter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority_only"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "total_silence"
                else -> "unknown"
            }
            SkillResult.Success(buildJsonObject {
                put("dnd_enabled", enabled)
                put("mode", modeName)
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to set DND mode: ${e.message}")
        }
    }
}
