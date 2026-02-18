package org.ethereumphone.andyclaw.skills.builtin.aurorastore

import android.content.Context
import java.io.File

/**
 * Persists download directories that should only be removed
 * after PackageInstaller reports a successful install.
 */
object AuroraInstallCleanupStore {
    private const val PREFS_NAME = "aurora_install_cleanup"
    private const val KEY_PREFIX = "pending_cleanup_"

    fun setPendingCleanupPath(context: Context, packageName: String, path: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(keyFor(packageName), path)
            .apply()
    }

    fun clearPendingCleanupPath(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(keyFor(packageName))
            .apply()
    }

    fun cleanupAfterSuccess(context: Context, packageName: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = keyFor(packageName)
        val path = prefs.getString(key, null) ?: return false

        val deleted = try {
            File(path).deleteRecursively()
        } catch (_: Exception) {
            false
        }

        if (deleted) {
            prefs.edit().remove(key).apply()
        }

        return deleted
    }

    private fun keyFor(packageName: String): String = "$KEY_PREFIX$packageName"
}
