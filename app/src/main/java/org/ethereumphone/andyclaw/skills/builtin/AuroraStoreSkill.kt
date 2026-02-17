package org.ethereumphone.andyclaw.skills.builtin

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.aurora.gplayapi.helpers.web.WebSearchHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.skills.builtin.aurorastore.AuroraStoreAuthProvider
import org.ethereumphone.andyclaw.skills.builtin.aurorastore.DeviceInfoProvider
import org.ethereumphone.andyclaw.skills.builtin.aurorastore.PlayStoreHttpClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Skill that allows downloading and installing apps from the Google Play Store
 * using Aurora Store's anonymous authentication.
 *
 * Provides tools to search for apps, get app details, and install apps by
 * package name. Checks whether an app is already installed before downloading.
 */
class AuroraStoreSkill(private val context: Context) : AndyClawSkill {

    companion object {
        private const val TAG = "AuroraStoreSkill"
        private const val DOWNLOAD_DIR = "aurora_downloads"
        const val ACTION_INSTALL_COMPLETE = "org.ethereumphone.andyclaw.AURORA_INSTALL_COMPLETE"
    }

    override val id = "aurora_store"
    override val name = "Aurora Store"

    override val baseManifest = SkillManifest(
        description = "Search and install apps from the Google Play Store via Aurora Store. " +
                "Can search for apps, get app details, check if an app is already installed, " +
                "and download + install apps by package name.",
        tools = listOf(
            ToolDefinition(
                name = "search_apps",
                description = "Search for apps on the Google Play Store. Returns a list of matching apps with their package names, display names, and descriptions.",
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(
                            mapOf(
                                "query" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("The search query (app name, keyword, etc.)")
                                    )
                                ),
                            )
                        ),
                        "required" to JsonArray(listOf(JsonPrimitive("query"))),
                    )
                ),
            ),
            ToolDefinition(
                name = "get_app_details",
                description = "Get detailed information about an app from the Play Store by its package name.",
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(
                            mapOf(
                                "package_name" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("The package name of the app (e.g. com.spotify.music)")
                                    )
                                ),
                            )
                        ),
                        "required" to JsonArray(listOf(JsonPrimitive("package_name"))),
                    )
                ),
            ),
            ToolDefinition(
                name = "install_app",
                description = "Download and install an app from the Play Store by its package name. " +
                        "Will first check if the app is already installed and skip if so. " +
                        "Supports split APKs. The user may need to confirm the installation on their device.",
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(
                            mapOf(
                                "package_name" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("The package name of the app to install (e.g. com.spotify.music)")
                                    )
                                ),
                                "force_reinstall" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("boolean"),
                                        "description" to JsonPrimitive("If true, reinstall even if the app is already installed. Defaults to false.")
                                    )
                                ),
                            )
                        ),
                        "required" to JsonArray(listOf(JsonPrimitive("package_name"))),
                    )
                ),
                requiresApproval = true,
            ),
        ),
        permissions = listOf("android.permission.INTERNET", "android.permission.REQUEST_INSTALL_PACKAGES"),
    )

    override val privilegedManifest: SkillManifest? = null

    // Lazy-initialized Aurora Store components
    private val httpClient by lazy { PlayStoreHttpClient() }
    private val deviceInfoProvider by lazy { DeviceInfoProvider(context) }
    private val authProvider by lazy { AuroraStoreAuthProvider(deviceInfoProvider, httpClient) }

    private val downloadHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "search_apps" -> searchApps(params)
            "get_app_details" -> getAppDetails(params)
            "install_app" -> installApp(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    // ── Authentication ──────────────────────────────────────────────

    private suspend fun ensureAuthenticated(): AuthData {
        if (!authProvider.isAuthenticated) {
            authProvider.authenticateAnonymously().getOrThrow()
        }
        return authProvider.authData!!
    }

    // ── Tool implementations ────────────────────────────────────────

    private suspend fun searchApps(params: JsonObject): SkillResult = withContext(Dispatchers.IO) {
        val query = params["query"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: query")

        try {
            ensureAuthenticated()

            val searchHelper = WebSearchHelper()
                .using(httpClient)
                .with(deviceInfoProvider.locale)

            val streamBundle = searchHelper.searchResults(query)
            val apps = streamBundle.streamClusters.values
                .flatMap { it.clusterAppList }
                .distinctBy { it.packageName }

            val results = buildJsonArray {
                for (app in apps.take(10)) {
                    add(buildJsonObject {
                        put("package_name", app.packageName)
                        put("display_name", app.displayName)
                        put("developer", app.developerName)
                        put("rating", app.labeledRating)
                        put("size", app.size)
                        put("is_free", app.isFree)
                        put("short_description", app.shortDescription)
                        put("installed", isAppInstalled(app.packageName))
                    })
                }
            }

            SkillResult.Success(buildJsonObject {
                put("query", query)
                put("result_count", results.size)
                put("apps", results)
            }.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for '$query'", e)
            SkillResult.Error("Failed to search for apps: ${e.message}")
        }
    }

    private suspend fun getAppDetails(params: JsonObject): SkillResult = withContext(Dispatchers.IO) {
        val packageName = params["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: package_name")

        try {
            ensureAuthenticated()

            val appDetailsHelper = AppDetailsHelper(authProvider.authData!!)
                .using(httpClient)

            val app = appDetailsHelper.getAppByPackageName(packageName)
            val installed = isAppInstalled(packageName)
            val installedVersionCode = getInstalledVersionCode(packageName)

            SkillResult.Success(buildJsonObject {
                put("package_name", app.packageName)
                put("display_name", app.displayName)
                put("developer", app.developerName)
                put("version_name", app.versionName)
                put("version_code", app.versionCode)
                put("rating", app.labeledRating)
                put("size", app.size)
                put("is_free", app.isFree)
                put("short_description", app.shortDescription)
                put("description", app.description)
                put("installed_on_device", installed)
                if (installed && installedVersionCode != null) {
                    put("installed_version_code", installedVersionCode)
                    put("update_available", app.versionCode > installedVersionCode.toInt())
                }
            }.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get details for $packageName", e)
            SkillResult.Error("Failed to get app details: ${e.message}")
        }
    }

    private suspend fun installApp(params: JsonObject): SkillResult = withContext(Dispatchers.IO) {
        val packageName = params["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: package_name")

        val forceReinstall = params["force_reinstall"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        // Check if the app is already installed
        if (isAppInstalled(packageName) && !forceReinstall) {
            val installedVersion = getInstalledVersionCode(packageName)
            return@withContext SkillResult.Success(buildJsonObject {
                put("already_installed", true)
                put("package_name", packageName)
                put("message", "App $packageName is already installed on this device.")
                if (installedVersion != null) {
                    put("installed_version_code", installedVersion)
                }
            }.toString())
        }

        try {
            // Step 1: Authenticate
            Log.i(TAG, "Starting install for $packageName")
            val authData = ensureAuthenticated()

            // Step 2: Get app details (needed for download)
            Log.i(TAG, "Fetching app details for download...")
            val appDetailsHelper = AppDetailsHelper(authData).using(httpClient)
            val app = appDetailsHelper.getAppByPackageName(packageName)
            Log.i(TAG, "App: ${app.displayName} v${app.versionName} (${app.versionCode})")

            // Step 3: Get download URLs via purchase
            Log.i(TAG, "Getting download URLs...")
            val purchaseHelper = PurchaseHelper(authData).using(httpClient)
            val files = purchaseHelper.purchase(
                packageName = app.packageName,
                versionCode = app.versionCode,
                offerType = app.offerType
            )

            if (files.isEmpty()) {
                return@withContext SkillResult.Error("No download files available for $packageName. The app may be paid or region-restricted.")
            }

            // Step 4: Download APK files
            Log.i(TAG, "Downloading ${files.size} APK file(s)...")
            val downloadDir = getDownloadDir(packageName, app.versionCode.toLong())
            val downloadedFiles = mutableListOf<File>()

            for (file in files) {
                val downloaded = downloadFile(file, downloadDir)
                    ?: return@withContext SkillResult.Error("Failed to download ${file.name}. The download URL may have expired.")
                downloadedFiles.add(downloaded)
            }

            Log.i(TAG, "Downloaded ${downloadedFiles.size} file(s), total size: ${downloadedFiles.sumOf { it.length() }} bytes")

            // Step 5: Install APKs
            Log.i(TAG, "Installing APK(s)...")
            val installResult = installApks(downloadedFiles, packageName)

            // Step 6: Clean up downloaded files
            downloadedFiles.forEach { it.delete() }
            downloadDir.deleteRecursively()

            if (installResult.isFailure) {
                return@withContext SkillResult.Error("Failed to install $packageName: ${installResult.exceptionOrNull()?.message}")
            }

            SkillResult.Success(buildJsonObject {
                put("install_initiated", true)
                put("package_name", packageName)
                put("app_name", app.displayName)
                put("version", app.versionName)
                put("files_count", files.size)
                put("message", "Installation of ${app.displayName} has been initiated. The user may need to confirm the installation on their device.")
            }.toString())

        } catch (e: Exception) {
            Log.e(TAG, "Failed to install $packageName", e)
            SkillResult.Error("Failed to install app: ${e.message}")
        }
    }

    // ── Package management helpers ──────────────────────────────────

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getInstalledVersionCode(packageName: String): Long? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
            packageInfo.longVersionCode
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    // ── Download helpers ────────────────────────────────────────────

    private fun getDownloadDir(packageName: String, versionCode: Long): File {
        return File(context.cacheDir, "$DOWNLOAD_DIR/$packageName/$versionCode").apply { mkdirs() }
    }

    private suspend fun downloadFile(file: PlayFile, downloadDir: File): File? =
        withContext(Dispatchers.IO) {
            try {
                if (file.url.isNullOrBlank()) {
                    Log.e(TAG, "Empty download URL for ${file.name}")
                    return@withContext null
                }

                val outputFile = File(downloadDir, file.name)

                val request = Request.Builder()
                    .url(file.url)
                    .build()

                downloadHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Download failed for ${file.name}: HTTP ${response.code}")
                        return@withContext null
                    }

                    response.body?.byteStream()?.use { inputStream ->
                        FileOutputStream(outputFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }

                Log.i(TAG, "Downloaded: ${file.name} (${outputFile.length()} bytes)")
                outputFile
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download ${file.name}", e)
                null
            }
        }

    // ── Install helpers ─────────────────────────────────────────────

    private suspend fun installApks(
        apkFiles: List<File>,
        packageName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Installing ${apkFiles.size} APK file(s) for $packageName")

            apkFiles.forEach { file ->
                if (!file.exists()) {
                    return@withContext Result.failure(Exception("APK file not found: ${file.name}"))
                }
            }

            val totalSize = apkFiles.sumOf { it.length() }
            val packageInstaller = context.packageManager.packageInstaller

            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(packageName)
                setSize(totalSize)
                setInstallReason(PackageManager.INSTALL_REASON_USER)
            }

            val sessionId = packageInstaller.createSession(params)
            Log.d(TAG, "Created install session: $sessionId")

            packageInstaller.openSession(sessionId).use { session ->
                apkFiles.forEachIndexed { index, apkFile ->
                    val name = if (apkFiles.size == 1) "base.apk" else "${index}_${apkFile.name}"
                    Log.d(TAG, "Writing APK: $name (${apkFile.length()} bytes)")

                    FileInputStream(apkFile).use { input ->
                        session.openWrite(name, 0, apkFile.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                }

                val intent = Intent(ACTION_INSTALL_COMPLETE).apply {
                    setPackage(context.packageName)
                    putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, packageName)
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )

                Log.i(TAG, "Committing install session for $packageName")
                session.commit(pendingIntent.intentSender)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APKs for $packageName", e)
            Result.failure(e)
        }
    }
}
