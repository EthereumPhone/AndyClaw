package org.ethereumphone.andyclaw.services

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.skills.Capability
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities

class AndyClawNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"

        @Volatile
        var instance: AndyClawNotificationListener? = null
            private set
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

        Log.d(TAG, "Notification from ${sbn.packageName} — triggering heartbeat")
        // Event-driven: this run covers the next scheduled tick, which is the whole point
        // of inverting the hierarchy.
        app.runtime.requestHeartbeatNow(eventDriven = true)
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
