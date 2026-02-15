package org.ethereumphone.andyclaw.extensions.security

import android.content.Context
import android.content.pm.PackageManager
import org.ethereumphone.andyclaw.extensions.ExtensionDescriptor
import org.ethereumphone.andyclaw.extensions.ExtensionType
import java.io.File
import java.security.MessageDigest
import java.util.jar.JarFile

/**
 * Result of a security validation check.
 */
sealed class SecurityCheckResult {
    /** All applicable checks passed — the extension may proceed. */
    data object Passed : SecurityCheckResult()

    /** A check failed — the extension must not proceed. */
    data class Failed(val reason: String) : SecurityCheckResult()

    /** Checks were skipped (trusted extension or disabled policy). */
    data class Skipped(val reason: String) : SecurityCheckResult()
}

/**
 * Enforces security boundaries for extension loading and execution.
 *
 * Validates:
 * - **Signature integrity** — APK signing certificates and JAR manifest signatures.
 * - **UID isolation** — APK extensions must not share the host's UID.
 * - **Runtime permissions** — Android permissions required by individual functions.
 *
 * All checks respect the configurable [policy], which supports per-extension
 * trust overrides and a full developer-mode bypass.
 */
class ExtensionSecurityManager(
    private val context: Context,
    var policy: ExtensionSecurityPolicy = ExtensionSecurityPolicy(),
) {

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Run all applicable security checks for [descriptor].
     *
     * Call this before allowing an extension to be loaded or executed.
     */
    fun validate(descriptor: ExtensionDescriptor): SecurityCheckResult {
        if (policy.isTrusted(descriptor)) {
            return SecurityCheckResult.Skipped("Extension ${descriptor.id} is trusted")
        }

        if (policy.enforceSignatureValidation) {
            val sigResult = validateSignature(descriptor)
            if (sigResult is SecurityCheckResult.Failed) return sigResult
        }

        if (policy.enforceUidIsolation && descriptor.type == ExtensionType.APK) {
            val uidResult = validateUidIsolation(descriptor)
            if (uidResult is SecurityCheckResult.Failed) return uidResult
        }

        return SecurityCheckResult.Passed
    }

    /**
     * Check whether the Android permissions required by a specific function are granted.
     */
    fun checkPermissions(
        descriptor: ExtensionDescriptor,
        requiredPermissions: List<String>,
    ): SecurityCheckResult {
        if (policy.isTrusted(descriptor) || !policy.enforcePermissionChecks) {
            return SecurityCheckResult.Skipped("Permission enforcement disabled or extension trusted")
        }

        for (permission in requiredPermissions) {
            val granted = context.checkSelfPermission(permission) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return SecurityCheckResult.Failed(
                    "Required permission not granted: $permission"
                )
            }
        }

        return SecurityCheckResult.Passed
    }

    // ── Signature validation ─────────────────────────────────────────

    private fun validateSignature(descriptor: ExtensionDescriptor): SecurityCheckResult {
        return when (descriptor.type) {
            ExtensionType.APK -> validateApkSignature(descriptor)
            ExtensionType.JAR -> validateJarSignature(descriptor)
        }
    }

    /**
     * Verify the APK's signing certificate via PackageManager.
     *
     * If [ExtensionDescriptor.signingCertHash] is provided, the certificate's
     * SHA-256 digest must match exactly.
     */
    private fun validateApkSignature(descriptor: ExtensionDescriptor): SecurityCheckResult {
        val packageName = descriptor.packageName
            ?: return SecurityCheckResult.Failed("APK extension ${descriptor.id} has no package name")

        return try {
            val pkgInfo = context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                )
            )

            val signingInfo = pkgInfo.signingInfo
                ?: return SecurityCheckResult.Failed("No signing info for $packageName")

            val signers = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }

            if (signers.isNullOrEmpty()) {
                return SecurityCheckResult.Failed("No signers found for $packageName")
            }

            // Pin to expected certificate if configured
            if (descriptor.signingCertHash != null) {
                val actualHash = sha256Hex(signers.first().toByteArray())
                if (!actualHash.equals(descriptor.signingCertHash, ignoreCase = true)) {
                    return SecurityCheckResult.Failed(
                        "Certificate mismatch for $packageName: " +
                                "expected ${descriptor.signingCertHash}, got $actualHash"
                    )
                }
            }

            SecurityCheckResult.Passed
        } catch (e: PackageManager.NameNotFoundException) {
            SecurityCheckResult.Failed("Package not installed: $packageName")
        } catch (e: Exception) {
            SecurityCheckResult.Failed("APK signature check failed: ${e.message}")
        }
    }

    /**
     * Verify JAR signature integrity by consuming all signed entries.
     *
     * A [SecurityException] during entry reading indicates a tampered entry.
     */
    private fun validateJarSignature(descriptor: ExtensionDescriptor): SecurityCheckResult {
        val jarPath = descriptor.jarPath
            ?: return SecurityCheckResult.Failed("JAR extension ${descriptor.id} has no file path")

        val file = File(jarPath)
        if (!file.exists()) {
            return SecurityCheckResult.Failed("JAR file not found: $jarPath")
        }

        return try {
            JarFile(file, true).use { jar ->
                // Reading every entry forces signature verification
                val buffer = ByteArray(8192)
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    jar.getInputStream(entry).use { stream ->
                        @Suppress("ControlFlowWithEmptyBody")
                        while (stream.read(buffer) != -1) { /* consume to trigger verify */ }
                    }
                }
            }
            SecurityCheckResult.Passed
        } catch (e: SecurityException) {
            SecurityCheckResult.Failed("JAR signature invalid: ${e.message}")
        } catch (e: Exception) {
            SecurityCheckResult.Failed("JAR validation error: ${e.message}")
        }
    }

    // ── UID isolation ────────────────────────────────────────────────

    /**
     * Verify that an APK extension runs under a different Linux UID
     * than the host application (i.e. no `android:sharedUserId` overlap).
     */
    private fun validateUidIsolation(descriptor: ExtensionDescriptor): SecurityCheckResult {
        val packageName = descriptor.packageName
            ?: return SecurityCheckResult.Failed("Cannot check UID without a package name")

        return try {
            val extAppInfo = context.packageManager.getApplicationInfo(packageName, 0)
            val hostUid = context.applicationInfo.uid

            if (extAppInfo.uid == hostUid) {
                SecurityCheckResult.Failed(
                    "Extension $packageName shares UID with host — isolation violated"
                )
            } else {
                SecurityCheckResult.Passed
            }
        } catch (e: PackageManager.NameNotFoundException) {
            SecurityCheckResult.Failed("Package not installed: $packageName")
        }
    }

    // ── Utilities ────────────────────────────────────────────────────

    companion object {
        /** Compute the SHA-256 hex digest of the given bytes. */
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }
}
