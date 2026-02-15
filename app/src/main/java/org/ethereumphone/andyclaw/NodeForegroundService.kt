package org.ethereumphone.andyclaw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File
import org.ethereumphone.andyclaw.agent.HeartbeatAgentRunner
import org.ethereumphone.andyclaw.heartbeat.HeartbeatConfig
import org.ethereumphone.andyclaw.heartbeat.HeartbeatInstructions

/**
 * Foreground service that keeps the AndyClaw heartbeat running.
 * The heartbeat is the AI's periodic self-check loop — without this service
 * running, the AI has no pulse.
 */
class NodeForegroundService : Service() {

    companion object {
        private const val TAG = "NodeForegroundService"
        private const val CHANNEL_ID = "andyclaw_heartbeat"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, NodeForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, NodeForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private val app: NodeApp
        get() = application as NodeApp

    private val runtime: NodeRuntime
        get() = app.runtime

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Wire up the real heartbeat agent runner with tool_use
        runtime.nativeSkillRegistry = app.nativeSkillRegistry
        runtime.anthropicClient = app.anthropicClient
        runtime.agentRunner = HeartbeatAgentRunner(app)

        // Configure 1-hour heartbeat with standing instructions prompt
        runtime.heartbeatConfig = HeartbeatConfig(
            intervalMs = 60L * 60 * 1000, // 1 hour
            heartbeatFilePath = File(filesDir, "HEARTBEAT.md").absolutePath,
        )

        // Seed HEARTBEAT.md if it doesn't exist
        seedHeartbeatFile()

        // Initialize and start the heartbeat
        runtime.initialize()
        runtime.startHeartbeat()

        Log.i(TAG, "Heartbeat service started")
        return START_STICKY
    }

    override fun onDestroy() {
        runtime.stopHeartbeat()
        Log.i(TAG, "Heartbeat service stopped")
        super.onDestroy()
    }

    private fun seedHeartbeatFile() {
        val file = File(filesDir, "HEARTBEAT.md")
        file.writeText(HeartbeatInstructions.CONTENT)
        Log.i(TAG, "Seeded HEARTBEAT.md")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AndyClaw Heartbeat",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the AI heartbeat running in the background"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AndyClaw")
            .setContentText("Heartbeat active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
