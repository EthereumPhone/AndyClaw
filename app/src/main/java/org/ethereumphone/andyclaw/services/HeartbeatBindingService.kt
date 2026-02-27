package org.ethereumphone.andyclaw.services

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.agent.HeartbeatAgentRunner
import org.ethereumphone.andyclaw.heartbeat.HeartbeatConfig
import org.ethereumphone.andyclaw.heartbeat.HeartbeatInstructions
import org.ethereumphone.andyclaw.ipc.IHeartbeatService
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities
import org.ethereumhpone.messengersdk.MessengerSDK

/**
 * Bound service for OS-level heartbeat triggering.
 *
 * On ethOS, the OS binds to this service and calls [heartbeatNow] periodically.
 * When called, the service ensures the runtime is initialized, acquires a wake lock,
 * and runs a single heartbeat cycle through the AI agent loop.
 */
class HeartbeatBindingService : Service() {

    companion object {
        private const val TAG = "HeartbeatBindingService"
        private const val HEARTBEAT_TIMEOUT_MS = 55_000L // 55s (OS typically holds 60s wake lock)
        private const val WAKE_LOCK_TAG = "AndyClaw:heartbeat"
        private const val LOW_BALANCE_CHANNEL_ID = "andyclaw_low_balance"
        private const val LOW_BALANCE_NOTIFICATION_ID = 1001
        private const val LOW_BALANCE_THRESHOLD = 5.0
    }

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught exception in heartbeat scope", throwable)
        }
    )

    private var runtimeReady = false
    private var messengerSdk: MessengerSDK? = null
    private val xmtpMutex = Mutex()

    private val binder = object : IHeartbeatService.Stub() {
        override fun heartbeatNow() {
            enforceSystemCaller()
            Log.i(TAG, "heartbeatNow() called by OS (uid=${Binder.getCallingUid()})")
            ensureRuntimeReady()
            performHeartbeat()
        }

        override fun heartbeatNowWithXmtpMessages(senderAddress: String, messageText: String) {
            enforceSystemCaller()
            Log.i(TAG, "heartbeatNowWithXmtpMessages() called by OS (uid=${Binder.getCallingUid()}) sender=$senderAddress text=\"${messageText.take(80)}\"")
            ensureRuntimeReady()
            performHeartbeatWithXmtp(senderAddress, messageText)
        }

        override fun reminderFired(reminderId: Int, time: Long, message: String, label: String) {
            enforceSystemCaller()
            Log.i(TAG, "reminderFired() from OS: id=$reminderId label=$label message=\"${message.take(80)}\"")
            ensureRuntimeReady()
            ReminderReceiver.removeStoredReminder(applicationContext, reminderId)
            performReminder(reminderId, time, message, label)
        }
    }

    private fun enforceSystemCaller() {
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.SYSTEM_UID) {
            Log.w(TAG, "Rejected heartbeatNow() from non-system caller (uid=$callingUid)")
            throw SecurityException(
                "Only the OS may call heartbeatNow(). Caller UID $callingUid is not SYSTEM_UID."
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "HeartbeatBindingService created")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind() - returning AIDL binder")
        // Do NOT initialize runtime here — keep onBind() lightweight so the system
        // can bind successfully even during direct boot or early startup.
        // Runtime init is deferred to the actual binder method calls.
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind()")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "HeartbeatBindingService destroyed")
        messengerSdk?.identity?.unbind()
        messengerSdk = null
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * On privileged (ethOS) devices the TinfoilProxyClient needs wallet auth.
     * If the user hasn't signed in yet, skip the heartbeat silently.
     */
    private fun isWalletAuthReady(): Boolean {
        if (!OsCapabilities.hasPrivilegedAccess) return true
        val app = application as NodeApp
        if (!app.securePrefs.walletSignature.value.startsWith("0x")) {
            Log.w(TAG, "Skipping heartbeat: wallet signature missing or invalid")
            return false
        }
        return true
    }

    private fun ensureRuntimeReady() {
        if (runtimeReady) return
        runtimeReady = true

        val app = application as NodeApp
        val runtime = app.runtime

        runtime.nativeSkillRegistry = app.nativeSkillRegistry
        runtime.anthropicClient = app.anthropicClient
        runtime.agentRunner = HeartbeatAgentRunner(app, app.heartbeatLogStore)

        runtime.heartbeatConfig = HeartbeatConfig(
            heartbeatFilePath = File(filesDir, "HEARTBEAT.md").absolutePath,
        )

        seedHeartbeatFile()
        runtime.initialize()

        Log.i(TAG, "Runtime initialized for OS-triggered heartbeat")
    }

    private fun performHeartbeat() {
        if (!isWalletAuthReady()) return
        serviceScope.launch {
            checkPaymasterBalance()
            runWithWakeLock {
                (application as NodeApp).runtime.requestHeartbeatNow()
            }
        }
    }

    /**
     * Runs the AI agent with the fired reminder as context.
     * The agent decides what to do: check device info, send a message, show a
     * notification, or anything else it has tool access for.
     */
    private fun performReminder(reminderId: Int, time: Long, message: String, label: String) {
        if (!isWalletAuthReady()) return
        serviceScope.launch {
            runWithWakeLock {
                val app = application as NodeApp
                val prompt = buildString {
                    appendLine("## Reminder Fired")
                    appendLine()
                    appendLine("A reminder that the user previously asked you to set has now triggered.")
                    appendLine("- Label: $label")
                    appendLine("- Message: $message")
                    appendLine("- Reminder ID: $reminderId")
                    appendLine("- Scheduled time: $time (epoch ms)")
                    appendLine("- Current time: ${System.currentTimeMillis()} (epoch ms)")
                    appendLine()
                    appendLine("Act on this reminder now. If the user asked you to do something")
                    appendLine("(e.g. check battery, look something up, send a message), do it using")
                    appendLine("your available tools. If it's a simple reminder to alert the user,")
                    appendLine("create a notification so they see it.")
                }
                val response = app.runtime.agentRunner.run(prompt)
                Log.i(TAG, "Reminder agent response (error=${response.isError}): " +
                        "\"${response.text.take(100)}\"")
            }
        }
    }

    private fun performHeartbeatWithXmtp(senderAddress: String, messageText: String) {
        if (!isWalletAuthReady()) return
        serviceScope.launch {
            // Prevent duplicate processing (OS may relay the same event twice)
            if (!xmtpMutex.tryLock()) {
                Log.i(TAG, "XMTP handling already in progress, skipping duplicate")
                return@launch
            }
            try {
                runWithWakeLock {
                    handleXmtpMessage(senderAddress, messageText)
                }
            } finally {
                xmtpMutex.unlock()
            }
        }
    }

    /**
     * Handles a single incoming XMTP message (text passed from Messenger via OS relay).
     * Connects to MessengerSDK first to fetch conversation history, then runs the agent
     * with the message + context as a prompt, and sends the response back to the sender.
     */
    private suspend fun handleXmtpMessage(senderAddress: String, messageText: String) {
        ensureRuntimeReady()
        val app = application as NodeApp

        Log.i(TAG, "XMTP: handling message from $senderAddress: \"${messageText.take(80)}\"")

        // Step 1: Connect to MessengerSDK (needed for both history fetch and reply)
        val sdk = try {
            if (messengerSdk == null) {
                messengerSdk = MessengerSDK.getInstance(this@HeartbeatBindingService)
            }
            val s = messengerSdk!!
            withContext(Dispatchers.IO) {
                s.identity.bind()
                s.identity.awaitConnected()
            }
            Log.i(TAG, "XMTP: SDK connected")
            s
        } catch (e: Exception) {
            Log.w(TAG, "XMTP: failed to connect SDK, proceeding without history", e)
            null
        }

        // Step 2: Fetch conversation history for context
        val historyLines = if (sdk != null) {
            try {
                fetchConversationHistory(sdk, senderAddress)
            } catch (e: Exception) {
                Log.w(TAG, "XMTP: failed to fetch conversation history", e)
                emptyList()
            }
        } else {
            emptyList()
        }

        // Step 3: Build prompt with history context
        val prompt = buildString {
            appendLine("## New incoming XMTP message")
            appendLine()
            appendLine("From: $senderAddress")
            appendLine("Message: \"$messageText\"")

            if (historyLines.isNotEmpty()) {
                appendLine()
                appendLine("## Previous conversation history (for context)")
                appendLine()
                for (line in historyLines) {
                    appendLine(line)
                }
            }

            appendLine()
            appendLine("---")
            appendLine("Reply to the new message above. Use the conversation history for context.")
            appendLine("Do NOT use send_xmtp_message — your response will be sent automatically.")
        }

        // Step 4: Run agent
        Log.i(TAG, "XMTP: running agent for $senderAddress...")
        val response = app.runtime.agentRunner.run(prompt)
        Log.i(TAG, "XMTP: agent response (error=${response.isError}): \"${response.text.take(100)}\"")

        if (response.isError) {
            Log.w(TAG, "XMTP: agent error for $senderAddress: ${response.text}")
            return
        }

        if (response.text.isBlank()) {
            Log.w(TAG, "XMTP: agent returned blank response for $senderAddress")
            return
        }

        // Step 5: Send reply via MessengerSDK
        if (sdk != null) {
            try {
                withContext(Dispatchers.IO) {
                    sdk.identity.sendMessage(senderAddress, response.text)
                }
                Log.i(TAG, "XMTP: sent reply to $senderAddress")
            } catch (e: Exception) {
                Log.e(TAG, "XMTP: failed to send reply to $senderAddress", e)
            }
        } else {
            Log.e(TAG, "XMTP: cannot send reply — SDK not connected")
        }
    }

    /**
     * Fetches the last 4 messages (before the newest) from the conversation with [peerAddress].
     * Returns formatted lines like `[sender]: "message text"` or `[You]: "message text"`.
     */
    private suspend fun fetchConversationHistory(
        sdk: MessengerSDK,
        peerAddress: String,
    ): List<String> = withContext(Dispatchers.IO) {
        sdk.identity.syncConversations()
        val conversations = sdk.identity.getConversations()
        val conversation = conversations.find {
            it.peerAddress.equals(peerAddress, ignoreCase = true)
        }

        if (conversation == null) {
            Log.w(TAG, "XMTP: no conversation found for $peerAddress")
            return@withContext emptyList()
        }

        val messages = sdk.identity.getMessages(conversation.id)
        Log.i(TAG, "XMTP: fetched ${messages.size} messages for conversation with $peerAddress")

        if (messages.size <= 1) {
            return@withContext emptyList()
        }

        // Take the 4 messages before the newest one (the newest is the just-arrived message)
        val history = messages.dropLast(1).takeLast(4)

        history.map { msg ->
            val sender = if (msg.isMe) "You" else peerAddress
            "[$sender]: \"${msg.body}\""
        }
    }

    private suspend fun runWithWakeLock(block: suspend () -> Unit) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG,
        ).apply { setReferenceCounted(false) }

        try {
            wakeLock.acquire(HEARTBEAT_TIMEOUT_MS + 5000)
            Log.i(TAG, "Wake lock acquired, running heartbeat...")

            val result = withTimeoutOrNull(HEARTBEAT_TIMEOUT_MS) {
                block()
            }

            if (result == null) {
                Log.w(TAG, "Heartbeat timed out after ${HEARTBEAT_TIMEOUT_MS}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during heartbeat", e)
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.i(TAG, "Wake lock released")
            }
        }
    }

    /**
     * Queries the ethOS paymaster system service for the current gas balance.
     * If the balance is below $[LOW_BALANCE_THRESHOLD], pushes a notification
     * that deep-links to WalletManager's gas top-up screen.
     */
    @SuppressLint("WrongConstant")
    private fun checkPaymasterBalance() {
        try {
            val proxy = getSystemService("paymaster")
            if (proxy == null) {
                Log.w(TAG, "Paymaster system service not available")
                return
            }

            val proxyClass = Class.forName("android.os.PaymasterProxy")
            if (!proxyClass.isInstance(proxy)) {
                Log.w(TAG, "Paymaster service returned unexpected type: ${proxy.javaClass.name}")
                return
            }

            // Query backend for fresh balance, then read it
            val queryUpdateMethod = proxyClass.getMethod("queryUpdate")
            queryUpdateMethod.invoke(proxy)

            val getBalanceMethod = proxyClass.getMethod("getBalance")
            val balanceStr = getBalanceMethod.invoke(proxy) as? String

            if (balanceStr == null) {
                Log.w(TAG, "Paymaster getBalance() returned null")
                return
            }

            val balance = balanceStr.toDoubleOrNull()
            if (balance == null) {
                Log.w(TAG, "Paymaster balance not parseable: \"$balanceStr\"")
                return
            }

            Log.i(TAG, "Paymaster balance: $${"%.2f".format(balance)}")

            if (balance < LOW_BALANCE_THRESHOLD) {
                showLowBalanceNotification(balance)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check paymaster balance", e)
        }
    }

    private fun showLowBalanceNotification(balance: Double) {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        // Ensure notification channel exists
        if (manager.getNotificationChannel(LOW_BALANCE_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                LOW_BALANCE_CHANNEL_ID,
                "Low Gas Balance",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts when your gas account balance is low"
            }
            manager.createNotificationChannel(channel)
        }

        // Deep-link to WalletManager gas top-up screen (same intent as ChatScreen)
        val topUpIntent = Intent("org.ethereumphone.walletmanager.ACTION_OPEN_GAS").apply {
            setPackage("org.ethereumphone.walletmanager")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            LOW_BALANCE_NOTIFICATION_ID,
            topUpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, LOW_BALANCE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Gas Balance Low")
            .setContentText("Your balance is $${"%.2f".format(balance)}. Tap to top up.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(LOW_BALANCE_NOTIFICATION_ID, notification)
        Log.i(TAG, "Low balance notification shown (balance=$${"%.2f".format(balance)})")
    }

    private fun seedHeartbeatFile() {
        val file = File(filesDir, "HEARTBEAT.md")
        file.writeText(HeartbeatInstructions.CONTENT)
        Log.i(TAG, "Seeded HEARTBEAT.md")
    }
}
