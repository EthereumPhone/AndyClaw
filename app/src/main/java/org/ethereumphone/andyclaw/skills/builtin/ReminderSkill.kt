package org.ethereumphone.andyclaw.skills.builtin

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
import org.ethereumphone.andyclaw.services.ReminderReceiver
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition

class ReminderSkill(private val context: Context) : AndyClawSkill {
    override val id = "reminders"
    override val name = "Reminders"

    companion object {
        private const val TAG = "ReminderSkill"
        private const val PREFS_NAME = "andyclaw_reminders"
    }

    override val baseManifest = SkillManifest(
        description = "Create, list, and cancel reminders. Reminders fire a notification at the scheduled time.",
        tools = listOf(
            ToolDefinition(
                name = "create_reminder",
                description = "Schedule a reminder that fires a notification at the specified time. Use epoch milliseconds for the time. The current device time is included below so you can calculate offsets.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "time" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("When to fire the reminder, as epoch milliseconds"),
                        )),
                        "message" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The reminder message shown in the notification body"),
                        )),
                        "label" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Short label for the notification title (default: 'Reminder')"),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("time"),
                        JsonPrimitive("message"),
                    )),
                )),
            ),
            ToolDefinition(
                name = "list_reminders",
                description = "List all pending (not yet fired) reminders.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(emptyMap()),
                )),
            ),
            ToolDefinition(
                name = "cancel_reminder",
                description = "Cancel a pending reminder by its ID.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "reminder_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("The ID of the reminder to cancel"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("reminder_id"))),
                )),
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "create_reminder" -> createReminder(params)
            "list_reminders" -> listReminders()
            "cancel_reminder" -> cancelReminder(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun createReminder(params: JsonObject): SkillResult {
        val time = params["time"]?.jsonPrimitive?.long
            ?: return SkillResult.Error("Missing required parameter: time")
        val message = params["message"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: message")
        val label = params["label"]?.jsonPrimitive?.contentOrNull ?: "Reminder"

        val now = System.currentTimeMillis()
        if (time <= now) {
            return SkillResult.Error("Reminder time must be in the future. Current time: $now, provided: $time")
        }

        // Preflight: check notification permission (Android 13+)
        val notifManager = context.getSystemService(NotificationManager::class.java)
        if (!notifManager.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications are disabled for this app")
            return SkillResult.Error(
                "Notifications are disabled for AndyClaw. The user must enable them in " +
                "Settings > Apps > AndyClaw > Notifications before reminders can work."
            )
        }

        // Preflight: check exact alarm permission (Android 12+)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "Exact alarm permission not granted")
            return SkillResult.Error(
                "Exact alarm permission not granted. The user must enable it in " +
                "Settings > Apps > AndyClaw > Alarms & reminders."
            )
        }

        val reminderId = (now % Int.MAX_VALUE).toInt()

        return try {
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId)
                putExtra(ReminderReceiver.EXTRA_REMINDER_MESSAGE, message)
                putExtra(ReminderReceiver.EXTRA_REMINDER_LABEL, label)
            }
            val pending = PendingIntent.getBroadcast(
                context, reminderId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
            Log.i(TAG, "Scheduled reminder id=$reminderId at $time (in ${time - now}ms)")

            // Persist so we can list/cancel later
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val entry = buildJsonObject {
                put("id", reminderId)
                put("time", time)
                put("message", message)
                put("label", label)
                put("created_at", now)
            }
            prefs.edit().putString(reminderId.toString(), entry.toString()).apply()

            SkillResult.Success(buildJsonObject {
                put("created", true)
                put("reminder_id", reminderId)
                put("time", time)
                put("label", label)
                put("message", message)
                put("current_device_time", now)
            }.toString())
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException scheduling alarm", e)
            SkillResult.Error("Cannot schedule exact alarm: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create reminder", e)
            SkillResult.Error("Failed to create reminder: ${e.message}")
        }
    }

    private fun listReminders(): SkillResult {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val reminders = mutableListOf<JsonObject>()

        for ((_, value) in prefs.all) {
            if (value !is String) continue
            try {
                val json = kotlinx.serialization.json.Json.parseToJsonElement(value) as? JsonObject
                    ?: continue
                val time = json["time"]?.jsonPrimitive?.long ?: continue
                // Only show future reminders
                if (time > now) {
                    reminders.add(json)
                }
            } catch (_: Exception) {
                // skip malformed entries
            }
        }

        reminders.sortBy { it["time"]?.jsonPrimitive?.long ?: 0L }

        return SkillResult.Success(buildJsonObject {
            put("count", reminders.size)
            put("reminders", JsonArray(reminders))
            put("current_device_time", now)
        }.toString())
    }

    private fun cancelReminder(params: JsonObject): SkillResult {
        val reminderId = params["reminder_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: return SkillResult.Error("Missing required parameter: reminder_id")

        return try {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val intent = Intent(context, ReminderReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context, reminderId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.cancel(pending)

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(reminderId.toString()).apply()

            SkillResult.Success(buildJsonObject {
                put("cancelled", true)
                put("reminder_id", reminderId)
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to cancel reminder: ${e.message}")
        }
    }
}
