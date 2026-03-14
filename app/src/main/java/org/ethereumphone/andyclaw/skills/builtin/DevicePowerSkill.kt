package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
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

class DevicePowerSkill(private val context: Context) : AndyClawSkill {
    override val id = "device_power"
    override val name = "Device Power"

    override val baseManifest = SkillManifest(
        description = "Device power management.",
        tools = emptyList(),
    )

    override val privilegedManifest = SkillManifest(
        description = "Reboot device and lock screen (privileged OS only).",
        tools = listOf(
            ToolDefinition(
                name = "reboot_device",
                description = "Reboot the device. This is destructive - the device will restart immediately.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "reason" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Reboot reason string (optional, for logging)"))),
                    )),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "lock_screen",
                description = "Turn off the screen and lock the device.",
                inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
                requiresApproval = true,
            ),
        ),
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        if (tier != Tier.PRIVILEGED) {
            return SkillResult.Error("$tool requires privileged OS access. Install AndyClaw as a system app on ethOS.")
        }
        return when (tool) {
            "reboot_device" -> rebootDevice(params)
            "lock_screen" -> lockScreen()
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun rebootDevice(params: JsonObject): SkillResult {
        val reason = params["reason"]?.jsonPrimitive?.contentOrNull
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.reboot(reason)
            // If we get here, something went wrong (reboot should not return)
            SkillResult.Success(buildJsonObject { put("rebooting", true) }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to reboot device: ${e.message}")
        }
    }

    private fun lockScreen(): SkillResult {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            // goToSleep is @hide in standard SDK; use reflection
            val method = pm.javaClass.getMethod("goToSleep", Long::class.javaPrimitiveType)
            method.invoke(pm, SystemClock.uptimeMillis())
            SkillResult.Success(buildJsonObject { put("screen_locked", true) }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to lock screen: ${e.message}")
        }
    }
}
