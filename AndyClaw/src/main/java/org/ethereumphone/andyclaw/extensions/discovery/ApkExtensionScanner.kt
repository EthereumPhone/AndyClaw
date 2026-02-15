package org.ethereumphone.andyclaw.extensions.discovery

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import kotlinx.serialization.json.Json
import org.ethereumphone.andyclaw.extensions.ApkBridgeType
import org.ethereumphone.andyclaw.extensions.ExtensionDescriptor
import org.ethereumphone.andyclaw.extensions.ExtensionFunction
import org.ethereumphone.andyclaw.extensions.ExtensionType

/**
 * Discovers APK-based extensions installed on the device.
 *
 * ## How APK extensions declare themselves
 *
 * An APK becomes an AndyClaw extension by adding metadata to its
 * `<application>` tag in `AndroidManifest.xml`:
 *
 * ```xml
 * <application ...>
 *   <meta-data android:name="org.ethereumphone.andyclaw.EXTENSION"
 *              android:value="true" />
 *   <meta-data android:name="org.ethereumphone.andyclaw.EXTENSION_ID"
 *              android:value="com.example.myextension" />
 *   <meta-data android:name="org.ethereumphone.andyclaw.EXTENSION_NAME"
 *              android:value="My Extension" />
 *   <!-- Optional: JSON resource listing functions -->
 *   <meta-data android:name="org.ethereumphone.andyclaw.EXTENSION_MANIFEST"
 *              android:resource="@raw/extension_manifest" />
 * </application>
 * ```
 *
 * ## Communication mechanisms
 *
 * Bridge types are auto-detected from declared components:
 *
 * | Component         | Intent action / authority pattern                           | Bridge type          |
 * |-------------------|-------------------------------------------------------------|----------------------|
 * | `<service>`       | `org.ethereumphone.andyclaw.EXTENSION_SERVICE`              | BOUND_SERVICE        |
 * | `<provider>`      | authority ending in `.andyclaw.extension`                    | CONTENT_PROVIDER     |
 * | `<receiver>`      | `org.ethereumphone.andyclaw.EXTENSION_BROADCAST`            | BROADCAST_RECEIVER   |
 * | `<activity>`      | `org.ethereumphone.andyclaw.EXTENSION_ACTION`               | EXPLICIT_INTENT      |
 */
class ApkExtensionScanner(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    companion object {
        // Application-level meta-data keys
        const val META_EXTENSION = "org.ethereumphone.andyclaw.EXTENSION"
        const val META_EXTENSION_ID = "org.ethereumphone.andyclaw.EXTENSION_ID"
        const val META_EXTENSION_NAME = "org.ethereumphone.andyclaw.EXTENSION_NAME"
        const val META_EXTENSION_MANIFEST = "org.ethereumphone.andyclaw.EXTENSION_MANIFEST"

        // Intent actions for bridge type detection
        const val ACTION_EXTENSION_SERVICE = "org.ethereumphone.andyclaw.EXTENSION_SERVICE"
        const val ACTION_EXTENSION_BROADCAST = "org.ethereumphone.andyclaw.EXTENSION_BROADCAST"
        const val ACTION_EXTENSION_ACTION = "org.ethereumphone.andyclaw.EXTENSION_ACTION"

        // ContentProvider authority suffix
        const val PROVIDER_AUTHORITY_SUFFIX = ".andyclaw.extension"
    }

    /**
     * Scan all installed packages for AndyClaw extension declarations.
     *
     * @return List of discovered extension descriptors (functions may be empty
     *         if the extension doesn't bundle an inline manifest).
     */
    fun scan(): List<ExtensionDescriptor> {
        val pm = context.packageManager
        val results = mutableListOf<ExtensionDescriptor>()

        try {
            val flags = PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_META_DATA
                        or PackageManager.GET_SERVICES
                        or PackageManager.GET_PROVIDERS
                        or PackageManager.GET_RECEIVERS
                        or PackageManager.GET_ACTIVITIES).toLong()
            )

            for (pkg in pm.getInstalledPackages(flags)) {
                val appMeta = pkg.applicationInfo?.metaData ?: continue
                if (!appMeta.getBoolean(META_EXTENSION, false)) continue

                val extensionId = appMeta.getString(META_EXTENSION_ID)
                    ?: "apk:${pkg.packageName}"

                val extensionName = appMeta.getString(META_EXTENSION_NAME)
                    ?: pkg.applicationInfo?.loadLabel(pm)?.toString()
                    ?: pkg.packageName

                val bridgeTypes = detectBridgeTypes(pkg, pm)
                val functions = loadManifestFunctions(pkg.packageName, appMeta, pm)

                results.add(
                    ExtensionDescriptor(
                        id = extensionId,
                        name = extensionName,
                        type = ExtensionType.APK,
                        version = pkg.longVersionCode.toInt(),
                        packageName = pkg.packageName,
                        bridgeTypes = bridgeTypes,
                        functions = functions,
                    )
                )
            }
        } catch (_: Exception) {
            // Discovery must never crash the host
        }

        return results
    }

    // ── Bridge detection ─────────────────────────────────────────────

    private fun detectBridgeTypes(
        pkgInfo: PackageInfo,
        pm: PackageManager,
    ): Set<ApkBridgeType> {
        val packageName = pkgInfo.packageName
        val types = mutableSetOf<ApkBridgeType>()
        val resolveFlags = PackageManager.ResolveInfoFlags.of(0)

        // Bound services
        val serviceIntent = Intent(ACTION_EXTENSION_SERVICE).setPackage(packageName)
        if (pm.queryIntentServices(serviceIntent, resolveFlags).isNotEmpty()) {
            types += ApkBridgeType.BOUND_SERVICE
        }

        // Broadcast receivers
        val broadcastIntent = Intent(ACTION_EXTENSION_BROADCAST).setPackage(packageName)
        if (pm.queryBroadcastReceivers(broadcastIntent, resolveFlags).isNotEmpty()) {
            types += ApkBridgeType.BROADCAST_RECEIVER
        }

        // Activities (explicit intents)
        val activityIntent = Intent(ACTION_EXTENSION_ACTION).setPackage(packageName)
        if (pm.queryIntentActivities(activityIntent, resolveFlags).isNotEmpty()) {
            types += ApkBridgeType.EXPLICIT_INTENT
        }

        // Content providers
        pkgInfo.providers?.forEach { provider ->
            if (provider.authority?.endsWith(PROVIDER_AUTHORITY_SUFFIX) == true) {
                types += ApkBridgeType.CONTENT_PROVIDER
            }
        }

        return types
    }

    // ── Manifest loading ─────────────────────────────────────────────

    /**
     * Attempt to load the extension's function manifest from an embedded
     * raw resource referenced via meta-data.
     */
    private fun loadManifestFunctions(
        packageName: String,
        meta: Bundle,
        pm: PackageManager,
    ): List<ExtensionFunction> {
        val resId = meta.getInt(META_EXTENSION_MANIFEST, 0)
        if (resId == 0) return emptyList()

        return try {
            val resources = pm.getResourcesForApplication(packageName)
            val raw = resources.openRawResource(resId).bufferedReader().readText()
            json.decodeFromString<List<ExtensionFunction>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
