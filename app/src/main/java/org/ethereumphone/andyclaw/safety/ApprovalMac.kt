package org.ethereumphone.andyclaw.safety

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Signs a stored approval request, so only a request this app wrote can be executed.
 *
 * The queue lives in the app's own files, which nothing else on the device can write — but an
 * approval executes a call with the user's say-so, and "nothing else can write here" is exactly
 * the assumption a restored backup or a future bug would quietly break. A request whose
 * signature does not check out can still be declined; it can never be run.
 */
interface ApprovalMac {
    /** The signature for [data], or null when signing is unavailable (then nothing is executable). */
    fun sign(data: ByteArray): String?

    fun verify(data: ByteArray, signature: String?): Boolean {
        val expected = sign(data) ?: return false
        val given = signature ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
    }
}

/**
 * HMAC-SHA256 under an AndroidKeyStore key that never leaves the keystore. Unrelated to any
 * wallet key: losing it (app data cleared) only makes queued requests decline-only.
 */
class KeystoreApprovalMac : ApprovalMac {

    override fun sign(data: ByteArray): String? = try {
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(key())
        Base64.encodeToString(mac.doFinal(data), Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w(TAG, "approval signing unavailable: ${e.message}")
        null
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build())
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "KeystoreApprovalMac"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "andyclaw_approval_hmac"
    }
}
