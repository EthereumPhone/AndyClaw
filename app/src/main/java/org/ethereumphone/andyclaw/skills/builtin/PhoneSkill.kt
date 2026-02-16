package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.telecom.TelecomManager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition

class PhoneSkill(private val context: Context) : AndyClawSkill {
    override val id = "phone"
    override val name = "Phone"

    override val baseManifest = SkillManifest(
        description = "Read call log from the device.",
        tools = listOf(
            ToolDefinition(
                name = "get_call_log",
                description = "Get recent call log entries.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "limit" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Max number of entries to return (default 20)"))),
                    )),
                )),
                requiredPermissions = listOf("android.permission.READ_CALL_LOG"),
            ),
        ),
    )

    override val privilegedManifest = SkillManifest(
        description = "Make phone calls and answer incoming calls (privileged OS only).",
        tools = listOf(
            ToolDefinition(
                name = "make_call",
                description = "Initiate a phone call to a number.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "number" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Phone number to call"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("number"))),
                )),
                requiresApproval = true,
                requiredPermissions = listOf("android.permission.CALL_PHONE"),
            ),
            ToolDefinition(
                name = "answer_call",
                description = "Answer an incoming phone call.",
                inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
                requiresApproval = true,
                requiredPermissions = listOf("android.permission.ANSWER_PHONE_CALLS"),
            ),
        ),
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "get_call_log" -> getCallLog(params)
            "make_call" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("make_call requires privileged OS access. Install AndyClaw as a system app on ethOS.")
                else makeCall(params)
            }
            "answer_call" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("answer_call requires privileged OS access. Install AndyClaw as a system app on ethOS.")
                else answerCall()
            }
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun getCallLog(params: JsonObject): SkillResult {
        val limit = params["limit"]?.jsonPrimitive?.int ?: 20
        return try {
            val entries = mutableListOf<JsonObject>()
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                ),
                null, null,
                "${CallLog.Calls.DATE} DESC",
            )
            cursor?.use {
                while (it.moveToNext() && entries.size < limit) {
                    val type = when (it.getInt(2)) {
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                        else -> "unknown"
                    }
                    entries.add(buildJsonObject {
                        put("number", it.getString(0) ?: "")
                        put("name", it.getString(1) ?: "")
                        put("type", type)
                        put("date", it.getLong(3))
                        put("duration_seconds", it.getInt(4))
                    })
                }
            }
            SkillResult.Success(buildJsonObject {
                put("count", entries.size)
                put("entries", JsonArray(entries))
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to read call log: ${e.message}")
        }
    }

    private fun makeCall(params: JsonObject): SkillResult {
        val number = params["number"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: number")
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            SkillResult.Success(buildJsonObject { put("calling", number) }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to make call: ${e.message}")
        }
    }

    private fun answerCall(): SkillResult {
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.acceptRingingCall()
            SkillResult.Success(buildJsonObject { put("answered", true) }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to answer call: ${e.message}")
        }
    }
}
