package org.ethereumphone.andyclaw.extensions.security

import org.ethereumphone.andyclaw.extensions.ExtensionDescriptor

/**
 * Configurable security policy for the extension system.
 *
 * **All checks are enforced by default.** Users can opt out of individual
 * checks to run extensions at their own risk — this is a deliberate design
 * choice to balance safety with power-user flexibility.
 *
 * ## Opt-out mechanisms (from narrowest to broadest)
 *
 * 1. **[trustedExtensionIds]** — whitelist specific extensions.
 * 2. **[enforceSignatureValidation] / [enforcePermissionChecks] / [enforceUidIsolation]** —
 *    disable individual check categories system-wide.
 * 3. **[developerMode]** — bypass **all** checks (nuclear option).
 *
 * @property enforceSignatureValidation  Require valid APK / JAR signatures before loading.
 * @property enforcePermissionChecks     Require declared Android permissions to be granted.
 * @property enforceUidIsolation         Require APK extensions to run in a separate UID.
 * @property executionTimeoutMs          Maximum wall-clock time per invocation (ms).
 * @property trustedExtensionIds         Extension IDs that bypass all checks.
 * @property developerMode               If `true`, every check returns "skip". Use with caution.
 */
data class ExtensionSecurityPolicy(
    val enforceSignatureValidation: Boolean = true,
    val enforcePermissionChecks: Boolean = true,
    val enforceUidIsolation: Boolean = true,
    val executionTimeoutMs: Long = 30_000L,
    val trustedExtensionIds: Set<String> = emptySet(),
    val developerMode: Boolean = false,
) {
    /**
     * Returns `true` if the given extension should bypass all security checks.
     */
    fun isTrusted(descriptor: ExtensionDescriptor): Boolean {
        return developerMode ||
                descriptor.trusted ||
                descriptor.id in trustedExtensionIds
    }
}
