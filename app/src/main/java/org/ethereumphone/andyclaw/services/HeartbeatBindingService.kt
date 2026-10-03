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
import android.os.Parcel
import android.os.Process
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.agent.HeartbeatAgentRunner
import org.ethereumphone.andyclaw.heartbeat.HeartbeatConfig
import org.ethereumphone.andyclaw.heartbeat.HeartbeatInstructions
import org.ethereumphone.andyclaw.heartbeat.HeartbeatTickGate
import org.ethereumphone.andyclaw.ipc.IHeartbeatService
import org.ethereumphone.andyclaw.skills.builtin.CronjobSkill
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities
import org.ethereumphone.andyclaw.telegram.TelegramAgentRunner
import org.ethereumphone.andyclaw.telegram.TelegramBotClient
import org.ethereumphone.andyclaw.telegram.TelegramOwner
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
        private const val HEARTBEAT_SEEDED_KEY = "heartbeat.seededDefaults"
        private const val HEARTBEAT_TIMEOUT_MS = 55_000L // 55s (OS typically holds 60s wake lock)
        private const val WAKE_LOCK_TAG = "AndyClaw:heartbeat"
        private const val LOW_BALANCE_CHANNEL_ID = "andyclaw_low_balance"
        private const val LOW_BALANCE_NOTIFICATION_ID = 1001
        private const val LOW_BALANCE_THRESHOLD = 5.0
        /** When the low-balance alert last went up; it goes up at most once a day. */
        private const val LOW_BALANCE_SHOWN_KEY = "lowBalance.lastShownMs"
        private const val LOW_BALANCE_INTERVAL_MS = 24L * 60 * 60 * 1000
        /** queryUpdate() only queues the fetch; this is how long the read waits for it. */
        private const val BALANCE_REFRESH_WAIT_MS = 10_000L
        /** Cron jobs and reminders kept while locked: one each, so the OS's own caps (20 + 50) bound it. */
        private const val MAX_DEFERRED_UNTIL_UNLOCK = 80
        /** Telegram messages kept while locked, in order. */
        private const val MAX_DEFERRED_MESSAGES_UNTIL_UNLOCK = 100
        /**
         * A hang guard, not a budget: the wake lock covers [HEARTBEAT_TIMEOUT_MS], the run itself
         * may take longer, but one that never ends must not hold the XMTP queue or a Telegram
         * chat's lock forever.
         */
        private const val BACKGROUND_RUN_MAX_MS = 10L * 60 * 1000

        // Binder transact constants for OS-level Telegram registration.
        // These match AndyClawHeartbeatService.java's onTransact() codes.
        private const val OS_SERVICE_NAME = "andyclawheartbeat"
        private const val OS_BINDER_DESCRIPTOR = "com.android.server.IAndyClawHeartbeat"
        private const val TRANSACTION_APP_REGISTER_TELEGRAM = android.os.IBinder.FIRST_CALL_TRANSACTION + 0
        private const val TRANSACTION_APP_UNREGISTER_TELEGRAM = android.os.IBinder.FIRST_CALL_TRANSACTION + 1
    }

    /**
     * Sends a Telegram register call to the OS system service via direct binder transact.
     * The OS service "andyclawheartbeat" is registered in ServiceManager and handles
     * TRANSACTION_APP_REGISTER_TELEGRAM in its onTransact() to start long-polling.
     */
    private fun registerTelegramWithOs(token: String) {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, OS_SERVICE_NAME) as? IBinder
            if (binder == null) {
                Log.w(TAG, "OS heartbeat binder not available, cannot register Telegram")
                return
            }
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(OS_BINDER_DESCRIPTOR)
                data.writeString(token)
                binder.transact(TRANSACTION_APP_REGISTER_TELEGRAM, data, null, IBinder.FLAG_ONEWAY)
                Log.i(TAG, "Telegram REGISTER sent to OS via binder transact")
            } finally {
                data.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Telegram with OS via binder", e)
        }
    }

    /**
     * Sends a Telegram unregister call to the OS system service via direct binder transact.
     */
    private fun unregisterTelegramWithOs() {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, OS_SERVICE_NAME) as? IBinder
            if (binder == null) {
                Log.w(TAG, "OS heartbeat binder not available, cannot unregister Telegram")
                return
            }
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(OS_BINDER_DESCRIPTOR)
                binder.transact(TRANSACTION_APP_UNREGISTER_TELEGRAM, data, null, IBinder.FLAG_ONEWAY)
                Log.i(TAG, "Telegram UNREGISTER sent to OS via binder transact")
            } finally {
                data.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister Telegram with OS via binder", e)
        }
    }

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught exception in heartbeat scope", throwable)
        }
    )

    // Written under the service's monitor (ensureRuntimeReady is @Synchronized), read from
    // binder threads and coroutines alike.
    @Volatile private var runtimeReady = false
    private var telegramPrefsObserved = false
    private var messengerSdk: MessengerSDK? = null
    // Replaced by the pref observers on a coroutine while binder threads read them.
    @Volatile private var telegramAgentRunner: TelegramAgentRunner? = null
    @Volatile private var telegramBotClient: TelegramBotClient? = null
    private val telegramChatMutexes = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()
    private val xmtpMutex = Mutex()
    private val xmtpGate = org.ethereumphone.andyclaw.heartbeat.XmtpRelayGate()

    /**
     * Work the OS delivered before the user first unlocked. Every one of these calls is oneway
     * and the OS does not redeliver, so a reminder, a cron job or a Telegram message dropped
     * here was simply gone. Bounded: a device that sits locked for days must not grow this.
     *
     * A cron job or reminder is kept once, however often it fired meanwhile, the latest firing
     * winning: the OS re-arms a cron job after every firing, so one armed at boot came back at
     * the unlock as a burst of identical runs — twelve paid runs, and twelve of its side effect,
     * for a 15-minute job three hours locked. Telegram messages are each kept, in order, in a
     * queue of their own: the OS has already consumed them, and a burst of cron firings used to
     * crowd them out of the shared one.
     */
    private val deferredUntilUnlock = LinkedHashMap<String, Pair<String, () -> Unit>>()
    private val deferredMessagesUntilUnlock = ArrayDeque<Pair<String, () -> Unit>>()
    private var unlockReceiver: android.content.BroadcastReceiver? = null

    private val binder = object : IHeartbeatService.Stub() {
        override fun heartbeatNow() {
            enforceSystemCaller()
            Log.i(TAG, "heartbeatNow() called by OS (uid=${Binder.getCallingUid()})")
            // The next tick comes on its own; one before the first unlock is not worth keeping.
            if (!ensureRuntimeReady()) {
                Log.i(TAG, "heartbeatNow: runtime not ready (locked since boot?), skipping this tick")
                return
            }
            // Also ambient ingestion's scheduled sweep — before the heartbeat's own on/off check,
            // because the cards are their own feature. Its cooldown decides whether it runs.
            (application as NodeApp).onAmbientTick()
            performHeartbeat()
        }

        override fun heartbeatNowWithXmtpMessages(senderAddress: String, messageText: String) {
            enforceSystemCaller()
            Log.i(TAG, "heartbeatNowWithXmtpMessages() called by OS (uid=${Binder.getCallingUid()}) sender=$senderAddress text=\"${messageText.take(80)}\"")
            // The messenger itself cannot run before the first unlock, so nothing is lost here
            // in practice; and a stranger's message is not worth holding on to.
            if (!ensureRuntimeReady()) {
                Log.w(TAG, "heartbeatNowWithXmtpMessages: runtime not ready, dropping message from $senderAddress")
                return
            }
            performHeartbeatWithXmtp(senderAddress, messageText)
        }

        override fun reminderFired(reminderId: Int, time: Long, message: String, label: String) {
            enforceSystemCaller()
            Log.i(TAG, "reminderFired() from OS: id=$reminderId label=$label message=\"${message.take(80)}\"")
            // The reminder was deleted OS-side the moment it fired, so this is the only chance to
            // tell the user. The notification is posted unconditionally, before anything that can
            // fail: whether the agent run happens (wallet auth, a locked device, the model's own
            // choice) used to decide whether the user heard about their reminder at all.
            try {
                ReminderReceiver.fireNotification(applicationContext, reminderId, message, label)
            } catch (e: Exception) {
                Log.e(TAG, "reminderFired: could not post the reminder notification", e)
            }
            removeStoredReminderQuietly(reminderId)
            whenRuntimeReady("reminder $reminderId", deferIfLocked = true, coalesceKey = "reminder:$reminderId") {
                removeStoredReminderQuietly(reminderId)
                performReminder(reminderId, time, message, label)
            }
        }

        override fun cronjobFired(cronjobId: Int, intervalMs: Long, reason: String, label: String) {
            enforceSystemCaller()
            Log.i(TAG, "cronjobFired() from OS: id=$cronjobId label=$label interval=${intervalMs / 60000}min reason=\"${reason.take(80)}\"")
            whenRuntimeReady("cron job $cronjobId", deferIfLocked = true, coalesceKey = "cron:$cronjobId") {
                performCronjob(cronjobId, intervalMs, reason, label)
            }
        }

        override fun telegramMessageReceived(chatId: Long, text: String, username: String?, firstName: String?) {
            enforceSystemCaller()
            Log.i(TAG, "telegramMessageReceived() from OS: chat=$chatId user=$username text=\"${text.take(80)}\"")
            // The OS polls Telegram from boot, and has already consumed the update: dropping it
            // here would lose the owner's message for good.
            whenRuntimeReady("Telegram message for chat $chatId", deferIfLocked = true) {
                performTelegramMessage(chatId, text, username, firstName)
            }
        }

        override fun notificationReceived(prompt: String) {
            enforceSystemCaller()
            Log.i(TAG, "notificationReceived() from OS: prompt=\"${prompt.take(120)}\"")
            // The settings below live in credential-encrypted storage: reading them before the
            // first unlock threw on the binder thread. A summary is refreshed by the next one.
            if (!ensureRuntimeReady()) {
                Log.i(TAG, "notificationReceived: runtime not ready, skipping")
                return
            }
            // Gate the LLM call on the user's opt-in. Previously this fired
            // unconditionally on every system notification, burning tokens
            // even when "Executive summary" was off in Settings — a silent
            // billing source. The setting now actually controls the call site.
            val prefs = (application as NodeApp).securePrefs
            if (prefs.heartbeatIntervalMinutes.value <= 0) {
                Log.i(TAG, "notificationReceived: heartbeat disabled by user — skipping LLM call")
                return
            }
            if (!prefs.executiveSummaryEnabled.value) {
                Log.i(TAG, "notificationReceived: executive summary disabled by user — skipping LLM call")
                return
            }
            Log.i(TAG, "notificationReceived: launching executive summary generation")
            serviceScope.launch {
                // Under a wake lock like every other OS-triggered run: the oneway call returns
                // at once and the device could otherwise sleep halfway through the LLM call.
                runWithWakeLock {
                    try {
                        val startMs = System.currentTimeMillis()
                        (application as NodeApp).executiveSummaryManager.generateAndStoreForNotification(prompt)
                        val elapsedMs = System.currentTimeMillis() - startMs
                        Log.i(TAG, "notificationReceived: executive summary completed in ${elapsedMs}ms")
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e(TAG, "Failed to handle notification summary update", e)
                    }
                }
            }
        }
    }

    private fun removeStoredReminderQuietly(reminderId: Int) {
        // Credential-encrypted SharedPreferences: throws before the first unlock. The deferred
        // path removes it again once the user has unlocked.
        try {
            ReminderReceiver.removeStoredReminder(applicationContext, reminderId)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove stored reminder $reminderId yet: ${e.message}")
        }
    }

    private fun isUserUnlocked(): Boolean =
        getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == true

    /**
     * Runs [action] once the runtime is ready. Before the first unlock, [deferIfLocked] work is
     * kept (bounded) and run on ACTION_USER_UNLOCKED; anything else, or anything that finds the
     * runtime failing on an unlocked device, is dropped with a log line. With a [coalesceKey]
     * only the latest such work is kept; without one, every item is kept in order.
     */
    private fun whenRuntimeReady(
        what: String,
        deferIfLocked: Boolean,
        coalesceKey: String? = null,
        action: () -> Unit,
    ) {
        if (ensureRuntimeReady()) {
            action()
            return
        }
        if (!deferIfLocked || isUserUnlocked()) {
            Log.w(TAG, "Runtime not ready; dropping $what")
            return
        }
        synchronized(deferredUntilUnlock) {
            if (coalesceKey != null) {
                val again = coalesceKey in deferredUntilUnlock
                if (!again && deferredUntilUnlock.size >= MAX_DEFERRED_UNTIL_UNLOCK) {
                    Log.w(TAG, "Locked since boot and $MAX_DEFERRED_UNTIL_UNLOCK items already waiting; dropping $what")
                    return
                }
                deferredUntilUnlock[coalesceKey] = what to action
                Log.i(TAG, "Locked since boot; keeping $what until the user unlocks" + if (again) " (replacing an earlier firing)" else "")
            } else {
                if (deferredMessagesUntilUnlock.size >= MAX_DEFERRED_MESSAGES_UNTIL_UNLOCK) {
                    Log.w(TAG, "Locked since boot and $MAX_DEFERRED_MESSAGES_UNTIL_UNLOCK messages already waiting; dropping $what")
                    return
                }
                deferredMessagesUntilUnlock.addLast(what to action)
                Log.i(TAG, "Locked since boot; keeping $what until the user unlocks")
            }
            if (unlockReceiver == null) {
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        serviceScope.launch { drainDeferredUntilUnlock() }
                    }
                }
                unlockReceiver = receiver
                androidx.core.content.ContextCompat.registerReceiver(
                    this,
                    receiver,
                    android.content.IntentFilter(Intent.ACTION_USER_UNLOCKED),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
        }
        // The unlock may have happened between the check above and the registration.
        if (isUserUnlocked()) serviceScope.launch { drainDeferredUntilUnlock() }
    }

    private fun drainDeferredUntilUnlock() {
        if (!ensureRuntimeReady()) {
            // Still locked, or unlocked but the runtime fails: keep what is queued while locked,
            // it is bounded; once unlocked there is nothing to wait for.
            if (isUserUnlocked()) {
                synchronized(deferredUntilUnlock) {
                    val waiting = deferredUntilUnlock.size + deferredMessagesUntilUnlock.size
                    if (waiting > 0) {
                        Log.w(TAG, "Runtime failed after unlock; dropping $waiting deferred item(s)")
                    }
                    deferredUntilUnlock.clear()
                    deferredMessagesUntilUnlock.clear()
                }
            }
            return
        }
        // Messages last and in their order: each one queues behind its chat's previous one.
        val work = synchronized(deferredUntilUnlock) {
            val copy = deferredUntilUnlock.values.toList() + deferredMessagesUntilUnlock.toList()
            deferredUntilUnlock.clear()
            deferredMessagesUntilUnlock.clear()
            copy
        }
        for ((what, action) in work) {
            Log.i(TAG, "Running $what, deferred until unlock")
            try {
                action()
            } catch (e: Exception) {
                Log.e(TAG, "Deferred $what failed", e)
            }
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
        // If Telegram was configured in a previous session, immediately tell the OS
        // to start polling — don't wait for the first heartbeat to fire.
        // Guard: credential-encrypted storage is unavailable before first unlock.
        val userManager = getSystemService(android.os.UserManager::class.java)
        if (userManager?.isUserUnlocked != true) {
            Log.w(TAG, "Device not yet unlocked, deferring Telegram registration")
            return
        }
        val app = application as NodeApp
        val enabled = app.securePrefs.telegramBotEnabled.value
        val token = app.securePrefs.telegramBotToken.value
        if (enabled && token.isNotBlank()) {
            registerTelegramWithOs(token)
        }
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
        // Do NOT unregister Telegram with the OS here — the OS should keep
        // polling even when the app process dies. That's the whole point of
        // OS-level Telegram polling.
        telegramAgentRunner?.clearAllHistory()
        telegramAgentRunner = null
        telegramBotClient = null
        telegramChatMutexes.clear()
        messengerSdk?.identity?.unbind()
        messengerSdk = null
        synchronized(deferredUntilUnlock) {
            unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
            unlockReceiver = null
            deferredUntilUnlock.clear()
            deferredMessagesUntilUnlock.clear()
        }
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

    /**
     * Wires the runtime once. False when it cannot be wired now.
     *
     * The service is directBootAware so the OS can bind it before the first unlock, and the OS
     * calls in on its own schedule from then on. Everything set up here (the LLM client, the
     * secure prefs, HEARTBEAT.md) lives in credential-encrypted storage. The flag used to be set
     * first: a tick before the first unlock threw halfway, the flag stayed true, and the runtime
     * kept its NoOpAgentRunner until the process died — reminders and cron jobs "ran" and did
     * nothing, and XMTP senders were answered with the literal "HEARTBEAT_OK". Now the flag is
     * set only once the setup has succeeded, and a failure is retried on the next call.
     */
    @Synchronized
    private fun ensureRuntimeReady(): Boolean {
        if (runtimeReady) return true
        if (!isUserUnlocked()) return false

        val app = application as NodeApp
        val runtime = app.runtime
        try {
            runtime.nativeSkillRegistry = app.nativeSkillRegistry
            runtime.llmClient = app.getLlmClient()
            runtime.agentRunner = HeartbeatAgentRunner(app, app.heartbeatLogStore)

            runtime.heartbeatConfig = heartbeatConfig(app)

            seedHeartbeatFile()
            runtime.initialize()
        } catch (e: Exception) {
            Log.e(TAG, "Runtime setup failed; will retry on the next call", e)
            return false
        }
        runtimeReady = true

        // Telegram is its own feature: a failure here must not take the heartbeat with it, and
        // the observers must be started once however often this is reached.
        try {
            startTelegramBot()
            if (!telegramPrefsObserved) {
                telegramPrefsObserved = true
                observeTelegramPrefs()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Telegram setup failed", e)
        }

        Log.i(TAG, "Runtime initialized for OS-triggered heartbeat")
        // Anything the OS delivered while the device was still locked.
        val hasDeferred = synchronized(deferredUntilUnlock) {
            deferredUntilUnlock.isNotEmpty() || deferredMessagesUntilUnlock.isNotEmpty()
        }
        if (hasDeferred) serviceScope.launch { drainDeferredUntilUnlock() }
        return true
    }

    private fun startTelegramBot() {
        val app = application as NodeApp
        val enabled = app.securePrefs.telegramBotEnabled.value
        val token = app.securePrefs.telegramBotToken.value
        if (!enabled || token.isBlank()) {
            Log.d(TAG, "Telegram bot not enabled or no token configured")
            return
        }

        // Create client first, then runner (runner needs the client for approval buttons)
        telegramBotClient = TelegramBotClient(token = { app.securePrefs.telegramBotToken.value })
        telegramAgentRunner = newTelegramRunner(telegramBotClient!!)

        // Tell the OS system service to start polling via direct binder transact
        registerTelegramWithOs(token)
    }

    private fun observeTelegramPrefs() {
        val app = application as NodeApp
        // startTelegramBot() has just acted on the settings as they are now. Each collector's first
        // value is those same settings, and acting on it again built two more runners at once (one
        // after clearAllHistory), so the first messages after a restart were answered without the
        // context of the one before. Only a change is acted on.
        val enabledAtStart = app.securePrefs.telegramBotEnabled.value
        val tokenAtStart = app.securePrefs.telegramBotToken.value

        serviceScope.launch {
            app.securePrefs.telegramBotEnabled.dropWhile { it == enabledAtStart }.collect { enabled ->
                val token = app.securePrefs.telegramBotToken.value
                if (enabled && token.isNotBlank()) {
                    telegramBotClient = TelegramBotClient(token = { app.securePrefs.telegramBotToken.value })
                    telegramAgentRunner = newTelegramRunner(telegramBotClient!!)
                    registerTelegramWithOs(token)
                } else {
                    unregisterTelegramWithOs()
                    telegramAgentRunner?.clearAllHistory()
                    telegramAgentRunner = null
                    telegramBotClient = null
                    telegramChatMutexes.clear()
                }
            }
        }

        serviceScope.launch {
            app.securePrefs.telegramBotToken.dropWhile { it == tokenAtStart }.collect { token ->
                val enabled = app.securePrefs.telegramBotEnabled.value
                if (enabled && token.isNotBlank()) {
                    telegramAgentRunner?.clearAllHistory()
                    telegramBotClient = TelegramBotClient(token = { app.securePrefs.telegramBotToken.value })
                    telegramAgentRunner = newTelegramRunner(telegramBotClient!!)
                    registerTelegramWithOs(token)
                } else if (token.isBlank()) {
                    unregisterTelegramWithOs()
                    telegramAgentRunner?.clearAllHistory()
                    telegramAgentRunner = null
                    telegramBotClient = null
                    telegramChatMutexes.clear()
                }
            }
        }
    }

    private fun performHeartbeat() {
        if ((application as NodeApp).securePrefs.heartbeatIntervalMinutes.value <= 0) {
            Log.i(TAG, "performHeartbeat: heartbeat disabled by user, skipping")
            return
        }
        if (!isWalletAuthReady()) {
            Log.i(TAG, "performHeartbeat: wallet auth not ready, skipping")
            return
        }
        // The OS ticks at most hourly (it also drives the ambient sweep, above); an interval of
        // more than an hour is the app's to honour (SET-02). Only this scheduled run is gated.
        val prefs = (application as NodeApp).securePrefs
        val interval = prefs.heartbeatIntervalMinutes.value
        val now = System.currentTimeMillis()
        val last = prefs.getString(HeartbeatTickGate.PREF_LAST_SCHEDULED_RUN_MS)?.toLongOrNull() ?: 0L
        if (!HeartbeatTickGate.shouldRun(now, last, interval)) {
            Log.i(TAG, "performHeartbeat: ${(now - last) / 60_000} of $interval minutes since the last run, skipping this tick")
            // The balance check has always ridden on the tick, not on the run.
            serviceScope.launch { checkPaymasterBalance() }
            return
        }
        prefs.putString(HeartbeatTickGate.PREF_LAST_SCHEDULED_RUN_MS, now.toString())
        Log.i(TAG, "performHeartbeat: starting")
        // Its own coroutine: the balance read waits for the refresh, and the run must not.
        serviceScope.launch { checkPaymasterBalance() }
        serviceScope.launch {
            runWithWakeLock {
                val app = application as NodeApp
                // Each tick reads the settings as they are now: a toggle changed since the
                // runtime started must reach the runner that actually runs. The heartbeat may
                // have been switched on since then too, so the starter list is checked here.
                seedHeartbeatFile()
                app.runtime.heartbeatConfig = heartbeatConfig(app)
                Log.i(TAG, "performHeartbeat: running the heartbeat")
                // Awaited, so the wake lock covers the run itself and not just its launch.
                val result = app.runtime.runHeartbeatNow()
                Log.i(TAG, "performHeartbeat: ${result?.outcome ?: "folded into a run already going"}")
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
                    appendLine(org.ethereumphone.andyclaw.agent.BackgroundTrigger.REMINDER.header)
                    appendLine()
                    appendLine("A reminder that the user previously asked you to set has now triggered.")
                    appendLine("- Label: $label")
                    appendLine("- Message: $message")
                    appendLine("- Reminder ID: $reminderId")
                    appendLine("- Scheduled time: $time (epoch ms)")
                    appendLine("- Current time: ${System.currentTimeMillis()} (epoch ms)")
                    appendLine()
                    appendLine("The user has already been shown a notification with this label and")
                    appendLine("message, so do not create another one just to alert them.")
                    appendLine("If the user asked you to do something (e.g. check battery, look")
                    appendLine("something up, send a message), do it now using your available tools.")
                }
                // A reminder runs with the authority of whoever set it, and no more: one a
                // stranger's message created stays untrusted when it fires.
                // It is also event-driven: something fired, the agent ran, and the next
                // scheduled tick has nothing to add.
                val key = org.ethereumphone.andyclaw.safety.TriggerProvenanceStore.reminderKey(reminderId)
                val provenance = app.triggerProvenanceStore.firedProvenanceFor(key)
                app.triggerProvenanceStore.forget(key) // one-shot
                val response = app.runtime.agentRunner.run(prompt, provenance = provenance)
                Log.i(TAG, "Reminder agent response (error=${response.isError}): " +
                        "\"${response.text.take(100)}\"")
            }
        }
    }

    /**
     * Runs the AI agent when a recurring cron job fires.
     * The agent receives the reason and can use all tools to act on it.
     * Unlike reminders, cron jobs keep recurring — the OS re-schedules automatically.
     */
    private fun performCronjob(cronjobId: Int, intervalMs: Long, reason: String, label: String) {
        if (!isWalletAuthReady()) return
        serviceScope.launch {
            runWithWakeLock {
                val app = application as NodeApp
                val prompt = buildString {
                    appendLine(org.ethereumphone.andyclaw.agent.BackgroundTrigger.CRON.header)
                    appendLine()
                    appendLine("A recurring cron job has triggered. This job runs automatically at a fixed interval.")
                    appendLine("- Label: $label")
                    appendLine("- Reason: $reason")
                    appendLine("- Cron Job ID: $cronjobId")
                    appendLine("- Interval: ${intervalMs / 60000} minutes")
                    appendLine("- Current time: ${System.currentTimeMillis()} (epoch ms)")
                    appendLine()
                    appendLine("Execute the task described in the reason above. Use your available tools")
                    appendLine("as needed. This cron job will fire again in ${intervalMs / 60000} minutes.")
                }
                // A cron job runs with the authority of whoever created it, and no more — see
                // TriggerProvenanceStore for the job it used to take for "every 30 minutes,
                // send 0.05 ETH to 0x…" from a stranger.
                val provenance = app.triggerProvenanceStore.firedProvenanceFor(
                    org.ethereumphone.andyclaw.safety.TriggerProvenanceStore.cronKey(cronjobId)
                )
                val response = app.runtime.agentRunner.run(prompt, provenance = provenance)
                Log.i(TAG, "Cronjob agent response (error=${response.isError}): " +
                        "\"${response.text.take(100)}\"")
            }
        }
    }

    /**
     * Processes an incoming Telegram message relayed from the OS system service.
     * Records the chat, handles commands (/start, /clear), and runs the agent
     * for regular messages with per-chat serialization.
     */
    private fun performTelegramMessage(chatId: Long, text: String, username: String?, firstName: String?) {
        if (!isWalletAuthReady()) return
        val app = application as NodeApp

        // Lazily initialize runner/client — the OS may deliver messages before
        // the first heartbeat fires (which normally calls startTelegramBot()).
        // Under a lock: two messages arriving together each built their own runner, and the
        // one that lost kept a private history nobody else saw.
        val (runner, client) = synchronized(this) {
            val c = telegramBotClient
                ?: TelegramBotClient(token = { app.securePrefs.telegramBotToken.value })
                    .also { telegramBotClient = it }
            val r = telegramAgentRunner ?: newTelegramRunner(c).also { telegramAgentRunner = it }
            r to c
        }

        app.telegramChatStore.record(chatId, username, firstName)

        // One at a time per chat, in the order the OS delivered them: the chat's lock is asked for
        // here, before this call returns (UNDISPATCHED), and the mutex hands it out first come,
        // first served. Asked for from two worker threads, the second message could win, and
        // "no, cancel that" ran before the "send 0.1 ETH to Bob" it was about.
        val mutex = telegramChatMutexes.getOrPut(chatId) { Mutex() }
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                // A wake lock, like the other OS-triggered runs: the oneway call has already returned.
                // Joined, so the next message waits for this one's run, not only for its start.
                runWithWakeLock { handleTelegramMessage(app, runner, client, chatId, text) }.join()
            }
        }
    }

    private suspend fun handleTelegramMessage(
        app: NodeApp,
        runner: TelegramAgentRunner,
        client: TelegramBotClient,
        chatId: Long,
        text: String,
    ) {
        try {
            // Handle /start command
            if (text.startsWith("/start")) {
                val aiName = app.userStoryManager.getAiName() ?: "AndyClaw"
                client.sendMessage(chatId, "Hello! I'm $aiName. How can I help you?")
                return
            }

            // Handle /clear command
            if (text == "/clear") {
                runner.clearHistory(chatId)
                client.sendMessage(chatId, "Conversation history cleared.")
                return
            }

            // Anyone but the owner writing in a loop must not become a stream of paid runs.
            // The owner is the chat verified at setup, not whichever chat wrote first.
            if (!TelegramOwner.isOwner(app.securePrefs.telegramOwnerChatId.value, chatId) &&
                !app.triggerBudget.tryAcquire("telegram:$chatId")
            ) {
                Log.w(TAG, "Telegram chat $chatId is over its message budget; not running the agent")
                return
            }

            // Regular messages: already one at a time per chat (performTelegramMessage).
            client.sendChatAction(chatId)
            val response = runner.run(chatId, text)
            if (response.isNotBlank()) {
                client.sendMessage(chatId, response)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process Telegram message for chat $chatId", e)
            try {
                client.sendMessage(chatId, "Sorry, something went wrong processing your message.")
            } catch (_: Exception) {}
        }
    }

    /**
     * The OS polls Telegram with allowed_updates=["message"], so an inline Approve/Decline press
     * never reaches this app: every approval the runner asked for sat three minutes holding the
     * chat's lock and then declined itself. On this path the call is queued for the phone instead.
     */
    private fun newTelegramRunner(client: TelegramBotClient) =
        TelegramAgentRunner(application as NodeApp, client, inlineApprovals = false)

    private fun performHeartbeatWithXmtp(senderAddress: String, messageText: String) {
        if ((application as NodeApp).securePrefs.heartbeatIntervalMinutes.value <= 0) {
            Log.i(TAG, "performHeartbeatWithXmtp: heartbeat disabled by user, skipping")
            return
        }
        if (!isWalletAuthReady()) return
        // Admitted before the budget is asked: a relayed duplicate or a message the full queue
        // would drop must not spend the sender's runs.
        when (xmtpGate.admit(senderAddress, messageText)) {
            org.ethereumphone.andyclaw.heartbeat.XmtpRelayGate.Admission.DUPLICATE -> {
                Log.i(TAG, "performHeartbeatWithXmtp: same message from $senderAddress relayed again, skipping")
                return
            }
            org.ethereumphone.andyclaw.heartbeat.XmtpRelayGate.Admission.QUEUE_FULL -> {
                Log.w(TAG, "performHeartbeatWithXmtp: XMTP queue full, dropping message from $senderAddress")
                return
            }
            org.ethereumphone.andyclaw.heartbeat.XmtpRelayGate.Admission.ADMITTED -> Unit
        }
        // A stranger writing in a loop must not become a stream of paid runs.
        if (!(application as NodeApp).triggerBudget.tryAcquire("xmtp:${senderAddress.lowercase()}")) {
            xmtpGate.release()
            Log.w(TAG, "performHeartbeatWithXmtp: $senderAddress is over its message budget; not running the agent")
            return
        }
        // UNDISPATCHED: the lock is asked for before this call returns, so "in order" is the order
        // the OS delivered them in, not whichever worker thread reached the mutex first.
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // One at a time, in order. This was tryLock(), meant to drop a relayed duplicate, and it
            // dropped every message that arrived while another was being answered — after the
            // budget had been spent on it. Duplicates are now caught by xmtpGate instead.
            try {
                xmtpMutex.withLock {
                    // Held until the run ends, not until the wake lock is let go: the next
                    // message must not start while this one is still being answered.
                    runWithWakeLock { handleXmtpMessage(senderAddress, messageText) }.join()
                }
            } finally {
                xmtpGate.release()
            }
        }
    }

    /**
     * Handles a single incoming XMTP message (text passed from Messenger via OS relay).
     * Connects to MessengerSDK first to fetch conversation history, then runs the agent
     * with the message + context as a prompt, and sends the response back to the sender.
     */
    private suspend fun handleXmtpMessage(senderAddress: String, messageText: String) {
        // Never on the NoOpAgentRunner: it answers every sender with "HEARTBEAT_OK".
        if (!ensureRuntimeReady()) return
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
            appendLine(org.ethereumphone.andyclaw.agent.BackgroundTrigger.XMTP.header)
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

        // Step 4: Run agent.
        // The prompt is built from a stranger's message body plus four more of their
        // messages, so the whole run is UNTRUSTED and is confined to replying to
        // this sender.
        Log.i(TAG, "XMTP: running agent for $senderAddress (UNTRUSTED)...")
        val response = app.runtime.agentRunner.run(
            prompt = prompt,
            provenance = Provenance.UNTRUSTED,
            conversationId = senderAddress,
        )
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

        if (messages.isEmpty()) {
            return@withContext emptyList()
        }

        // Take the last 4 messages as conversation history context
        val history = messages.takeLast(4)

        history.map { msg ->
            val sender = if (msg.isMe) "You" else peerAddress
            "[$sender]: \"${msg.body}\""
        }
    }

    /**
     * Runs [block] under a wake lock and returns the job running it.
     *
     * The wake lock is bounded, the work is not: it runs in [serviceScope] and outlives the wait.
     * This used to be `withTimeoutOrNull(55 s) { block() }`, which cancelled the run itself — an
     * XMTP reply or a cron task was cut off mid-way, after the model had been paid for, with no
     * answer sent. The same shape as [org.ethereumphone.andyclaw.NodeRuntime.runHeartbeatNow].
     * Callers that must not overlap the next run join the returned job.
     */
    private suspend fun runWithWakeLock(block: suspend () -> Unit): Job {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG,
        ).apply { setReferenceCounted(false) }

        val work = serviceScope.launch {
            try {
                val finished = withTimeoutOrNull(BACKGROUND_RUN_MAX_MS) { block(); true }
                if (finished == null) Log.w(TAG, "Background run gave up after ${BACKGROUND_RUN_MAX_MS}ms")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error during background run", e)
            }
        }
        try {
            wakeLock.acquire(HEARTBEAT_TIMEOUT_MS + 5000)
            Log.i(TAG, "Wake lock acquired, running heartbeat...")

            val result = withTimeoutOrNull(HEARTBEAT_TIMEOUT_MS) { work.join() }

            if (result == null) {
                Log.w(TAG, "Still running after ${HEARTBEAT_TIMEOUT_MS}ms; releasing the wake lock, the run carries on")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during heartbeat", e)
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.i(TAG, "Wake lock released")
            }
        }
        return work
    }

    /**
     * Queries the ethOS paymaster system service for the current gas balance.
     * If the balance is below $[LOW_BALANCE_THRESHOLD], pushes a notification
     * that deep-links to WalletManager's gas top-up screen.
     */
    @SuppressLint("WrongConstant")
    private suspend fun checkPaymasterBalance() {
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

            // Query backend for fresh balance, then read it. queryUpdate() only queues the fetch,
            // so read once it has had time to land: read at once, it was the last tick's balance,
            // and a top-up was followed by one more "balance low".
            val queryUpdateMethod = proxyClass.getMethod("queryUpdate")
            queryUpdateMethod.invoke(proxy)
            delay(BALANCE_REFRESH_WAIT_MS)

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
            } else {
                clearLowBalanceAlert()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to check paymaster balance", e)
        }
    }

    /** Topped up: the alert's figure is wrong now, and the next drop below may alert at once. */
    private fun clearLowBalanceAlert() {
        val prefs = (application as NodeApp).securePrefs
        if (prefs.getString(LOW_BALANCE_SHOWN_KEY) == null) return
        prefs.remove(LOW_BALANCE_SHOWN_KEY)
        getSystemService(NotificationManager::class.java)?.cancel(LOW_BALANCE_NOTIFICATION_ID)
    }

    private fun showLowBalanceNotification(balance: Double) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // Once a day at most, like the OS's own warning. The check rides on every heartbeat tick,
        // and this high-importance alert sounded every 5–60 minutes, day and night.
        val prefs = (application as NodeApp).securePrefs
        val now = System.currentTimeMillis()
        val lastShown = prefs.getString(LOW_BALANCE_SHOWN_KEY)?.toLongOrNull() ?: 0L
        if (now - lastShown in 0 until LOW_BALANCE_INTERVAL_MS) {
            Log.d(TAG, "Low balance alert shown ${(now - lastShown) / 60_000} min ago; not again yet")
            return
        }

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

        val topUpIntent = Intent().apply {
            setClassName("io.freedomfactory.paymaster", "io.freedomfactory.paymaster.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
            .setOnlyAlertOnce(true)
            .build()

        manager.notify(LOW_BALANCE_NOTIFICATION_ID, notification)
        prefs.putString(LOW_BALANCE_SHOWN_KEY, now.toString())
        Log.i(TAG, "Low balance notification shown (balance=$${"%.2f".format(balance)})")
    }

    /** The heartbeat's configuration from the settings as they are right now. */
    private fun heartbeatConfig(app: NodeApp) = HeartbeatConfig(
        heartbeatFilePath = File(filesDir, "HEARTBEAT.md").absolutePath,
        backstopQuietMs = app.heartbeatBackstopQuietMs,
    )

    private fun seedHeartbeatFile() {
        val file = File(filesDir, "HEARTBEAT.md")
        val prefs = (application as NodeApp).securePrefs
        if (!file.exists()) {
            file.writeText(HeartbeatInstructions.CONTENT)
            Log.i(TAG, "Seeded HEARTBEAT.md")
        } else if (file.readText().contains("Gather fresh info the user might care about")) {
            file.writeText(HeartbeatInstructions.CONTENT)
            Log.i(TAG, "Migrated HEARTBEAT.md: removed legacy proactive instructions")
        }
        // Phones onboarded before onboarding wrote the starter list carry a header and nothing
        // else, so with the heartbeat switched on every tick skipped as EMPTY_HEARTBEAT_FILE. The
        // first time the heartbeat is found on, a list that is still empty gets the starter tasks
        // — once: a list the user empties afterwards stays empty.
        if (prefs.heartbeatIntervalMinutes.value > 0 && prefs.getString(HEARTBEAT_SEEDED_KEY) != "true") {
            HeartbeatInstructions.seedStarterTasks(file)
            prefs.putString(HEARTBEAT_SEEDED_KEY, "true")
        }
    }
}
