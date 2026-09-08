package org.ethereumphone.andyclaw.ui.components

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/**
 * User confirmation for spending from the agent sub-account.
 *
 * ## Why there is no CryptoObject here
 *
 * This gate deliberately authenticates the *user action* and nothing else. It must never be
 * bound to the sub-account's signing key:
 *
 *  - `p256_walletsdk` is owner[0] of the sub-account's CREATE2 address. Requiring user
 *    authentication on that key would set `setInvalidatedByBiometricEnrollment` (which
 *    defaults to true), so adding or removing a single fingerprint would permanently
 *    invalidate it.
 *  - The usual remedy for that — delete and regenerate — changes the derived wallet address
 *    and strands every asset at the old one.
 *  - There is no recovery. The OS wallet is owner[1], but it cannot produce an ERC-1271
 *    signature over a raw hash, so it cannot authorise a sub-account UserOperation.
 *
 * If cryptographic binding is ever wanted, bind a `CryptoObject` to a *separate* throwaway
 * key that holds no funds and is safe to invalidate — never to `p256_walletsdk`.
 */
object SpendAuthGate {

    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Whether the device can show a biometric / device-credential prompt at all. */
    fun isAvailable(context: Context): Boolean {
        val manager = context.getSystemService(BiometricManager::class.java) ?: return false
        return manager.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Show the prompt. [onSuccess] runs only on a genuine authentication.
     *
     * Falls through to [onUnavailable] when no credential is enrolled, so the caller can
     * substitute its typed confirmation instead of silently allowing the send.
     */
    fun authenticate(
        activity: Activity,
        title: String,
        subtitle: String,
        description: String,
        onSuccess: () -> Unit,
        onCancelled: () -> Unit,
        onUnavailable: () -> Unit,
    ) {
        if (!isAvailable(activity)) {
            onUnavailable()
            return
        }

        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setDescription(description)
            // No negative button: DEVICE_CREDENTIAL supplies its own fallback, and setting
            // both is an IllegalArgumentException.
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        // Deliberately no CryptoObject — see the class comment.
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) = onSuccess()

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) = onCancelled()
            },
        )
    }
}
