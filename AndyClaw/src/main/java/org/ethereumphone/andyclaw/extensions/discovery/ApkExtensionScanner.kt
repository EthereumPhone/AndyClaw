package org.ethereumphone.andyclaw.extensions.discovery

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import kotlinx.serialization.json.Json
import org.ethereumphone.andyclaw.extensions.ApkBridgeType
import org.ethereumphone.andyclaw.extensions.ExtensionDescriptor
import org.ethereumphone.andyclaw.extensions.ExtensionFunction
import org.ethereumphone.andyclaw.extensions.ExtensionType
import org.ethereumphone.andyclaw.extensions.security.ExtensionSecurityManager

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

        private const val TAG = "ApkExtensionScanner"

        /** A function manifest is a few KB of JSON; more is not read at all. */
        private const val MAX_MANIFEST_BYTES = 256 * 1024

        /** Which package and signing certificate each extension id was first seen with. */
        private const val PINS_PREFS = "andyclaw_extension_pins"
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
                        or PackageManager.GET_ACTIVITIES
                        or PackageManager.GET_SIGNING_CERTIFICATES).toLong()
            )

            for (pkg in pm.getInstalledPackages(flags)) {
                // One app's broken declaration ends its own entry, not the scan for every other.
                try {
                    describe(pkg, pm)?.let { results += it }
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping ${pkg.packageName}: ${e.message}")
                }
            }
        } catch (_: Exception) {
            // Discovery must never crash the host
        }

        return pinned(results)
    }

    private fun describe(pkg: PackageInfo, pm: PackageManager): ExtensionDescriptor? {
        val appMeta = pkg.applicationInfo?.metaData ?: return null
        if (!appMeta.getBoolean(META_EXTENSION, false)) return null

        val extensionId = appMeta.getString(META_EXTENSION_ID)
            ?: "apk:${pkg.packageName}"

        val extensionName = appMeta.getString(META_EXTENSION_NAME)
            ?: pkg.applicationInfo?.loadLabel(pm)?.toString()
            ?: pkg.packageName

        val bridgeTypes = detectBridgeTypes(pkg, pm)
        val functions = loadManifestFunctions(pkg.packageName, appMeta, pm)

        return ExtensionDescriptor(
            id = extensionId,
            name = extensionName,
            type = ExtensionType.APK,
            version = pkg.longVersionCode.toInt(),
            packageName = pkg.packageName,
            bridgeTypes = bridgeTypes,
            functions = functions,
            // Checked again before every call (ExtensionSecurityManager): a package replaced by
            // another signer since this scan is refused there.
            signingCertHash = certHash(pkg),
        )
    }

    /**
     * Extensions name themselves, and everything the user grants one — the `ext:` skill, whether
     * it is enabled — is keyed on that name. So a later app that declared an enabled extension's
     * id took its place, and with it every call's parameters. Now an id belongs to the package and
     * signing certificate it was first seen with: another package or certificate claiming it is
     * not loaded, and an id claimed by two installed packages at once loads neither.
     */
    private fun pinned(found: List<ExtensionDescriptor>): List<ExtensionDescriptor> {
        val pins = context.getSharedPreferences(PINS_PREFS, Context.MODE_PRIVATE)
        val out = mutableListOf<ExtensionDescriptor>()
        for ((id, claims) in found.groupBy { it.id }) {
            if (claims.mapNotNull { it.packageName }.distinct().size > 1) {
                Log.w(TAG, "Extension id $id is declared by ${claims.map { it.packageName }}; loading none of them")
                continue
            }
            val descriptor = claims.first()
            val identity = "${descriptor.packageName}|${descriptor.signingCertHash}"
            when (val pin = pins.getString(id, null)) {
                null -> pins.edit().putString(id, identity).apply()
                identity -> Unit
                else -> {
                    Log.w(TAG, "Extension id $id now comes from ${descriptor.packageName} with a certificate " +
                        "it was not first seen with; not loading it (was ${pin.substringBefore('|')})")
                    continue
                }
            }
            out += descriptor
        }
        return out
    }

    private fun certHash(pkg: PackageInfo): String? {
        val info = pkg.signingInfo ?: return null
        // The same certificate ExtensionSecurityManager compares against.
        val signers = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        val first = signers?.firstOrNull() ?: return null
        return ExtensionSecurityManager.sha256Hex(first.toByteArray())
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
            // Bounded: any installed app can declare itself an extension, and a 500 MB resource
            // read into one String was an OutOfMemoryError on every start — no catch saw it.
            val raw = resources.openRawResource(resId).use { readBounded(it, MAX_MANIFEST_BYTES) }
                ?: return emptyList()
            json.decodeFromString<List<ExtensionFunction>>(raw)
        } catch (_: Exception) {
            emptyList()
        } catch (_: StackOverflowError) {
            // JSON nested a few hundred thousand levels deep.
            emptyList()
        }
    }

    /** At most [maxBytes] of [input] as UTF-8, or null when there is more. */
    private fun readBounded(input: java.io.InputStream, maxBytes: Int): String? {
        val buf = ByteArray(maxBytes + 1)
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return if (total > maxBytes) null else String(buf, 0, total, Charsets.UTF_8)
    }
}
