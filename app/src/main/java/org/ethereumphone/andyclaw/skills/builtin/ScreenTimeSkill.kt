package org.ethereumphone.andyclaw.skills.builtin

import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition

class ScreenTimeSkill(private val context: Context) : AndyClawSkill {
    override val id = "screen_time"
    override val name = "Screen Time"

    override val baseManifest = SkillManifest(
        description = "Screen time and app usage statistics.",
        tools = emptyList(),
    )

    override val privilegedManifest = SkillManifest(
        description = "Query app usage statistics (privileged OS only, requires PACKAGE_USAGE_STATS).",
        tools = listOf(
            ToolDefinition(
                name = "get_usage_stats",
                description = "Get aggregated app usage statistics for a time period.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "start_time" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Start time as epoch millis (default: 24h ago)"))),
                        "end_time" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("End time as epoch millis (default: now)"))),
                        "limit" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Max apps to return, sorted by usage (default 20)"))),
                    )),
                )),
            ),
            ToolDefinition(
                name = "get_app_usage",
                description = "Get detailed usage for a specific app.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "package_name" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Package name of the app"))),
                        "start_time" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Start time as epoch millis (default: 24h ago)"))),
                        "end_time" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("End time as epoch millis (default: now)"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("package_name"))),
                )),
            ),
        ),
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        if (tier != Tier.PRIVILEGED) {
            return SkillResult.Error("$tool requires privileged OS access. Install AndyClaw as a system app on ethOS.")
        }
        return when (tool) {
            "get_usage_stats" -> getUsageStats(params)
            "get_app_usage" -> getAppUsage(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun getUsageStats(params: JsonObject): SkillResult {
        val now = System.currentTimeMillis()
        val startTime = params["start_time"]?.jsonPrimitive?.long ?: (now - 24L * 60 * 60 * 1000)
        val endTime = params["end_time"]?.jsonPrimitive?.long ?: now
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 20
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startTime, endTime)
            if (stats.isNullOrEmpty()) {
                return SkillResult.Error("No usage stats available. Ensure PACKAGE_USAGE_STATS permission is granted.")
            }
            val pm = context.packageManager
            val sorted = stats
                .filter { it.totalTimeInForeground > 0 }
                .sortedByDescending { it.totalTimeInForeground }
                .take(limit)
            val entries = sorted.map { stat ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(stat.packageName, 0)).toString()
                } catch (_: Exception) { stat.packageName }
                buildJsonObject {
                    put("package_name", stat.packageName)
                    put("label", label)
                    put("total_foreground_ms", stat.totalTimeInForeground)
                    put("total_foreground_minutes", stat.totalTimeInForeground / 60000)
                    put("last_used", stat.lastTimeUsed)
                }
            }
            SkillResult.Success(buildJsonObject {
                put("count", entries.size)
                put("start_time", startTime)
                put("end_time", endTime)
                put("apps", JsonArray(entries))
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to get usage stats: ${e.message}")
        }
    }

    private fun getAppUsage(params: JsonObject): SkillResult {
        val packageName = params["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: package_name")
        val now = System.currentTimeMillis()
        val startTime = params["start_time"]?.jsonPrimitive?.long ?: (now - 24L * 60 * 60 * 1000)
        val endTime = params["end_time"]?.jsonPrimitive?.long ?: now
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startTime, endTime)
            val appStat = stats?.find { it.packageName == packageName }
                ?: return SkillResult.Error("No usage data found for $packageName")
            val pm = context.packageManager
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (_: Exception) { packageName }
            SkillResult.Success(buildJsonObject {
                put("package_name", packageName)
                put("label", label)
                put("total_foreground_ms", appStat.totalTimeInForeground)
                put("total_foreground_minutes", appStat.totalTimeInForeground / 60000)
                put("last_used", appStat.lastTimeUsed)
                put("first_used", appStat.firstTimeStamp)
                put("start_time", startTime)
                put("end_time", endTime)
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to get app usage: ${e.message}")
        }
    }
}
