package org.ethereumphone.andyclaw.services

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
}
