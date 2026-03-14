package org.ethereumphone.andyclaw.skills.builtin

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

class AppsSkill(private val context: Context) : AndyClawSkill {
    override val id = "apps"
    override val name = "Apps"

    override val baseManifest = SkillManifest(
        description = "List, launch, and get info about installed apps.",
        tools = listOf(
            ToolDefinition(
                name = "list_installed_apps",
                description = "List all installed apps on the device with package names and labels.",
                inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
            ),
            ToolDefinition(
                name = "launch_app",
                description = "Launch an app by its package name.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "package_name" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("The package name of the app to launch"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("package_name"))),
                )),
            ),
            ToolDefinition(
                name = "get_app_info",
                description = "Get detailed information about an installed app.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "package_name" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("The package name of the app"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("package_name"))),
                )),
            ),
        ),
    )

    override val privilegedManifest = SkillManifest(
        description = "Force stop and interact with apps (privileged OS only).",
        tools = listOf(
            ToolDefinition(
                name = "force_stop_app",
                description = "Force stop an app by package name (privileged OS only).",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "package_name" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("The package name to force stop"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("package_name"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "interact_with_app",
                description = "Interact with an app's UI elements (stub - privileged OS only).",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "package_name" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Target app package name"))),
                        "action" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Action to perform (tap, type, scroll)"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("package_name"), JsonPrimitive("action"))),
                )),
                requiresApproval = true,
            ),
        ),
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "list_installed_apps" -> listApps()
            "launch_app" -> launchApp(params)
            "get_app_info" -> getAppInfo(params)
            "force_stop_app" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("force_stop_app requires privileged OS")
                else forceStopApp(params)
            }
            "interact_with_app" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("interact_with_app requires privileged OS")
                else SkillResult.Error("interact_with_app is not yet implemented")
            }
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun listApps(): SkillResult {
        return try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            val apps = packages.map { pkgInfo ->
                val appInfo = pkgInfo.applicationInfo
                val isSystemApp = appInfo != null &&
                        (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                buildJsonObject {
                    put("package_name", pkgInfo.packageName)
                    put("label", appInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkgInfo.packageName)
                    put("is_system_app", isSystemApp)
                }
            }
            SkillResult.Success(JsonArray(apps).toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to list apps: ${e.message}")
        }
    }

    private fun launchApp(params: JsonObject): SkillResult {
        val packageName = params["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: package_name")
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return SkillResult.Error("App not found or has no launcher activity: $packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            SkillResult.Success(buildJsonObject { put("launched", packageName) }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to launch app: ${e.message}")
        }
    }

    private fun getAppInfo(params: JsonObject): SkillResult {
        val packageName = params["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: package_name")
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val pkgInfo = pm.getPackageInfo(packageName, 0)
            val result = buildJsonObject {
                put("package_name", packageName)
                put("label", pm.getApplicationLabel(appInfo).toString())
                put("version_name", pkgInfo.versionName ?: "unknown")
                put("version_code", pkgInfo.longVersionCode)
                put("target_sdk", appInfo.targetSdkVersion)
                put("is_system_app", (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                put("enabled", appInfo.enabled)
            }
            SkillResult.Success(result.toString())
        } catch (e: PackageManager.NameNotFoundException) {
            SkillResult.Error("App not found: $packageName")
        } catch (e: Exception) {
            SkillResult.Error("Failed to get app info: ${e.message}")
        }
    }

    private fun forceStopApp(params: JsonObject): SkillResult {
        val packageName = params["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: package_name")
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            // Try hidden forceStopPackage first (truly kills the app), fall back to killBackgroundProcesses
            try {
                val method = am.javaClass.getDeclaredMethod("forceStopPackage", String::class.java)
                method.invoke(am, packageName)
            } catch (_: Exception) {
                @Suppress("DEPRECATION")
                am.killBackgroundProcesses(packageName)
            }
            SkillResult.Success(buildJsonObject { put("force_stopped", packageName) }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to force stop app: ${e.message}")
        }
    }
}
