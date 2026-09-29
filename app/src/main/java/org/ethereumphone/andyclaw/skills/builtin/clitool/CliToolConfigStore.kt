@file:Suppress("DEPRECATION")

package org.ethereumphone.andyclaw.skills.builtin.clitool

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted key-value store for CLI tool configuration (API keys, env vars).
 * Each entry is stored as "cli.<toolId>.<KEY>" to avoid collisions.
 */
class CliToolConfigStore(context: Context) {

    companion object {
        private const val TAG = "CliToolConfigStore"
        private const val PREFS_NAME = "cli-tool-config.secure"
        private const val KEY_PREFIX = "cli."
    }

    private val prefs: SharedPreferences by lazy { openPrefs(context) }

    /**
     * Open the encrypted store, starting a fresh one if the existing file can't be
     * opened. The file's Tink keyset is wrapped by a keystore key that does not
     * travel with the file: after a restore onto another device, a keystore reset
     * or a lost master key, `create` throws — and, being `lazy`, threw again on
     * every access, so every cli_tools_* call failed until the app data was
     * cleared. The unreadable file is copied aside (not deleted, in case the key
     * comes back) and the store starts empty; the user re-enters the tool keys.
     */
    private fun openPrefs(context: Context): SharedPreferences = try {
        createPrefs(context)
    } catch (e: Exception) {
        if (e !is java.security.GeneralSecurityException && e !is java.io.IOException) throw e
        Log.e(TAG, "Encrypted CLI tool config unreadable; starting a fresh one", e)
        val file = java.io.File(context.dataDir, "shared_prefs/$PREFS_NAME.xml")
        try {
            if (file.isFile) {
                file.copyTo(
                    java.io.File(file.parentFile, "$PREFS_NAME.unreadable-${System.currentTimeMillis()}.bak"),
                    overwrite = true,
                )
            }
        } catch (copyError: Exception) {
            Log.w(TAG, "Could not keep a copy of the unreadable config", copyError)
        }
        // deleteSharedPreferences also evicts the in-process cache; renaming the
        // file alone would hand EncryptedSharedPreferences the same stale contents.
        context.deleteSharedPreferences(PREFS_NAME)
        createPrefs(context)
    }

    private fun createPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun setEnvVar(toolId: String, key: String, value: String) {
        prefs.edit { putString("$KEY_PREFIX$toolId.$key", value) }
    }

    fun getEnvVar(toolId: String, key: String): String? {
        return prefs.getString("$KEY_PREFIX$toolId.$key", null)
    }

    fun getAllEnvVars(toolId: String): Map<String, String> {
        val prefix = "$KEY_PREFIX$toolId."
        val result = mutableMapOf<String, String>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(prefix) && value is String) {
                result[key.removePrefix(prefix)] = value
            }
        }
        return result
    }

    fun removeAllForTool(toolId: String) {
        val prefix = "$KEY_PREFIX$toolId."
        prefs.edit {
            for (key in prefs.all.keys) {
                if (key.startsWith(prefix)) remove(key)
            }
        }
    }

    fun isConfigured(toolId: String, requiredKeys: List<String>): Boolean {
        if (requiredKeys.isEmpty()) return true
        return requiredKeys.all { key ->
            !prefs.getString("$KEY_PREFIX$toolId.$key", null).isNullOrBlank()
        }
    }
}
