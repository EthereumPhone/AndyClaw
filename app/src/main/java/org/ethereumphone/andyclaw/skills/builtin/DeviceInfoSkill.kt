package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.app.ActivityManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import kotlinx.serialization.json.JsonPrimitive

class DeviceInfoSkill(private val context: Context) : AndyClawSkill {
    override val id = "device_info"
    override val name = "Device Info"

    override val baseManifest = SkillManifest(
        description = "Provides device information including battery, storage, network, model, OS, and RAM.",
        tools = listOf(
            ToolDefinition(
                name = "get_device_info",
                description = "Get comprehensive device information including battery level, storage usage, network status, device model, OS version, and RAM usage.",
                inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
            )
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "get_device_info" -> getDeviceInfo()
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun getDeviceInfo(): SkillResult {
        return try {
            val info = buildJsonObject {
                // Battery
                val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                val isCharging = batteryStatus?.let {
                    val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                } ?: false
                put("battery_level", batteryPct)
                put("is_charging", isCharging)

                // Storage
                val stat = StatFs(Environment.getDataDirectory().path)
                val totalBytes = stat.totalBytes
                val freeBytes = stat.availableBytes
                put("storage_total_gb", String.format("%.1f", totalBytes / 1_073_741_824.0))
                put("storage_free_gb", String.format("%.1f", freeBytes / 1_073_741_824.0))

                // Network
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork
                val caps = network?.let { cm.getNetworkCapabilities(it) }
                val networkType = when {
                    caps == null -> "disconnected"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                }
                put("network_type", networkType)

                // Device model
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)

                // OS
                put("android_version", Build.VERSION.RELEASE)
                put("sdk_version", Build.VERSION.SDK_INT)

                // RAM
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memInfo)
                put("ram_total_gb", String.format("%.1f", memInfo.totalMem / 1_073_741_824.0))
                put("ram_available_gb", String.format("%.1f", memInfo.availMem / 1_073_741_824.0))
            }
            SkillResult.Success(info.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to get device info: ${e.message}")
        }
    }
}
