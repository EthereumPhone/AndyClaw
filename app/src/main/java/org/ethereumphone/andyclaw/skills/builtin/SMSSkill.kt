package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition

class SMSSkill(private val context: Context) : AndyClawSkill {
    override val id = "sms"
    override val name = "SMS"

    override val baseManifest = SkillManifest(
        description = "Read SMS messages from the device.",
        tools = listOf(
            ToolDefinition(
                name = "read_sms",
                description = "Read recent SMS messages. Returns the most recent messages.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "limit" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Maximum number of messages to return (default 20)"))),
                        "from" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Filter by sender address/number"))),
                    )),
                )),
            ),
        ),
        permissions = listOf("android.permission.READ_SMS"),
    )

    override val privilegedManifest = SkillManifest(
        description = "Send SMS messages and set up auto-replies (privileged OS only).",
        tools = listOf(
            ToolDefinition(
                name = "send_sms",
                description = "Send an SMS message to a phone number.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "to" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Recipient phone number"))),
                        "message" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Message text"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("to"), JsonPrimitive("message"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "auto_reply_sms",
                description = "Set up an automatic reply for incoming SMS (stub - privileged OS only).",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "message" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Auto-reply message text"))),
                        "duration_minutes" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Duration in minutes for auto-reply"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("message"))),
                )),
                requiresApproval = true,
            ),
        ),
        permissions = listOf("android.permission.SEND_SMS"),
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "read_sms" -> readSms(params)
            "send_sms" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("send_sms requires privileged OS")
                else sendSms(params)
            }
            "auto_reply_sms" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("auto_reply_sms requires privileged OS")
                else SkillResult.Error("auto_reply_sms is not yet implemented")
            }
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun readSms(params: JsonObject): SkillResult {
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 20
        val fromFilter = params["from"]?.jsonPrimitive?.contentOrNull
        return try {
            val messages = mutableListOf<JsonObject>()
            val uri = Uri.parse("content://sms/inbox")
            val selection = fromFilter?.let { "address LIKE ?" }
            val selectionArgs = fromFilter?.let { arrayOf("%$it%") }
            val cursor = context.contentResolver.query(
                uri,
                arrayOf("_id", "address", "body", "date", "read"),
                selection,
                selectionArgs,
                "date DESC",
            )
            cursor?.use {
                while (it.moveToNext() && messages.size < limit) {
                    messages.add(buildJsonObject {
                        put("id", it.getString(0) ?: "")
                        put("from", it.getString(1) ?: "")
                        put("body", it.getString(2) ?: "")
                        put("date", it.getLong(3))
                        put("read", it.getInt(4) == 1)
                    })
                }
            }
            SkillResult.Success(JsonArray(messages).toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to read SMS: ${e.message}")
        }
    }

    private fun sendSms(params: JsonObject): SkillResult {
        val to = params["to"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: to")
        val message = params["message"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: message")
        return try {
            val smsManager = context.getSystemService(android.telephony.SmsManager::class.java)
            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(to, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(to, null, parts, null, null)
            }
            SkillResult.Success(buildJsonObject { put("sent", true); put("to", to) }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to send SMS: ${e.message}")
        }
    }
}
