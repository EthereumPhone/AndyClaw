package org.ethereumphone.andyclaw.skills.builtin

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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

class PackageManagerSkill(private val context: Context) : AndyClawSkill {
    override val id = "package_manager"
    override val name = "Package Manager"

    override val baseManifest = SkillManifest(
        description = "Manage installed application packages.",
        tools = emptyList(),
    )

    override val privilegedManifest = SkillManifest(
        description = "Uninstall apps, clear cache, and clear app data (privileged OS only).",
        tools = listOf(
            ToolDefinition(
                name = "uninstall_app",
                description = "Uninstall an app by package name.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "package_name" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Package name of the app to uninstall"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("package_name"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "clear_app_cache",
                description = "Clear the cache of an app.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "package_name" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Package name of the app"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("package_name"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "clear_app_data",
                description = "Clear all data for an app (WARNING: this is destructive and removes all app settings and files).",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "package_name" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Package name of the app"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("package_name"))),
                )),
                requiresApproval = true,
            ),
        ),
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        if (tier != Tier.PRIVILEGED) {
            return SkillResult.Error("$tool requires privileged OS access. Install AndyClaw as a system app on ethOS.")
        }
        return when (tool) {
            "uninstall_app" -> uninstallApp(params)
            "clear_app_cache" -> clearAppCache(params)
            "clear_app_data" -> clearAppData(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun uninstallApp(params: JsonObject): SkillResult {
        val packageName = params["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: package_name")
        if (packageName == context.packageName) {
            return SkillResult.Error("Cannot uninstall AndyClaw itself")
        }
        return try {
            // Verify app exists
            context.packageManager.getApplicationInfo(packageName, 0)
            // Use PackageInstaller for privileged uninstall
            val pi = context.packageManager.packageInstaller
            pi.uninstall(packageName, android.app.PendingIntent.getBroadcast(
                context, 0,
                Intent("org.ethereumphone.andyclaw.UNINSTALL_RESULT"),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            ).intentSender)
            SkillResult.Success(buildJsonObject {
                put("uninstall_initiated", true)
                put("package_name", packageName)
            }.toString())
        } catch (_: PackageManager.NameNotFoundException) {
            SkillResult.Error("App not found: $packageName")
        } catch (e: Exception) {
            SkillResult.Error("Failed to uninstall app: ${e.message}")
        }
    }

    private fun clearAppCache(params: JsonObject): SkillResult {
        val packageName = params["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: package_name")
        return try {
            context.packageManager.getApplicationInfo(packageName, 0)
            // Use hidden deleteApplicationCacheFiles API via reflection
            val observerClass = Class.forName("android.content.pm.IPackageDataObserver")
            val method = context.packageManager.javaClass.getMethod(
                "deleteApplicationCacheFiles",
                String::class.java,
                observerClass,
            )
            method.invoke(context.packageManager, packageName, null)
            SkillResult.Success(buildJsonObject {
                put("cache_cleared", true)
                put("package_name", packageName)
            }.toString())
        } catch (_: PackageManager.NameNotFoundException) {
            SkillResult.Error("App not found: $packageName")
        } catch (_: NoSuchMethodException) {
            // Fallback: use pm command
            try {
                val process = Runtime.getRuntime().exec(arrayOf("pm", "clear-cache", packageName))
                process.waitFor()
                SkillResult.Success(buildJsonObject {
                    put("cache_cleared", true)
                    put("package_name", packageName)
                    put("method", "fallback")
                }.toString())
            } catch (e: Exception) {
                SkillResult.Error("Failed to clear cache: ${e.message}")
            }
        } catch (e: Exception) {
            SkillResult.Error("Failed to clear cache: ${e.message}")
        }
    }

    private fun clearAppData(params: JsonObject): SkillResult {
        val packageName = params["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: package_name")
        if (packageName == context.packageName) {
            return SkillResult.Error("Cannot clear data of AndyClaw itself")
        }
        return try {
            context.packageManager.getApplicationInfo(packageName, 0)
            // Use ActivityManager's clearApplicationUserData via reflection
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            try {
                val observerClass = Class.forName("android.content.pm.IPackageDataObserver")
                val method = am.javaClass.getMethod(
                    "clearApplicationUserData",
                    String::class.java,
                    observerClass,
                )
                method.invoke(am, packageName, null)
            } catch (_: NoSuchMethodException) {
                // Fallback: use pm command
                val process = Runtime.getRuntime().exec(arrayOf("pm", "clear", packageName))
                process.waitFor()
            }
            SkillResult.Success(buildJsonObject {
                put("data_cleared", true)
                put("package_name", packageName)
            }.toString())
        } catch (_: PackageManager.NameNotFoundException) {
            SkillResult.Error("App not found: $packageName")
        } catch (e: Exception) {
            SkillResult.Error("Failed to clear app data: ${e.message}")
        }
    }
}
