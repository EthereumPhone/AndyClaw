package org.ethereumphone.andyclaw.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities

/**
 * Used to start the heartbeat foreground service after a reboot on non-ethOS devices; Android 15
 * forbids that for its type (see [onReceive]). On ethOS the OS binds to HeartbeatBindingService
 * directly, so this receiver is a no-op there too.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        OsCapabilities.init(context)

        if (OsCapabilities.hasPrivilegedAccess) {
            Log.i(TAG, "ethOS detected — OS handles heartbeat, skipping foreground service")
            return
        }

        // Not started from here. NodeForegroundService is a dataSync foreground service, and
        // from Android 15 (minSdk 35) an app targeting it may not start one from BOOT_COMPLETED:
        // startForeground throws ForegroundServiceStartNotAllowedException and the process
        // crashes on every boot. MainActivity starts it when the user next opens the app. Moving
        // the service to a type that may start at boot is a manifest and Play-policy decision,
        // not something to slip in here.
        Log.i(TAG, "Boot completed on non-ethOS device — heartbeat resumes when the app is opened " +
            "(a dataSync foreground service may not start from BOOT_COMPLETED)")
    }
}
