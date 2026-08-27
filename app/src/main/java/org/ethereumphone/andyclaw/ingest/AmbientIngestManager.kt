package org.ethereumphone.andyclaw.ingest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Android half of the trigger inversion.
 *
 * `andyclaw-to-agent-first.md` §3: the notification listener is already event-driven and
 * calendar and mail should join it. This is the joining. It listens for the two signals the
 * app can actually get for free — a mail or calendar app posting a notification, and the
 * user unlocking the device — and turns each into an [AmbientIngestor] call that
 * [AmbientTriggerPolicy] may or may not let through.
 *
 * It deliberately does **not** run the agent. Ingesting updates what the device knows; it is
 * not thinking, and it must not open the heartbeat's backstop window — otherwise a burst of
 * mail would suppress the scheduled tick while nothing had actually reasoned about anything.
 * Rendering and reacting are Phase 4's; this phase's job is to have the data ready and
 * correct before either exists.
 *
 * Receivers are registered at runtime, not in the manifest. `ACTION_USER_PRESENT` is an
 * implicit broadcast, and a manifest receiver for one is not reliably delivered on modern
 * Android.
 */
class AmbientIngestManager(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val ingestor: AmbientIngestor,
) {

    private var receiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Register the listeners and do one sweep, so a fresh boot is current straight away. */
    fun start() {
        if (receiver != null) return

        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT) onSignal(AmbientSignal.USER_PRESENT)
            }
        }
        receiver = r
        try {
            appContext.registerReceiver(
                r,
                IntentFilter(Intent.ACTION_USER_PRESENT),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not register the unlock receiver: ${e.message}")
            receiver = null
        }

        // Coming back online is the moment whatever was missed while offline becomes
        // fetchable. Throttled hard by the policy, because a flaky connection announces
        // itself repeatedly.
        try {
            val cm = appContext.getSystemService(ConnectivityManager::class.java)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onSignal(AmbientSignal.CONNECTIVITY)
            }
            cm?.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "could not register the connectivity callback: ${e.message}")
        }

        onSignal(AmbientSignal.SCHEDULED)
    }

    fun stop() {
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
        networkCallback?.let { cb ->
            runCatching {
                appContext.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
    }

    /**
     * A notification arrived from [packageName].
     *
     * Only the posting package is looked at, never the notification's title or text — that
     * content is written by whoever sent the mail, and reading it to decide what to do is
     * the injection channel this app spent Phase 1 closing.
     */
    fun onNotificationFrom(packageName: String) {
        val signal = AmbientTriggerPolicy.signalFor(packageName) ?: return
        onSignal(signal)
    }

    fun onSignal(signal: AmbientSignal) {
        scope.launch {
            try {
                val report = ingestor.ingest(signal)
                if (report.ran) {
                    Log.i(TAG, "$signal -> ${report.contextsWritten} context row(s)")
                } else {
                    Log.d(TAG, "$signal skipped: ${report.skippedReason}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ambient ingest for $signal failed: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "AmbientIngest"
    }
}
