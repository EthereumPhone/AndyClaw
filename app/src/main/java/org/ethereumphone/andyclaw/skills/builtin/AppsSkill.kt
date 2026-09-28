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
                description = "List the apps a user can open (the app drawer), with package names and labels, " +
                    "sorted by label. Use this to find an app's real package name before launching or driving it; " +
                    "don't guess package names. `query` filters by label or package (e.g. \"notes\"); " +
                    "`include_all` lists every installed package, background and system ones included.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "query" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Case-insensitive text to match against the label or package name"))),
                        "include_all" to JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive("Every installed package, not only launchable apps (default false)"))),
                    )),
                )),
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
        ),
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "list_installed_apps" -> listApps(params)
            "launch_app" -> launchApp(params)
            "get_app_info" -> getAppInfo(params)
            "force_stop_app" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("force_stop_app requires privileged OS")
                else forceStopApp(params)
            }
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    /**
     * Launchable apps by default. Every package on a phone is 200+ entries, mostly overlays and
     * services nobody has heard of; as JSON that is ~27k characters, which the tool-result cap
     * cut to the first ~25 in PackageManager order, so the app the user named was usually not
     * among them (agentbench: the dgen1's own Notes app, never seen, and a note written to a
     * file in the sandbox instead).
     */
    private fun listApps(params: JsonObject): SkillResult {
        val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val includeAll = params["include_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        if (!includeAll) return listLaunchableApps(query)
        return try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
                .filter { query == null || AppQuery.matches(query, it.packageName,
                    it.applicationInfo?.let { a -> pm.getApplicationLabel(a).toString() }.orEmpty()) }
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

    private fun listLaunchableApps(query: String?): SkillResult = try {
        val pm = context.packageManager
        val all = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        fun json(apps: List<Pair<String, String>>) =
            JsonArray(apps.map { (pkg, label) -> buildJsonObject { put("package_name", pkg); put("label", label) } })
        val matches = if (query == null) all else all.filter { (pkg, label) -> AppQuery.matches(query, pkg, label) }
        if (query != null && matches.isEmpty()) {
            // An empty list read as "not installed": YouTube Music is labelled "YT Music" and its
            // package has no "youtube music" in it (agentbench app_installed_question). Say that
            // nothing matched the words, and show what is there.
            SkillResult.Success(buildJsonObject {
                put("matches", JsonArray(emptyList()))
                put("note", "No app's name or package contains every word of '$query'. Names can differ from " +
                    "what people call an app (YouTube Music is 'YT Music'), so check this list of every app " +
                    "that can be opened before saying it is not installed.")
                put("all_launchable_apps", json(all))
            }.toString())
        } else {
            SkillResult.Success(json(matches).toString())
        }
    } catch (e: Exception) {
        SkillResult.Error("Failed to list apps: ${e.message}")
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

/**
 * Whether an app answers to [query]: every word of it appears in the label or the package, as a
 * word or inside one ("music" in "com.google.android.apps.youtube.music", "maps" in "Google
 * Maps"). A plain substring test on the whole query missed an app whose label and package spell
 * the words apart.
 */
internal object AppQuery {
    fun matches(query: String, packageName: String, label: String): Boolean {
        val words = tokens(query)
        if (words.isEmpty()) return true
        val haystack = (tokens(packageName) + tokens(label)).toSet()
        val flat = "${packageName.lowercase()} ${label.lowercase()}"
        return words.all { w -> w in haystack || flat.contains(w) }
    }

    private fun tokens(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
}
