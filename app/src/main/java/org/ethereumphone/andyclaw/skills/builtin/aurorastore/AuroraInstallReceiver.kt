package org.ethereumphone.andyclaw.skills.builtin.aurorastore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import org.ethereumphone.andyclaw.skills.builtin.AuroraStoreSkill

/**
 * BroadcastReceiver that handles installation results from PackageInstaller
 * for apps installed via the Aurora Store skill.
 */
class AuroraInstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AuroraInstallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AuroraStoreSkill.ACTION_INSTALL_COMPLETE) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "unknown"
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Installation successful: $packageName")
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    Log.i(TAG, "User confirmation required for: $packageName")
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                }
            }

            PackageInstaller.STATUS_FAILURE ->
                Log.e(TAG, "Installation failed: $packageName - $message")

            PackageInstaller.STATUS_FAILURE_ABORTED ->
                Log.w(TAG, "Installation aborted: $packageName")

            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                Log.e(TAG, "Installation blocked: $packageName - $message")

            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                Log.e(TAG, "Installation conflict: $packageName - $message")

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Log.e(TAG, "Incompatible package: $packageName - $message")

            PackageInstaller.STATUS_FAILURE_INVALID ->
                Log.e(TAG, "Invalid package: $packageName - $message")

            PackageInstaller.STATUS_FAILURE_STORAGE ->
                Log.e(TAG, "Not enough storage: $packageName - $message")

            else ->
                Log.e(TAG, "Unknown status ($status): $packageName - $message")
        }
    }
}
