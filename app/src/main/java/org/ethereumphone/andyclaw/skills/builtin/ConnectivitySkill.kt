package org.ethereumphone.andyclaw.skills.builtin

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.telephony.TelephonyManager
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

class ConnectivitySkill(private val context: Context) : AndyClawSkill {
    override val id = "connectivity"
    override val name = "Connectivity"

    override val baseManifest = SkillManifest(
        description = "Read device connectivity status (WiFi, Bluetooth, mobile data, airplane mode).",
        tools = listOf(
            ToolDefinition(
                name = "get_connectivity_status",
                description = "Get current connectivity status including WiFi, Bluetooth, mobile data, and airplane mode state.",
                inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
            ),
        ),
    )

    override val privilegedManifest = SkillManifest(
        description = "Toggle WiFi, Bluetooth, mobile data, airplane mode, and manage WiFi networks (privileged OS only).",
        tools = listOf(
            ToolDefinition(
                name = "toggle_wifi",
                description = "Enable or disable WiFi.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "enabled" to JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive("true to enable, false to disable"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("enabled"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "connect_wifi_network",
                description = "Connect to a WiFi network by SSID.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "ssid" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("WiFi network SSID"))),
                        "password" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("WiFi password (omit for open networks)"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("ssid"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "forget_wifi_network",
                description = "Remove a saved WiFi network.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "ssid" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("WiFi network SSID to forget"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("ssid"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "toggle_bluetooth",
                description = "Enable or disable Bluetooth.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "enabled" to JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive("true to enable, false to disable"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("enabled"))),
                )),
                requiresApproval = true,
                requiredPermissions = listOf("android.permission.BLUETOOTH_CONNECT"),
            ),
            ToolDefinition(
                name = "toggle_mobile_data",
                description = "Enable or disable mobile data.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "enabled" to JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive("true to enable, false to disable"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("enabled"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "toggle_airplane_mode",
                description = "Enable or disable airplane mode.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "enabled" to JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive("true to enable, false to disable"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("enabled"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "toggle_hotspot",
                description = "Enable or disable WiFi hotspot (tethering).",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "enabled" to JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive("true to enable, false to disable"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("enabled"))),
                )),
                requiresApproval = true,
            ),
        ),
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "get_connectivity_status" -> getConnectivityStatus()
            "toggle_wifi" -> privileged(tier) { toggleWifi(params) }
            "connect_wifi_network" -> privileged(tier) { connectWifiNetwork(params) }
            "forget_wifi_network" -> privileged(tier) { forgetWifiNetwork(params) }
            "toggle_bluetooth" -> privileged(tier) { toggleBluetooth(params) }
            "toggle_mobile_data" -> privileged(tier) { toggleMobileData(params) }
            "toggle_airplane_mode" -> privileged(tier) { toggleAirplaneMode(params) }
            "toggle_hotspot" -> privileged(tier) { toggleHotspot(params) }
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun privileged(tier: Tier, block: () -> SkillResult): SkillResult {
        return if (tier != Tier.PRIVILEGED) SkillResult.Error("This tool requires privileged OS access. Install AndyClaw as a system app on ethOS.")
        else block()
    }

    private fun getConnectivityStatus(): SkillResult {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

            val airplaneMode = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            val mobileDataEnabled = try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                tm.isDataEnabled
            } catch (_: Exception) { false }

            val result = buildJsonObject {
                put("wifi_enabled", wifiManager.isWifiEnabled)
                put("wifi_connected", connectivityManager.activeNetwork != null && wifiManager.isWifiEnabled)
                @Suppress("DEPRECATION")
                put("wifi_ssid", wifiManager.connectionInfo?.ssid?.removeSurrounding("\"") ?: "unknown")
                put("bluetooth_enabled", bluetoothManager?.adapter?.isEnabled == true)
                put("mobile_data_enabled", mobileDataEnabled)
                put("airplane_mode", airplaneMode)
            }
            SkillResult.Success(result.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to get connectivity status: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun toggleWifi(params: JsonObject): SkillResult {
        val enabled = params["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: return SkillResult.Error("Missing required parameter: enabled (boolean)")
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.isWifiEnabled = enabled
            SkillResult.Success(buildJsonObject { put("wifi_enabled", enabled) }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to toggle WiFi: ${e.message}")
        }
    }

    private fun connectWifiNetwork(params: JsonObject): SkillResult {
        val ssid = params["ssid"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: ssid")
        return try {
            // Use shell command for privileged WiFi connection
            val password = params["password"]?.jsonPrimitive?.contentOrNull
            val cmd = if (password != null) {
                "cmd wifi connect-network \"$ssid\" wpa2 \"$password\""
            } else {
                "cmd wifi connect-network \"$ssid\" open"
            }
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                SkillResult.Success(buildJsonObject { put("connected", ssid) }.toString())
            } else {
                val error = process.errorStream.bufferedReader().readText()
                SkillResult.Error("Failed to connect to WiFi network: $error")
            }
        } catch (e: Exception) {
            SkillResult.Error("Failed to connect to WiFi network: ${e.message}")
        }
    }

    private fun forgetWifiNetwork(params: JsonObject): SkillResult {
        val ssid = params["ssid"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: ssid")
        return try {
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val config = wifiManager.configuredNetworks?.find { it.SSID == "\"$ssid\"" }
                ?: return SkillResult.Error("WiFi network not found: $ssid")
            @Suppress("DEPRECATION")
            val removed = wifiManager.removeNetwork(config.networkId)
            if (removed) {
                SkillResult.Success(buildJsonObject { put("forgotten", ssid) }.toString())
            } else {
                SkillResult.Error("Failed to forget WiFi network: $ssid")
            }
        } catch (e: Exception) {
            SkillResult.Error("Failed to forget WiFi network: ${e.message}")
        }
    }

    private fun toggleBluetooth(params: JsonObject): SkillResult {
        val enabled = params["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: return SkillResult.Error("Missing required parameter: enabled (boolean)")
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
                ?: return SkillResult.Error("Bluetooth not available on this device")
            @Suppress("DEPRECATION")
            if (enabled) adapter.enable() else adapter.disable()
            SkillResult.Success(buildJsonObject { put("bluetooth_enabled", enabled) }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to toggle Bluetooth: ${e.message}")
        }
    }

    private fun toggleMobileData(params: JsonObject): SkillResult {
        val enabled = params["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: return SkillResult.Error("Missing required parameter: enabled (boolean)")
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val method = tm.javaClass.getDeclaredMethod("setDataEnabled", Boolean::class.javaPrimitiveType)
            method.invoke(tm, enabled)
            SkillResult.Success(buildJsonObject { put("mobile_data_enabled", enabled) }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to toggle mobile data: ${e.message}")
        }
    }

    private fun toggleAirplaneMode(params: JsonObject): SkillResult {
        val enabled = params["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: return SkillResult.Error("Missing required parameter: enabled (boolean)")
        return try {
            Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, if (enabled) 1 else 0)
            // Broadcast the change
            val intent = android.content.Intent(android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED)
            intent.putExtra("state", enabled)
            context.sendBroadcast(intent)
            SkillResult.Success(buildJsonObject { put("airplane_mode", enabled) }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to toggle airplane mode: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun toggleHotspot(params: JsonObject): SkillResult {
        val enabled = params["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: return SkillResult.Error("Missing required parameter: enabled (boolean)")
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wifiManager.javaClass.getDeclaredMethod(
                "setWifiApEnabled",
                android.net.wifi.WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType,
            )
            method.invoke(wifiManager, null, enabled)
            SkillResult.Success(buildJsonObject { put("hotspot_enabled", enabled) }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to toggle hotspot: ${e.message}")
        }
    }
}
