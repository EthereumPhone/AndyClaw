package org.ethereumphone.andyclaw.flows

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * The HMAC key that authenticates installed flows, held in AndroidKeyStore so it cannot
 * be read out of the device even with the file system in hand.
 *
 * A flow is executable code that runs against live apps with the user's authority. The
 * content hash catches a corrupted write; this catches a *deliberate* one — a flow
 * dropped into `filesDir/flows/` by anything other than this app never verifies, and is
 * ignored rather than replayed.
 *
 * This key has nothing to do with the wallet. The wallet's P-256 key lives in keystore2
 * namespace 104 inside `system_server` and is frozen (ethOS `CLAUDE.md` §0.1). This one
 * is app-local, generated on first use, and losing it costs nothing worse than
 * re-recording the flows.
 */
class KeystoreFlowSigner(private val alias: String = DEFAULT_ALIAS) : FlowSigner {

    @Volatile
    private var cached: SecretKey? = null

    override fun mac(bytes: ByteArray): ByteArray {
        val key = key()
        return Mac.getInstance(ALGORITHM).apply { init(key) }.doFinal(bytes)
    }

    private fun key(): SecretKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val key = load() ?: generate()
            cached = key
            return key
        }
    }

    private fun load(): SecretKey? = try {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (e: Exception) {
        // Do NOT fall through to generating a replacement here: a transient keystore
        // error would silently invalidate every installed flow. Let it throw, and let
        // FlowStore refuse to load flows until the key is readable again.
        Log.w(TAG, "keystore lookup for '$alias' failed", e)
        throw e
    }

    private fun generate(): SecretKey {
        Log.i(TAG, "generating flow HMAC key '$alias'")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            ).build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "KeystoreFlowSigner"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALGORITHM = "HmacSHA256"
        const val DEFAULT_ALIAS = "andyclaw_flow_hmac"
    }
}
