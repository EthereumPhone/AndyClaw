package org.ethereumphone.andyclaw.services

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.skills.Capability
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities

class AndyClawNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"
        private const val WAKE_LOCK_TAG = "AndyClaw:notificationHeartbeat"
        /** The same bound the OS-triggered runs hold theirs for; the run itself may go on longer. */
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L

        @Volatile
        var instance: AndyClawNotificationListener? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // Never react to our own notifications (avoid infinite loops)
        if (sbn.packageName == applicationContext.packageName) return

        val app = applicationContext as? NodeApp ?: return

        // A mail or calendar app posting anything is the cheapest "something changed"
        // signal on the device, and it is the one that makes the schedule a backstop
        // rather than the loop. Only the posting package is used — never the notification's
        // own title or text, which is content a stranger wrote.
        app.onNotificationPosted(sbn.packageName)

        // Gate: privileged capability required
        if (!OsCapabilities.hasCapability(Capability.HEARTBEAT_ON_NOTIFICATION)) return

        // The master heartbeat switch is a kill switch for every automatic trigger.
        if (app.securePrefs.heartbeatIntervalMinutes.value <= 0) return

        // Gate: user must have the notification trigger enabled
        if (!app.securePrefs.heartbeatOnNotificationEnabled.value) return

        // Only something new and worth waking for, not too often, and not the echo of what the
        // agent itself just did (see NotificationTriggerPolicy).
        val n = sbn.notification
        val posted = org.ethereumphone.andyclaw.heartbeat.NotificationTriggerPolicy.Posted(
            key = sbn.key,
            packageName = sbn.packageName,
            ongoing = sbn.isOngoing,
            groupSummary = (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0,
            onlyAlertOnce = (n.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE) != 0,
            category = n.category,
            agentActedHere = org.ethereumphone.andyclaw.agent.RecentAgentActions.actedOnRecently(sbn.packageName),
        )
        if (!app.notificationTriggerPolicy.shouldTrigger(posted)) return

        // Like the OS-triggered heartbeat: without the wallet's sign-in every call it makes is
        // refused, and the run is paid for nothing.
        if (OsCapabilities.hasPrivilegedAccess && !app.securePrefs.walletSignature.value.startsWith("0x")) {
            Log.d(TAG, "Notification from ${sbn.packageName} — wallet sign-in missing, not triggering heartbeat")
            return
        }

        Log.d(TAG, "Notification from ${sbn.packageName} — triggering heartbeat")
        // Under a bounded wake lock, as every other trigger runs: with the screen off, the CPU
        // sleeping halfway left the run stalled until something else woke the phone.
        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        scope.launch {
            try {
                // Event-driven: this run covers the next scheduled tick, which is the whole point
                // of inverting the hierarchy.
                app.runtime.runHeartbeatNow(eventDriven = true)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Notification-triggered heartbeat failed: ${e.message}")
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op
    }

    fun dismissNotification(key: String) {
        cancelNotification(key)
    }

    fun replyToNotification(key: String, replyText: String) {
        val all = activeNotifications
        val sbn = all.find { it.key == key }
            ?: throw IllegalArgumentException("Notification not found: $key")

        // Try the target notification first, then fall back to siblings that are provably the
        // same conversation (WhatsApp-style bundles, where the entry the model picked has no
        // actions but a child notification for that chat does). "Same package" is not enough:
        // it used to send the reply to whichever chat of that app happened to have a reply
        // action, and it defeated ProvenanceGate's pinning of the approved key.
        val candidates = mutableListOf(sbn)
        candidates.addAll(all.filter {
            isSameConversation(
                target = ReplyTarget(sbn.key, sbn.packageName, sbn.groupKey, sbn.notification?.shortcutId),
                other = ReplyTarget(it.key, it.packageName, it.groupKey, it.notification?.shortcutId),
            )
        })

        for (candidate in candidates) {
            val actions = candidate.notification.actions ?: continue
            for (action in actions) {
                val remoteInputs = action.remoteInputs
                if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                    val intent = Intent()
                    val bundle = Bundle()
                    for (remoteInput in remoteInputs) {
                        bundle.putCharSequence(remoteInput.resultKey, replyText)
                    }
                    RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
                    action.actionIntent.send(applicationContext, 0, intent)
                    return
                }
            }
        }

        throw IllegalArgumentException(
            "Notification $key has no direct reply action, and no other notification is " +
                "provably the same conversation — not replying, to avoid messaging the wrong chat"
        )
    }
}

/** The identity fields [isSameConversation] compares, pulled out so it can be tested on the JVM. */
internal data class ReplyTarget(
    val key: String,
    val packageName: String,
    val groupKey: String?,
    val shortcutId: String?,
)

/**
 * Whether [other] may stand in for [target] when [target] itself has no reply action: same
 * package, same notification group, and the same non-empty conversation shortcut id. A missing
 * shortcut id on either side is not a match — without it nothing says the two are one chat.
 */
internal fun isSameConversation(target: ReplyTarget, other: ReplyTarget): Boolean =
    other.key != target.key &&
        other.packageName == target.packageName &&
        target.groupKey != null && other.groupKey == target.groupKey &&
        !target.shortcutId.isNullOrEmpty() && other.shortcutId == target.shortcutId
