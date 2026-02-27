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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.agent.HeartbeatAgentRunner
import org.ethereumphone.andyclaw.heartbeat.HeartbeatConfig
import org.ethereumphone.andyclaw.heartbeat.HeartbeatInstructions
import org.ethereumphone.andyclaw.skills.Capability
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities
import org.ethereumphone.andyclaw.telegram.TelegramBotService
import org.ethereumhpone.messengersdk.MessengerSDK
import org.ethereumhpone.messengersdk.SdkException

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
        const val EXTRA_XMTP_MESSAGE_COUNT = "xmtp_message_count"

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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var messengerSdk: MessengerSDK? = null
    private var telegramBotService: TelegramBotService? = null
    private var serviceInitialized = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        if (!serviceInitialized) {
            // Wire up the real heartbeat agent runner with tool_use
            runtime.nativeSkillRegistry = app.nativeSkillRegistry
            runtime.anthropicClient = app.anthropicClient
            runtime.agentRunner = HeartbeatAgentRunner(app, app.heartbeatLogStore)

            // Configure heartbeat with user-chosen interval
            val intervalMinutes = app.securePrefs.heartbeatIntervalMinutes.value
            runtime.heartbeatConfig = HeartbeatConfig(
                intervalMs = intervalMinutes.toLong() * 60 * 1000,
                heartbeatFilePath = File(filesDir, "HEARTBEAT.md").absolutePath,
            )

            // Seed HEARTBEAT.md if it doesn't exist
            seedHeartbeatFile()

            // Initialize and start the heartbeat
            runtime.initialize()
            runtime.startHeartbeat()

            // Observe interval changes and update config dynamically
            observeHeartbeatInterval()

            // Start listening for XMTP new-message callbacks
            startXmtpMessageListener()

            // Start Telegram bot if configured
            startTelegramBot()
            observeTelegramPrefs()

            serviceInitialized = true
            Log.i(TAG, "Heartbeat service started (interval: ${intervalMinutes}m)")
        }

        // Handle XMTP broadcast wake-up
        val xmtpCount = intent?.getIntExtra(EXTRA_XMTP_MESSAGE_COUNT, 0) ?: 0
        if (xmtpCount > 0) {
            handleXmtpWakeup(xmtpCount)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        runtime.stopHeartbeat()
        telegramBotService?.stop()
        telegramBotService = null
        messengerSdk?.identity?.unbind()
        messengerSdk = null
        serviceScope.cancel()
        Log.i(TAG, "Heartbeat service stopped")
        super.onDestroy()
    }

    /**
     * Observes the heartbeat interval preference and updates the runtime config
     * whenever the user changes it in settings.
     */
    private fun observeHeartbeatInterval() {
        serviceScope.launch {
            app.securePrefs.heartbeatIntervalMinutes
                .collect { minutes ->
                    val newIntervalMs = minutes.toLong() * 60 * 1000
                    Log.i(TAG, "Heartbeat interval changed to ${minutes}m")
                    runtime.heartbeatConfig = HeartbeatConfig(
                        intervalMs = newIntervalMs,
                        heartbeatFilePath = File(filesDir, "HEARTBEAT.md").absolutePath,
                    )
                }
        }
    }

    /**
     * Starts the Telegram bot polling service if enabled and a token is configured.
     */
    private fun startTelegramBot() {
        val enabled = app.securePrefs.telegramBotEnabled.value
        val token = app.securePrefs.telegramBotToken.value
        if (!enabled || token.isBlank()) {
            Log.d(TAG, "Telegram bot not enabled or no token configured")
            return
        }

        val service = TelegramBotService(app, serviceScope)
        telegramBotService = service
        service.start()
        Log.i(TAG, "Telegram bot started")
    }

    /**
     * Observes Telegram-related preferences and auto-starts or stops the bot
     * when the user toggles it or changes the token in settings.
     */
    private fun observeTelegramPrefs() {
        serviceScope.launch {
            app.securePrefs.telegramBotEnabled.collect { enabled ->
                val token = app.securePrefs.telegramBotToken.value
                if (enabled && token.isNotBlank()) {
                    if (telegramBotService?.isRunning != true) {
                        telegramBotService?.stop()
                        val service = TelegramBotService(app, serviceScope)
                        telegramBotService = service
                        service.start()
                        Log.i(TAG, "Telegram bot started via pref change")
                    }
                } else {
                    telegramBotService?.stop()
                    telegramBotService = null
                    Log.i(TAG, "Telegram bot stopped via pref change")
                }
            }
        }

        serviceScope.launch {
            app.securePrefs.telegramBotToken.collect { token ->
                val enabled = app.securePrefs.telegramBotEnabled.value
                if (enabled && token.isNotBlank()) {
                    // Restart with new token
                    telegramBotService?.stop()
                    val service = TelegramBotService(app, serviceScope)
                    telegramBotService = service
                    service.start()
                    Log.i(TAG, "Telegram bot restarted with new token")
                } else if (token.isBlank()) {
                    telegramBotService?.stop()
                    telegramBotService = null
                }
            }
        }
    }

    /**
     * Handles a wake-up from the XMTP broadcast receiver.
     * Fetches full message context via the SDK when available, otherwise
     * falls back to a generic prompt.
     */
    private fun handleXmtpWakeup(messageCount: Int) {
        if (!app.securePrefs.heartbeatOnXmtpMessageEnabled.value) return

        serviceScope.launch {
            val sdk = messengerSdk
            val context = if (sdk != null) {
                try {
                    fetchNewMessagesContext(sdk, messageCount)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch XMTP messages for heartbeat context", e)
                    "You received $messageCount new XMTP message(s). " +
                        "Use list_conversations and read_messages to check them."
                }
            } else {
                "You received $messageCount new XMTP message(s). " +
                    "Use list_conversations and read_messages to check them."
            }
            runtime.requestHeartbeatNowWithContext(context)
        }
    }

    /**
     * Binds the MessengerSDK IdentityClient and collects its [newMessages] flow.
     * When new messages arrive and the user has the setting enabled, fetches
     * the actual message contents and triggers a heartbeat with that context.
     */
    private fun startXmtpMessageListener() {
        if (!OsCapabilities.hasCapability(Capability.HEARTBEAT_ON_XMTP_MESSAGE)) return

        serviceScope.launch {
            try {
                val sdk = MessengerSDK.getInstance(this@NodeForegroundService)
                messengerSdk = sdk

                sdk.identity.bind()
                sdk.identity.awaitConnected()
                Log.i(TAG, "MessengerSDK identity bound for XMTP message listening")

                sdk.identity.newMessages.collect { count ->
                    if (!app.securePrefs.heartbeatOnXmtpMessageEnabled.value) return@collect
                    if (count <= 0) return@collect

                    Log.d(TAG, "XMTP callback: $count new message(s) — fetching and triggering heartbeat")

                    val context = try {
                        fetchNewMessagesContext(sdk, count)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch XMTP messages for heartbeat context", e)
                        "You received $count new XMTP message(s). Use list_conversations and read_messages to check them."
                    }

                    runtime.requestHeartbeatNowWithContext(context)
                }
            } catch (e: SdkException) {
                Log.w(TAG, "MessengerSDK not available for XMTP message listening: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start XMTP message listener: ${e.message}", e)
            }
        }
    }

    /**
     * Fetches recent messages from all conversations and formats them as context
     * for the heartbeat prompt.
     */
    private suspend fun fetchNewMessagesContext(sdk: MessengerSDK, count: Int): String {
        val conversations = sdk.identity.getConversations()
        if (conversations.isEmpty()) {
            return "You received $count new XMTP message(s) but no conversations were found."
        }

        val sb = StringBuilder()
        sb.appendLine("You received $count new XMTP message(s). Here are the recent messages:")
        sb.appendLine()

        for (conv in conversations) {
            val messages = sdk.identity.getMessages(conv.id)
            // Only show the most recent messages (up to 10 per conversation)
            val recent = messages.takeLast(10)
            if (recent.isEmpty()) continue

            sb.appendLine("Conversation with ${conv.peerAddress} (id: ${conv.id}):")
            for (msg in recent) {
                val sender = if (msg.isMe) "You" else conv.peerAddress
                sb.appendLine("  [$sender]: ${msg.body}")
            }
            sb.appendLine()
        }

        sb.appendLine("Review these messages and take appropriate action. If someone sent you a message, consider replying using send_xmtp_message.")
        return sb.toString()
    }

    private fun seedHeartbeatFile() {
        val file = File(filesDir, "HEARTBEAT.md")
        file.writeText(HeartbeatInstructions.CONTENT)
        Log.i(TAG, "Seeded HEARTBEAT.md")
    }

    private fun createNotificationChannel() {
        val aiName = app.securePrefs.aiName.value
        val channel = NotificationChannel(
            CHANNEL_ID,
            "$aiName Heartbeat",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the AI heartbeat running in the background"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val aiName = app.securePrefs.aiName.value
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(aiName)
            .setContentText("Heartbeat active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
