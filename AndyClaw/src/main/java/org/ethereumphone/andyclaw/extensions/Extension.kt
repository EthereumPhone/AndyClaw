package org.ethereumphone.andyclaw.extensions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Type of extension runtime.
 */
enum class ExtensionType {
    /** Installed Android APK communicating via IPC. */
    APK,

    /** Dynamically-loaded JVM/DEX plugin. */
    JAR,
}

/**
 * Communication mechanism an APK extension exposes.
 *
 * The executor picks the highest-fidelity bridge available for each invocation.
 * Priority order: BOUND_SERVICE > CONTENT_PROVIDER > BROADCAST_RECEIVER > EXPLICIT_INTENT.
 */
enum class ApkBridgeType {
    /** AIDL / Messenger bound service — full bidirectional, streaming-capable. */
    BOUND_SERVICE,

    /** ContentProvider `call()` — synchronous request / response. */
    CONTENT_PROVIDER,

    /** Explicit broadcast with result receiver — asynchronous single-shot. */
    BROADCAST_RECEIVER,

    /** Activity intent — fire-and-forget (no structured return). */
    EXPLICIT_INTENT,
}

/**
 * A single function exposed by an extension.
 *
 * Maps 1-to-1 with a tool the agent can invoke.
 */
@Serializable
data class ExtensionFunction(
    /** Function name used for invocation (must be unique within the extension). */
    val name: String,

    /** Human-readable description shown to agents. */
    val description: String,

    /** JSON Schema describing the expected input parameters. */
    val inputSchema: JsonObject,

    /** If true, the engine will require explicit user approval before executing. */
    val requiresApproval: Boolean = false,

    /** Android permissions the host app must hold for this function to run. */
    val requiredPermissions: List<String> = emptyList(),
)

/**
 * Metadata describing a discovered extension.
 *
 * Produced by scanners, enriched by loaders, and stored in the [ExtensionRegistry].
 */
data class ExtensionDescriptor(
    /** Unique identifier. Convention: "apk:<package>" or "jar:<name>". */
    val id: String,

    /** Human-readable name. */
    val name: String,

    /** Whether this is an APK or JAR extension. */
    val type: ExtensionType,

    /** Monotonically increasing version for the extension. */
    val version: Int = 1,

    /** Package name of the APK (APK extensions only). */
    val packageName: String? = null,

    /** Filesystem path to the JAR/DEX file (JAR extensions only). */
    val jarPath: String? = null,

    /** IPC mechanisms the APK extension exposes (APK extensions only). */
    val bridgeTypes: Set<ApkBridgeType> = emptySet(),

    /** Functions this extension makes available. */
    val functions: List<ExtensionFunction> = emptyList(),

    /** SHA-256 hex digest of the extension's signing certificate (for verification). */
    val signingCertHash: String? = null,

    /** If true, this extension bypasses security checks. Set by the user, not the extension. */
    val trusted: Boolean = false,
)

/**
 * Outcome of an extension function invocation.
 */
sealed class ExtensionResult {
    /** The function completed successfully. */
    data class Success(val data: String) : ExtensionResult()

    /** The function failed with a recoverable error. */
    data class Error(val message: String, val cause: Throwable? = null) : ExtensionResult()

    /** Security policy blocked the invocation. */
    data class PermissionDenied(val reason: String) : ExtensionResult()

    /** The function exceeded the configured execution timeout. */
    data class Timeout(val millis: Long) : ExtensionResult()

    /** The function requires explicit user approval before proceeding. */
    data class ApprovalRequired(val description: String) : ExtensionResult()
}
