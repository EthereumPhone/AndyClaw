package org.ethereumphone.andyclaw.services

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class AndyClawNotificationListener : NotificationListenerService() {

    companion object {
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
        // Notifications are accessed via activeNotifications when needed
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

        // Try the target notification first, then fall back to siblings from the
        // same package (handles WhatsApp-style bundled notifications where the
        // group summary has no actions but child notifications do).
        val candidates = mutableListOf(sbn)
        candidates.addAll(all.filter { it.key != key && it.packageName == sbn.packageName })

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

        throw IllegalArgumentException("No notification from ${sbn.packageName} has a direct reply action")
    }
}
