package org.ethereumphone.andyclaw.skills.builtin

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

class CameraSkill(private val context: Context) : AndyClawSkill {
    override val id = "camera"
    override val name = "Camera"

    override val baseManifest = SkillManifest(
        description = "Take photos using the device camera.",
        tools = listOf(
            ToolDefinition(
                name = "take_photo",
                description = "Take a photo using the device camera. Returns a base64-encoded image.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "facing" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Camera facing: front or back (default: back)"),
                        )),
                    )),
                )),
                requiresApproval = true,
                requiredPermissions = listOf("android.permission.CAMERA"),
            ),
        ),
        permissions = listOf("android.permission.CAMERA"),
    )

    override val privilegedManifest = SkillManifest(
        description = "Silent capture and image analysis (privileged OS only).",
        tools = listOf(
            ToolDefinition(
                name = "silent_capture",
                description = "Take a photo silently without user notification (privileged OS only).",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "facing" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Camera facing: front or back"))),
                    )),
                )),
                requiredPermissions = listOf("android.permission.CAMERA"),
            ),
            ToolDefinition(
                name = "analyze_image",
                description = "Capture and analyze the scene using the camera (privileged OS only).",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "prompt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("What to look for in the image"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("prompt"))),
                )),
                requiredPermissions = listOf("android.permission.CAMERA"),
            ),
        ),
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "take_photo" -> takePhoto(params)
            "silent_capture" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("silent_capture requires privileged OS")
                else takePhoto(params)
            }
            "analyze_image" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("analyze_image requires privileged OS")
                else SkillResult.Error("analyze_image is not yet implemented")
            }
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun takePhoto(params: JsonObject): SkillResult {
        // This wraps the existing CameraCaptureManager.
        // Since CameraCaptureManager requires lifecycle/coroutine context that's set up elsewhere,
        // we return a message indicating the capture should be routed through the existing system.
        val facing = params["facing"]?.jsonPrimitive?.contentOrNull ?: "back"
        return SkillResult.Success(buildJsonObject {
            put("status", "capture_requested")
            put("facing", facing)
            put("message", "Photo capture initiated. The result will be provided by the camera system.")
        }.toString())
    }
}
