package org.ethereumphone.andyclaw.extensions

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.ethereumphone.andyclaw.extensions.discovery.ApkExtensionScanner
import org.ethereumphone.andyclaw.extensions.discovery.JarExtensionScanner
import org.ethereumphone.andyclaw.extensions.execution.ApkExtensionExecutor
import org.ethereumphone.andyclaw.extensions.execution.JarExtensionLoader
import org.ethereumphone.andyclaw.extensions.security.ExtensionSecurityManager
import org.ethereumphone.andyclaw.extensions.security.ExtensionSecurityPolicy
import org.ethereumphone.andyclaw.extensions.security.SecurityCheckResult
import java.io.File

/**
 * Unified entry point for the AndyClaw extension system.
 *
 * Orchestrates the full lifecycle of both APK and JAR extensions:
 *
 * ```
 *   ┌─────────────┐     ┌──────────────┐     ┌──────────────┐
 *   │  Discovery   │────▶│   Registry   │────▶│  Execution   │
 *   │ (APK + JAR)  │     │  (indexing)  │     │ (IPC / DEX)  │
 *   └─────────────┘     └──────────────┘     └──────┬───────┘
 *                                                    │
 *                                            ┌───────▼───────┐
 *                                            │   Security     │
 *                                            │   (opt-out)    │
 *                                            └───────────────┘
 * ```
 *
 * ## Quick start
 *
 * ```kotlin
 * val engine = ExtensionEngine(context)
 *
 * // Optional: relax security for development
 * engine.updateSecurityPolicy(
 *     ExtensionSecurityPolicy(developerMode = true)
 * )
 *
 * // Discover and register all available extensions
 * engine.discoverAndRegister()
 *
 * // Invoke a function (routed automatically)
 * val result = engine.execute("myext.doSomething", params)
 *
 * // Bridge into the existing skill system
 * val adapters = engine.toSkillAdapters()
 * adapters.forEach { nativeSkillRegistry.register(it) }
 * ```
 *
 * ## Security model
 *
 * All security checks are **on by default**:
 * - Signature validation (APK certs + JAR signing)
 * - UID isolation (APK extensions must not share the host UID)
 * - Permission checks (Android runtime permissions)
 *
 * Users can opt out at various granularities — see [ExtensionSecurityPolicy].
 *
 * @param context          Android application context.
 * @param securityPolicy   Initial security policy (can be changed at runtime).
 */
class ExtensionEngine(
    private val context: Context,
    securityPolicy: ExtensionSecurityPolicy = ExtensionSecurityPolicy(),
) {

    /** Central registry of all discovered extensions. */
    val registry = ExtensionRegistry()

    /** Security manager (publicly accessible for advanced policy queries). */
    val securityManager = ExtensionSecurityManager(context, securityPolicy)

    private val apkScanner = ApkExtensionScanner(context)
    private val jarScanner = JarExtensionScanner()
    private val apkExecutor = ApkExtensionExecutor(context)
    private val jarLoader = JarExtensionLoader(
        cacheDir = File(context.cacheDir, "extension_dex"),
        workDir = File(context.filesDir, "extension_data"),
    )

    private val discoveryMutex = Mutex()

    /**
     * Directories scanned for JAR/DEX plugins.
     * Defaults to `<filesDir>/extensions/jars`. Add more as needed.
     */
    var jarExtensionDirs: List<File> = listOf(
        File(context.filesDir, "extensions/jars"),
    )

    // ── Discovery ────────────────────────────────────────────────────

    /**
     * Discover all available extensions (APK + JAR) and register them.
     *
     * Safe to call repeatedly — the registry is updated in place,
     * and JAR plugins that are already loaded are not re-loaded.
     */
    suspend fun discoverAndRegister() = discoveryMutex.withLock {
        withContext(Dispatchers.IO) {
            discoverApkExtensions()
            discoverJarExtensions()
        }
    }

    // ── Execution (by function name) ─────────────────────────────────

    /**
     * Execute a function by name, automatically routing to the correct extension.
     *
     * @param functionName Globally unique function name.
     * @param params       JSON parameters matching the function's input schema.
     * @return Execution result.
     */
    suspend fun execute(
        functionName: String,
        params: JsonObject,
    ): ExtensionResult {
        val descriptor = registry.findExtensionForFunction(functionName)
            ?: return ExtensionResult.Error("No extension provides function: $functionName")

        return executeOnExtension(descriptor, functionName, params)
    }

    // ── Execution (by extension ID + function name) ──────────────────

    /**
     * Execute a function on a specific extension identified by [extensionId].
     */
    suspend fun executeOnExtension(
        extensionId: String,
        functionName: String,
        params: JsonObject,
    ): ExtensionResult {
        val descriptor = registry.getExtension(extensionId)
            ?: return ExtensionResult.Error("Extension not found: $extensionId")

        return executeOnExtension(descriptor, functionName, params)
    }

    // ── Policy ───────────────────────────────────────────────────────

    /**
     * Replace the security policy at runtime.
     *
     * Changes take effect for the next invocation — in-flight calls
     * are not affected.
     */
    fun updateSecurityPolicy(policy: ExtensionSecurityPolicy) {
        securityManager.policy = policy
    }

    // ── Shutdown ─────────────────────────────────────────────────────

    /**
     * Tear down the engine: unbind APK services, destroy JAR plugins,
     * and clear the registry.
     */
    fun shutdown() {
        apkExecutor.unbindAll()
        jarLoader.unloadAll()
        registry.clear()
    }

    // ── Core execution path ──────────────────────────────────────────

    /**
     * Internal execution with security gating, approval checks, and routing.
     */
    private suspend fun executeOnExtension(
        descriptor: ExtensionDescriptor,
        functionName: String,
        params: JsonObject,
    ): ExtensionResult {
        // Locate the function definition (if available)
        val function = descriptor.functions.find { it.name == functionName }

        // ── Security gate ────────────────────────────────────────────
        val validationResult = securityManager.validate(descriptor)
        if (validationResult is SecurityCheckResult.Failed) {
            return ExtensionResult.PermissionDenied(validationResult.reason)
        }

        // Per-function permission check
        if (function != null && function.requiredPermissions.isNotEmpty()) {
            val permResult = securityManager.checkPermissions(
                descriptor,
                function.requiredPermissions,
            )
            if (permResult is SecurityCheckResult.Failed) {
                return ExtensionResult.PermissionDenied(permResult.reason)
            }
        }

        // Approval gate
        if (function?.requiresApproval == true) {
            return ExtensionResult.ApprovalRequired(
                "Function '$functionName' from '${descriptor.name}' requires user approval"
            )
        }

        // ── Route to executor ────────────────────────────────────────
        val timeoutMs = securityManager.policy.executionTimeoutMs

        return when (descriptor.type) {
            ExtensionType.APK ->
                apkExecutor.execute(descriptor, functionName, params, timeoutMs)

            ExtensionType.JAR ->
                jarLoader.execute(descriptor.id, functionName, params, timeoutMs)
        }
    }

    // ── Discovery helpers ────────────────────────────────────────────

    private fun discoverApkExtensions() {
        for (descriptor in apkScanner.scan()) {
            registry.register(descriptor)
        }
    }

    private fun discoverJarExtensions() {
        // Ensure scan directories exist
        jarExtensionDirs.forEach { it.mkdirs() }

        for (descriptor in jarScanner.scan(jarExtensionDirs)) {
            if (!jarLoader.isLoaded(descriptor.id)) {
                val enriched = jarLoader.load(descriptor)
                registry.register(enriched ?: descriptor)
            }
        }
    }
}
