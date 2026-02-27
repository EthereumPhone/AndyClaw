package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities

class CronjobSkill(private val context: Context) : AndyClawSkill {
    override val id = "cronjobs"
    override val name = "Cron Jobs"

    companion object {
        private const val TAG = "CronjobSkill"
        private const val PREFS_NAME = "andyclaw_cronjobs"
        private const val ACTION_CRONJOB_SCHEDULE = "org.ethereumphone.andyclaw.CRONJOB_SCHEDULE"
        private const val ACTION_CRONJOB_CANCEL = "org.ethereumphone.andyclaw.CRONJOB_CANCEL"
        private const val MIN_INTERVAL_MINUTES = 5L
        private const val MAX_INTERVAL_MINUTES = 7 * 24 * 60L // 7 days

        fun removeStoredCronjob(context: Context, cronjobId: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(cronjobId.toString()).apply()
        }
    }

    override val baseManifest = SkillManifest(
        description = "Create, list, and cancel recurring cron jobs. Cron jobs run the AI agent repeatedly at a fixed interval. Use these for periodic tasks like checking battery, monitoring prices, sending daily summaries, etc.",
        tools = listOf(
            ToolDefinition(
                name = "create_cronjob",
                description = "Create a recurring cron job that runs the AI agent at a fixed interval. The agent will be invoked each time with the reason you provide, and can use all its tools to act on it. Use this for any task that should happen periodically (e.g. 'check battery level and notify if below 20%' every 30 minutes).",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "interval_minutes" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("How often to run, in minutes. Minimum 5, maximum 10080 (7 days)."),
                        )),
                        "reason" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("What the agent should do each time this cron job fires. Be specific — this is the prompt the agent receives on each execution."),
                        )),
                        "label" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Short human-readable label for this cron job (default: 'Cron Job')"),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("interval_minutes"),
                        JsonPrimitive("reason"),
                    )),
                )),
            ),
            ToolDefinition(
                name = "list_cronjobs",
                description = "List all active cron jobs.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(emptyMap()),
                )),
            ),
            ToolDefinition(
                name = "cancel_cronjob",
                description = "Cancel an active cron job by its ID. It will stop recurring.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "cronjob_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("The ID of the cron job to cancel"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("cronjob_id"))),
                )),
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "create_cronjob" -> createCronjob(params)
            "list_cronjobs" -> listCronjobs()
            "cancel_cronjob" -> cancelCronjob(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun createCronjob(params: JsonObject): SkillResult {
        val intervalMinutes = params["interval_minutes"]?.jsonPrimitive?.long
            ?: return SkillResult.Error("Missing required parameter: interval_minutes")
        val reason = params["reason"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: reason")
        val label = params["label"]?.jsonPrimitive?.contentOrNull ?: "Cron Job"

        if (intervalMinutes < MIN_INTERVAL_MINUTES) {
            return SkillResult.Error("Interval must be at least $MIN_INTERVAL_MINUTES minutes, got $intervalMinutes")
        }
        if (intervalMinutes > MAX_INTERVAL_MINUTES) {
            return SkillResult.Error("Interval must be at most $MAX_INTERVAL_MINUTES minutes (7 days), got $intervalMinutes")
        }

        val now = System.currentTimeMillis()
        val cronjobId = (now % Int.MAX_VALUE).toInt()
        val intervalMs = intervalMinutes * 60 * 1000L

        return if (OsCapabilities.hasPrivilegedAccess) {
            createCronjobViaOs(cronjobId, intervalMs, intervalMinutes, reason, label, now)
        } else {
            // Non-ethOS: cron jobs require OS-level scheduling for reliability
            SkillResult.Error(
                "Cron jobs require ethOS for reliable recurring scheduling. " +
                "Use create_reminder for one-time scheduled tasks instead."
            )
        }
    }

    private fun createCronjobViaOs(
        cronjobId: Int, intervalMs: Long, intervalMinutes: Long,
        reason: String, label: String, now: Long,
    ): SkillResult {
        return try {
            val intent = Intent(ACTION_CRONJOB_SCHEDULE).apply {
                putExtra("cronjob_id", cronjobId)
                putExtra("interval_ms", intervalMs)
                putExtra("reason", reason)
                putExtra("label", label)
            }
            context.sendBroadcast(intent)
            Log.i(TAG, "Sent OS cronjob broadcast: id=$cronjobId interval=${intervalMinutes}min")

            // Mirror to local prefs for list/cancel tracking
            persistToLocalPrefs(cronjobId, intervalMs, reason, label, now)

            SkillResult.Success(buildJsonObject {
                put("created", true)
                put("cronjob_id", cronjobId)
                put("interval_minutes", intervalMinutes)
                put("label", label)
                put("reason", reason)
                put("current_device_time", now)
            }.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send cronjob to OS", e)
            SkillResult.Error("Failed to schedule OS cron job: ${e.message}")
        }
    }

    private fun persistToLocalPrefs(
        cronjobId: Int, intervalMs: Long, reason: String, label: String, now: Long,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val entry = buildJsonObject {
            put("id", cronjobId)
            put("interval_ms", intervalMs)
            put("interval_minutes", intervalMs / 60000)
            put("reason", reason)
            put("label", label)
            put("created_at", now)
        }
        prefs.edit().putString(cronjobId.toString(), entry.toString()).apply()
    }

    private fun listCronjobs(): SkillResult {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cronjobs = mutableListOf<JsonObject>()

        for ((_, value) in prefs.all) {
            if (value !is String) continue
            try {
                val json = kotlinx.serialization.json.Json.parseToJsonElement(value) as? JsonObject
                    ?: continue
                cronjobs.add(json)
            } catch (_: Exception) {
                // skip malformed entries
            }
        }

        cronjobs.sortBy { it["created_at"]?.jsonPrimitive?.long ?: 0L }

        return SkillResult.Success(buildJsonObject {
            put("count", cronjobs.size)
            put("cronjobs", JsonArray(cronjobs))
            put("current_device_time", System.currentTimeMillis())
        }.toString())
    }

    private fun cancelCronjob(params: JsonObject): SkillResult {
        val cronjobId = params["cronjob_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: return SkillResult.Error("Missing required parameter: cronjob_id")

        return if (OsCapabilities.hasPrivilegedAccess) {
            cancelCronjobViaOs(cronjobId)
        } else {
            SkillResult.Error("Cron jobs require ethOS.")
        }
    }

    private fun cancelCronjobViaOs(cronjobId: Int): SkillResult {
        return try {
            val intent = Intent(ACTION_CRONJOB_CANCEL).apply {
                putExtra("cronjob_id", cronjobId)
            }
            context.sendBroadcast(intent)
            Log.i(TAG, "Sent OS cronjob cancel broadcast: id=$cronjobId")

            // Remove from local mirror
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(cronjobId.toString()).apply()

            SkillResult.Success(buildJsonObject {
                put("cancelled", true)
                put("cronjob_id", cronjobId)
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to cancel OS cron job: ${e.message}")
        }
    }
}
