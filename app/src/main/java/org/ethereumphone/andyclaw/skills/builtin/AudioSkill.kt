package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import android.media.AudioManager
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

class AudioSkill(private val context: Context) : AndyClawSkill {
    override val id = "audio"
    override val name = "Audio"

    override val baseManifest = SkillManifest(
        description = "Read audio and volume state.",
        tools = listOf(
            ToolDefinition(
                name = "get_audio_state",
                description = "Get current volume levels for all audio streams and ringer mode.",
                inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
            ),
        ),
    )

    override val privilegedManifest = SkillManifest(
        description = "Set volume levels and ringer mode (privileged OS only).",
        tools = listOf(
            ToolDefinition(
                name = "set_volume",
                description = "Set volume for a specific audio stream.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "stream" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Audio stream: music, ring, notification, alarm, voice_call, system"),
                        )),
                        "level" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Volume level (0 to max for stream)"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("stream"), JsonPrimitive("level"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "set_ringer_mode",
                description = "Set the device ringer mode.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "mode" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Ringer mode: normal, silent, or vibrate"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("mode"))),
                )),
                requiresApproval = true,
            ),
        ),
    )

    private val streamMap = mapOf(
        "music" to AudioManager.STREAM_MUSIC,
        "ring" to AudioManager.STREAM_RING,
        "notification" to AudioManager.STREAM_NOTIFICATION,
        "alarm" to AudioManager.STREAM_ALARM,
        "voice_call" to AudioManager.STREAM_VOICE_CALL,
        "system" to AudioManager.STREAM_SYSTEM,
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "get_audio_state" -> getAudioState()
            "set_volume" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("set_volume requires privileged OS access. Install AndyClaw as a system app on ethOS.")
                else setVolume(params)
            }
            "set_ringer_mode" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("set_ringer_mode requires privileged OS access. Install AndyClaw as a system app on ethOS.")
                else setRingerMode(params)
            }
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun getAudioState(): SkillResult {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val ringerMode = when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> "normal"
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "unknown"
            }
            val volumes = streamMap.map { (name, stream) ->
                buildJsonObject {
                    put("stream", name)
                    put("current", am.getStreamVolume(stream))
                    put("max", am.getStreamMaxVolume(stream))
                }
            }
            SkillResult.Success(buildJsonObject {
                put("ringer_mode", ringerMode)
                put("is_music_active", am.isMusicActive)
                put("volumes", JsonArray(volumes))
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to get audio state: ${e.message}")
        }
    }

    private fun setVolume(params: JsonObject): SkillResult {
        val streamName = params["stream"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: stream")
        val level = params["level"]?.jsonPrimitive?.int
            ?: return SkillResult.Error("Missing required parameter: level")
        val stream = streamMap[streamName]
            ?: return SkillResult.Error("Invalid stream: $streamName. Valid: ${streamMap.keys.joinToString()}")
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(stream)
            val clamped = level.coerceIn(0, max)
            am.setStreamVolume(stream, clamped, 0)
            SkillResult.Success(buildJsonObject {
                put("stream", streamName)
                put("level", clamped)
                put("max", max)
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to set volume: ${e.message}")
        }
    }

    private fun setRingerMode(params: JsonObject): SkillResult {
        val mode = params["mode"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: mode")
        val ringerMode = when (mode) {
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            else -> return SkillResult.Error("Invalid mode: $mode. Valid: normal, silent, vibrate")
        }
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = ringerMode
            SkillResult.Success(buildJsonObject { put("ringer_mode", mode) }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to set ringer mode: ${e.message}")
        }
    }
}
