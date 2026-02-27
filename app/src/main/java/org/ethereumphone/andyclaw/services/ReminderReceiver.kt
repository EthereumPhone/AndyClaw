package org.ethereumphone.andyclaw.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import org.ethereumphone.andyclaw.MainActivity

/**
 * Fires when a scheduled reminder alarm triggers (local AlarmManager path, non-ethOS).
 *
 * The notification-building logic lives in the companion object so it can be called from
 * two paths:
 * 1. Local: [onReceive] when [AlarmManager] fires (non-ethOS)
 * 2. OS:    [HeartbeatBindingService.reminderFired] when the system service delivers (ethOS)
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReminderReceiver"
        const val CHANNEL_ID = "andyclaw_reminders_alarm"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_REMINDER_MESSAGE = "reminder_message"
        const val EXTRA_REMINDER_LABEL = "reminder_label"
        private const val PREFS_NAME = "andyclaw_reminders"

        /**
         * Builds and shows the alarm-style reminder notification.
         */
        fun fireNotification(context: Context, reminderId: Int, message: String, label: String) {
            ensureChannel(context)

            val tapIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val tapPending = PendingIntent.getActivity(
                context, reminderId, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(label)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setSound(Settings.System.DEFAULT_ALARM_ALERT_URI)
                .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
                .setFullScreenIntent(tapPending, true)
                .setAutoCancel(true)
                .setContentIntent(tapPending)
                .build()

            val manager = context.getSystemService(NotificationManager::class.java)
            manager.notify(reminderId, notification)
            Log.i(TAG, "Reminder notification shown: id=$reminderId label=$label")
        }

        /**
         * Removes a fired reminder from the local SharedPreferences store.
         */
        fun removeStoredReminder(context: Context, id: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(id.toString()).apply()
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            // Delete the old silent channel if it lingered from a previous version
            manager.deleteNotificationChannel("andyclaw_reminders")
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return

            val alarmSound = Settings.System.DEFAULT_ALARM_ALERT_URI
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alarm-style reminders created by the AI assistant"
                setSound(alarmSound, audioAttrs)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                setBypassDnd(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getIntExtra(EXTRA_REMINDER_ID, 0)
        val message = intent.getStringExtra(EXTRA_REMINDER_MESSAGE) ?: "Reminder"
        val label = intent.getStringExtra(EXTRA_REMINDER_LABEL) ?: "Reminder"

        Log.i(TAG, "Reminder fired (local): id=$reminderId label=$label")
        fireNotification(context, reminderId, message, label)
        removeStoredReminder(context, reminderId)
    }
}
