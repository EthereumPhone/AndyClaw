package org.ethereumphone.andyclaw.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.ethereumphone.andyclaw.NodeForegroundService
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities

/**
 * Starts the heartbeat foreground service after a device reboot on non-ethOS devices.
 * On ethOS the OS binds to HeartbeatBindingService directly, so this receiver is a no-op.
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

        Log.i(TAG, "Boot completed on non-ethOS device — starting heartbeat foreground service")
        NodeForegroundService.start(context)
    }
}
