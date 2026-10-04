package org.ethereumphone.andyclaw.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.IAgentDisplayService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import org.ethereumphone.andyclaw.BuildConfig
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.frames.SessionFrameStore
import org.ethereumphone.andyclaw.ledger.LedgerAction
import org.ethereumphone.andyclaw.ledger.LedgerDraft
import org.ethereumphone.andyclaw.ledger.LedgerKind
import org.ethereumphone.andyclaw.ledger.LedgerOutcome
import org.ethereumphone.andyclaw.agent.AgentLoop
import org.ethereumphone.andyclaw.ipc.IExecSummaryCallback
import org.ethereumphone.andyclaw.ipc.ILauncherCallback
import org.ethereumphone.andyclaw.ipc.ILauncherService
import org.ethereumphone.andyclaw.summary.ExecutiveSummaryManager
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.LlmProvider
import org.ethereumphone.andyclaw.skills.RoutingPreset
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities
import org.ethereumphone.andyclaw.PaymasterSDK
import org.ethereumphone.andyclaw.ui.chat.ToolResultFormatter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Parcel
import kotlinx.coroutines.flow.first

/**
 * Bound service that the ethOS Launcher binds to for dGENT tab functionality.
 *
 * Provides:
 * - Setup status check
 * - AI name retrieval
 * - Prompt processing with streaming token delivery via [ILauncherCallback]
 * - Audio transcription via Whisper
 * - Multi-turn conversation support via session IDs
 */
class LauncherBindingService : Service() {

    companion object {
        private const val TAG = "LauncherBindingService"

        /** The agent wallet address as last verified, for answering offline. */
        private const val AGENT_WALLET_CACHE_KEY = "agent.wallet.cachedAddress"

        /**
         * Callers let in by name alone: `agentbench/`'s client, which drives this service over the
         * launcher's own contract on an emulator. Debug builds only: the shipped APK is a release
         * build, where R8 folds this away and every caller has to pass [CallerPolicy] in full.
         */
        private val DEBUG_CALLER_PACKAGES =
            if (BuildConfig.DEBUG) setOf(AGENTBENCH_CLIENT_PACKAGE) else emptySet()

        private const val AGENTBENCH_CLIENT_PACKAGE = "org.ethereumphone.andyclaw.agentbench"

        /**
         * How often a frame is pulled off the agent display.
         *
         * One a second, which is what this loop has always done. It is now also the replay
         * frame rate and the unit the per-session cap counts in, so it is a named constant
         * rather than a literal inside the loop.
         */
        private const val DISPLAY_FRAME_INTERVAL_MS = FrameStreamPolicy.IDLE_INTERVAL_MS
        /** How far past the rows asked for a ledger read looks, to find the oldest turns' steps. */
        private const val ATTRIBUTION_LOOKBACK = 300
        /** Rows a session read returns at most, to stay well inside a binder transaction. */
        private const val MAX_SESSION_ROWS = 400
        /** While the autopilot runs the preview is the show: ~5 fps instead of 1. */
        private const val AUTOPILOT_FRAME_INTERVAL_MS = FrameStreamPolicy.RUN_INTERVAL_MS
        private const val DISPLAY_FRAME_MAX_WIDTH = 480

        /**
         * JPEG quality for those frames.
         *
         * The stream feeds a preview pane and a replay, neither of which is a screenshot the
         * model has to read — that path calls `captureFrame` itself at full quality. 60 is
         * where a phone screenshot stops looking different and the file stops being large,
         * which matters at one a second against a retention cap in megabytes.
         */
        private const val DISPLAY_FRAME_QUALITY = 60

        /** `agent-os-design.md` §3's rung 4: driving a UI. What a display session is. */
        private const val RUNG_DISPLAY = 4
    }

    /**
     * Validates that the calling process belongs to an authorised caller: the launcher or
     * SystemUI by name, **and** the system uid or AndyClaw's own signing key ([CallerPolicy]). A
     * name alone let any app called `org.ethosmobile.ethoslauncher` read every key off a phone
     * that is not a dgen1.
     * Throws [SecurityException] if the caller is not authorized.
     */
    private fun enforceCallerIsLauncher() {
        val callingUid = Binder.getCallingUid()
        val callerPackages = packageManager.getPackagesForUid(callingUid)?.toList()
        val signatureMatch = CallerPolicy.needsSignatureCheck(callingUid, callerPackages) &&
            signedLikeUs(callingUid)
        if (CallerPolicy.isAllowed(callingUid, callerPackages, signatureMatch, DEBUG_CALLER_PACKAGES)) return
        val callerNames = callerPackages?.joinToString() ?: "unknown (uid=$callingUid)"
        Log.w(TAG, "Rejected IPC from unauthorized caller: $callerNames")
        throw SecurityException(
            "Only authorised packages may bind to LauncherBindingService. " +
            "Caller: $callerNames"
        )
    }

    /**
     * `sendLockscreenPrompt` is SystemUI's alone, authenticated like every other call; the launcher
     * sends its turns through `sendPrompt`.
     */
    private fun enforceCallerIsSystemUi() {
        val callingUid = Binder.getCallingUid()
        val callerPackages = packageManager.getPackagesForUid(callingUid)?.toList()
        val signatureMatch = callingUid != CallerPolicy.SYSTEM_UID &&
            callerPackages.orEmpty().contains(CallerPolicy.SYSTEMUI_PACKAGE) && signedLikeUs(callingUid)
        if (CallerPolicy.lockscreenAllowed(callingUid, callerPackages, signatureMatch)) return
        val callerNames = callerPackages?.joinToString() ?: "unknown (uid=$callingUid)"
        Log.w(TAG, "Rejected sendLockscreenPrompt from $callerNames")
        throw SecurityException("Only SystemUI may send lock-screen prompts. Caller: $callerNames")
    }

    /** Whether [uid] is signed with the key this APK is signed with (the platform key on a dgen1). */
    private fun signedLikeUs(uid: Int): Boolean = try {
        packageManager.checkSignatures(uid, android.os.Process.myUid()) == PackageManager.SIGNATURE_MATCH
    } catch (e: Exception) {
        Log.w(TAG, "Signature check for uid $uid failed", e)
        false
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught coroutine error", throwable)
        }
    )

    /**
     * Display capture streams, one per launcher session.
     *
     * This was a single field, which meant a second session silently inherited the first
     * one's stream: `startDisplayCapture` returned early because a job was already active,
     * and whichever session stopped first cancelled it for both. One agent display at a time
     * is a property of the OS service, not of this one — two launcher sessions can each be
     * mid-turn, and each is entitled to its own frames and its own recording.
     */
    private val displayCaptures = java.util.concurrent.ConcurrentHashMap<String, DisplayCapture>()

    /** A running capture: the loop, and how often it pulls a frame (changed as runs start and end). */
    private class DisplayCapture(
        val job: Job,
        val intervalMs: java.util.concurrent.atomic.AtomicLong,
    )

    /** The running turn of each launcher session, so a newer prompt or STOP can end it. */
    private val turns by lazy { LauncherTurns(scope) }

    /**
     * The launcher's conversations — the model's context and the Room session — keyed by the
     * launcher's own session id, which is also the Room session's id and the ledger's (IPC-10).
     */
    private val conversations by lazy {
        LauncherConversations(LauncherConversations.storeOf((application as NodeApp).sessionManager))
    }

    /** Tracks whether a memory reindex is in progress. */
    private val isReindexingFlag = AtomicBoolean(false)

    /** Currently registered exec summary streaming callback from the launcher. */
    private var execSummaryCallback: IExecSummaryCallback? = null

    /**
     * Starts a turn for [sessionId]: it replaces a turn the session still has running, ends if the
     * caller's process dies, and sends exactly one terminal callback however it ends.
     */
    private fun startTurn(prompt: String, sessionId: String, callback: ILauncherCallback, fromLockscreen: Boolean) {
        val terminal = TurnTerminal()
        val caller = try { callback.asBinder() } catch (_: Exception) { null }
        turns.start(sessionId, caller) {
            try {
                runAgentLoop(prompt, sessionId, callback, terminal, fromLockscreen)
                // Every path of the loop ends in onComplete or onError; this is the net under it.
                terminal.end {
                    Log.w(TAG, "Turn for session $sessionId ended without an answer")
                    try { callback.onError("Something went wrong on my side") } catch (_: RemoteException) {}
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Inference cancelled for session $sessionId")
                terminal.end {
                    try { callback.onError("Cancelled") } catch (_: RemoteException) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, if (fromLockscreen) "sendLockscreenPrompt failed" else "sendPrompt failed", e)
                terminal.end {
                    try { callback.onError(e.message ?: "Unknown error") } catch (_: RemoteException) {}
                }
            }
        }
    }

    private val binder = object : ILauncherService.Stub() {

        override fun isSetup(): Boolean {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return false
            return app.userStoryManager.exists()
        }

        override fun getAiName(): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return "AndyClaw"
            return app.userStoryManager.getAiName()
        }

        override fun sendPrompt(prompt: String, sessionId: String, callback: ILauncherCallback) {
            enforceCallerIsLauncher()
            startTurn(prompt, sessionId, callback, fromLockscreen = false)
        }

        override fun transcribeAudio(audioFd: ParcelFileDescriptor, callback: ILauncherCallback) {
            enforceCallerIsLauncher()
            scope.launch {
                // Copy the audio data from the PFD to a local temp file so
                // WhisperTranscriber (which needs a file path) can access it.
                val tempFile = File(cacheDir, "launcher_audio_${System.currentTimeMillis()}.wav")
                try {
                    // The descriptor is closed however the copy ends: a failed copy used to leak it.
                    audioFd.use { fd ->
                        FileInputStream(fd.fileDescriptor).use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }

                    val app = application as NodeApp
                    val text = app.whisperTranscriber.transcribe(tempFile.absolutePath)
                    callback.onTranscription(text)
                } catch (e: Exception) {
                    Log.e(TAG, "transcribeAudio failed", e)
                    try {
                        callback.onError("Transcription failed: ${e.message}")
                    } catch (_: RemoteException) {}
                } finally {
                    tempFile.delete()
                }
            }
        }

        override fun clearSession(sessionId: String) {
            enforceCallerIsLauncher()
            if (application !is NodeApp) return
            conversations.clear(sessionId)
            Log.d(TAG, "Cleared session: $sessionId")
        }

        override fun sendLockscreenPrompt(
            prompt: String,
            sessionId: String,
            callback: ILauncherCallback,
        ) {
            enforceCallerIsSystemUi()
            startTurn(prompt, sessionId, callback, fromLockscreen = true)
        }

        override fun getRecentSessions(limit: Int): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return "[]"
            return runBlocking(Dispatchers.IO) {
                try {
                    val sessions = app.sessionManager.getSessions()
                        .sortedByDescending { it.updatedAt }
                        .take(limit.coerceIn(1, 50))
                    val arr = JSONArray()
                    for (s in sessions) {
                        arr.put(JSONObject().apply {
                            put("id", s.id)
                            put("title", s.title)
                            put("updatedAt", s.updatedAt)
                        })
                    }
                    arr.toString()
                } catch (e: Exception) {
                    Log.e(TAG, "getRecentSessions failed", e)
                    "[]"
                }
            }
        }

        override fun getSessionMessages(sessionId: String): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return "[]"
            return runBlocking(Dispatchers.IO) {
                try {
                    val messages = app.sessionManager.getMessages(sessionId)
                    // Bounded: an unbounded session overflowed the 1 MB binder transaction. Only
                    // what was said: tool rows the launcher drops, and summaries it showed as replies.
                    val rows = SessionMessagesCap.capForLauncher(
                        messages.map { SessionMessagesCap.Row(it.role.name.lowercase(), it.content, it.timestamp) }
                    )
                    val arr = JSONArray()
                    for (m in rows) {
                        arr.put(JSONObject().apply {
                            put("role", m.role)
                            put("content", m.content)
                            put("timestamp", m.timestamp)
                            if (m.truncated) put("truncated", true)
                            if (m.omittedBefore > 0) put("omittedBefore", m.omittedBefore)
                        })
                    }
                    if (rows.size < messages.size || rows.any { it.truncated }) {
                        Log.i(TAG, "getSessionMessages: $sessionId capped to ${rows.size}/${messages.size} rows")
                    }
                    arr.toString()
                } catch (e: Exception) {
                    Log.e(TAG, "getSessionMessages failed", e)
                    "[]"
                }
            }
        }

        override fun getSettings(): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return "{}"
            val prefs = app.securePrefs
            return JSONObject().apply {
                put("provider", prefs.selectedProvider.value.name)
                put("model", prefs.selectedModel.value)
                put("aiName", prefs.aiName.value)
                put("yoloMode", prefs.yoloMode.value)
                put("safetyEnabled", prefs.safetyEnabled.value)
                put("provenanceEnforcement", prefs.provenanceEnforcementEnabled.value)
                put("notificationReplyEnabled", prefs.notificationReplyEnabled.value)
                put("executiveSummaryEnabled", prefs.executiveSummaryEnabled.value)
                put("heartbeatOnNotification", prefs.heartbeatOnNotificationEnabled.value)
                put("ledgerEnabled", prefs.ledgerEnabled.value)
                put("displayFrameCapture", prefs.displayFrameCaptureEnabled.value)
                // The autopilot, and running saved tasks (compiled flows) without a card. Only on
                // a phone that has the agent display, where both exist.
                if (OsCapabilities.hasPrivilegedAccess) {
                    put("autopilotEnabled", prefs.autopilotEnabled.value)
                    put("jevPrefetchEnabled", prefs.jevPrefetchEnabled.value)
                    put("autopilotNoConfirm", prefs.autopilotNoConfirm.value)
                }
                // The on-device reflex models: routing and shadow mode, and running simple
                // commands on the phone. Only where the models can run (not an x86 host).
                app.reflexRuntime?.let { reflex ->
                    put("reflexEnabled", prefs.reflexEnabled.value)
                    put("reflexInstant", prefs.reflexInstantEnabled.value)
                    // absent | downloading | ready | failed: the model that fills in times, levels
                    // and app names. Fetched on Wi-Fi by itself; "reflexActorDownload" asks now.
                    put("reflexActorState", reflex.actorState.value.name.lowercase())
                    put("reflexActorProgress", reflex.actorProgress.value.toDouble())
                    runCatching { reflex.shadowSummary() }.getOrNull()?.let { put("reflexSummary", it) }
                }
                put("ambientIngest", prefs.ambientIngestEnabled.value)
                // ok | no_account | auth_expired | offline | off, and when it last worked, so
                // the launcher can say "Google disconnected — reconnect" instead of showing nothing.
                runCatching { app.ambientIngestStatus() }.getOrNull()?.let { status ->
                    put("ambientIngestState", status.state)
                    put("ambientIngestLastSuccessMs", status.lastSuccessMs)
                    put("ambientIngestLastAttemptMs", status.lastAttemptMs)
                }
                put("heartbeatOnXmtpMessage", prefs.heartbeatOnXmtpMessageEnabled.value)
                put("heartbeatIntervalMinutes", prefs.heartbeatIntervalMinutes.value)
                put("heartbeatUseSameModel", prefs.heartbeatUseSameModel.value)
                put("heartbeatProvider", prefs.heartbeatProvider.value.name)
                put("heartbeatModel", prefs.heartbeatModel.value)
                put("smartRoutingEnabled", prefs.smartRoutingEnabled.value)
                put("routingUseSameModel", prefs.routingUseSameModel.value)
                put("routingProvider", prefs.routingProvider.value.name)
                put("routingModel", prefs.routingModel.value)
                put("ledMaxBrightness", prefs.ledMaxBrightness.value)
                put("telegramBotEnabled", prefs.telegramBotEnabled.value)
                put("telegramBotToken", prefs.telegramBotToken.value)
                put("telegramOwnerChatId", prefs.telegramOwnerChatId.value)
                put("memoryAutoStore", prefs.getString("memory.autoStore") != "false")
                put("apiKey", prefs.apiKey.value)
                put("tinfoilApiKey", prefs.tinfoilApiKey.value)
                put("openaiApiKey", prefs.openaiApiKey.value)
                put("veniceApiKey", prefs.veniceApiKey.value)
                put("claudeOauthRefreshToken", prefs.claudeOauthRefreshToken.value)
                put("selectedRoutingPresetId", prefs.selectedRoutingPresetId.value)
                put("isLocalModelDownloaded", app.modelDownloadManager.isModelDownloaded)
                put("isDownloading", app.modelDownloadManager.isDownloading.value)
                put("downloadProgress", (app.modelDownloadManager.downloadProgress.value * 100).toInt())
                put("downloadError", app.modelDownloadManager.downloadError.value ?: "")
                put("googleOauthRefreshToken", prefs.googleOauthRefreshToken.value)
                put("googleOauthClientId", prefs.googleOauthClientId.value)
                put("googleOauthClientSecret", prefs.googleOauthClientSecret.value)
                put("isPrivileged", OsCapabilities.hasPrivilegedAccess)
                put("currentTier", OsCapabilities.currentTier().name)
                // Custom (self-hosted OpenAI-compatible) provider
                put("customBaseUrl", prefs.customBaseUrl.value)
                put("customApiKey", prefs.customApiKey.value)
                put("customModelId", prefs.customModelId.value)
                // Local LLM (on-device) — selected GGUF + runtime knobs
                put("selectedGguf", prefs.selectedGgufFilename.value)
                put("localTemperature", prefs.localLlmTemperature.value)
                put("localTopP", prefs.localLlmTopP.value)
                put("localTopK", prefs.localLlmTopK.value)
                put("localMaxTokens", prefs.localLlmMaxTokens.value)
                put("localRepeatPenalty", prefs.localLlmRepeatPenalty.value)
                put("localNCtx", prefs.localLlmNCtx.value)
                put("localNBatch", prefs.localLlmNBatch.value)
                put("localNThreads", prefs.localLlmNThreads.value)
                put("localNGpuLayers", prefs.localLlmNGpuLayers.value)
                put("localUseMmap", prefs.localLlmUseMmap.value)
                // Tool search on means the LLM router never runs (SET-03): the launcher shows the
                // router's switches only when this is false.
                put("toolSearchEnabled", prefs.toolSearchEnabled.value)
                // AndyClaw skips OS ticks the interval has not reached, so every interval up to a
                // day is honoured (SET-02).
                put("heartbeatMaxIntervalMinutes", LauncherSettings.HEARTBEAT_MAX_INTERVAL_MINUTES)
                // Connected without handing the launcher the refresh token to find out, and how the
                // last CONNECT went (SET-13, SET-26).
                put("googleConnected", prefs.googleOauthRefreshToken.value.isNotBlank())
                put("googleOauthState", app.googleAuthManager.wireState())
                put("telegramConfigured", LauncherSettings.telegramConfigured(
                    prefs.telegramBotToken.value, prefs.telegramOwnerChatId.value))
                // What a secret field shows instead of the secret (SET-26). The raw values above
                // still go out this release, for launchers that read them.
                val secrets = mapOf(
                    "apiKey" to prefs.apiKey.value,
                    "tinfoilApiKey" to prefs.tinfoilApiKey.value,
                    "openaiApiKey" to prefs.openaiApiKey.value,
                    "veniceApiKey" to prefs.veniceApiKey.value,
                    "customApiKey" to prefs.customApiKey.value,
                    "claudeOauthRefreshToken" to prefs.claudeOauthRefreshToken.value,
                    "googleOauthClientSecret" to prefs.googleOauthClientSecret.value,
                    "telegramBotToken" to prefs.telegramBotToken.value,
                )
                for (key in LauncherSettings.SECRET_KEYS) {
                    put("${key}Hint", LauncherSettings.secretHint(secrets[key].orEmpty()))
                }
            }.toString()
        }

        override fun setSetting(key: String, value: String): Boolean {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return false
            val prefs = app.securePrefs
            return try {
                when (key) {
                    "provider" -> {
                        val provider = LlmProvider.fromName(value) ?: return false
                        prefs.setSelectedProvider(provider)
                        // CUSTOM keeps the user's own model id, which setSelectedProvider has just put
                        // back; the Qwen placeholder is a model their server does not have (SET-10).
                        if (provider != LlmProvider.CUSTOM) {
                            prefs.setSelectedModel(AnthropicModels.defaultForProvider(provider).modelId)
                        }
                    }
                    "model" -> {
                        prefs.setSelectedModel(value)
                        // For CUSTOM the pick is the server's model id: keep customModelId in step,
                        // or the provider never counts as configured (SET-10).
                        if (prefs.selectedProvider.value == LlmProvider.CUSTOM && value.isNotBlank()) {
                            prefs.setCustomModelId(value)
                        }
                    }
                    "aiName" -> {
                        // Where every run and getAiName() read it too: the story's `# Name:` line
                        // (SET-06). Blank is refused, not turned into "AndyClaw".
                        val name = value.trim()
                        if (name.isEmpty()) return false
                        prefs.setAiName(name)
                        app.userStoryManager.rename(name)
                    }
                    "yoloMode" -> prefs.setYoloMode(value.toBooleanStrict())
                    "safetyEnabled" -> prefs.setSafetyEnabled(value.toBooleanStrict())
                    // Off = the provenance gate logs its verdicts without applying
                    // them. The switch to watch real traffic with before enforcing.
                    "provenanceEnforcement" -> prefs.setProvenanceEnforcementEnabled(value.toBooleanStrict())
                    "notificationReplyEnabled" -> prefs.setNotificationReplyEnabled(value.toBooleanStrict())
                    "executiveSummaryEnabled" -> prefs.setExecutiveSummaryEnabled(value.toBooleanStrict())
                    "heartbeatOnNotification" -> prefs.setHeartbeatOnNotificationEnabled(value.toBooleanStrict())
                    "ledgerEnabled" -> prefs.setLedgerEnabled(value.toBooleanStrict())
                    "displayFrameCapture" -> prefs.setDisplayFrameCaptureEnabled(value.toBooleanStrict())
                    "autopilotEnabled" -> prefs.setAutopilotEnabled(value.toBooleanStrict())
                    "jevPrefetchEnabled" -> prefs.setJevPrefetchEnabled(value.toBooleanStrict())
                    "autopilotNoConfirm" -> prefs.setAutopilotNoConfirm(value.toBooleanStrict())
                    "reflexEnabled" -> prefs.setReflexEnabled(value.toBooleanStrict())
                    "reflexInstant" -> prefs.setReflexInstantEnabled(value.toBooleanStrict())
                    "reflexActorDownload" -> {
                        val reflex = app.reflexRuntime ?: return false
                        reflex.scope.launch { reflex.downloadActor() }
                    }
                    // Through the app, not the prefs: the receivers have to follow the
                    // switch, or nothing happens until the next boot.
                    "ambientIngest" -> app.setAmbientIngestEnabled(value.toBooleanStrict())
                    "heartbeatOnXmtpMessage" -> prefs.setHeartbeatOnXmtpMessageEnabled(value.toBooleanStrict())
                    "heartbeatIntervalMinutes" -> prefs.setHeartbeatIntervalMinutes(value.toInt())
                    // As AndyClaw's own settings do it (SET-08): a provider change brings the model
                    // along, and a separate model starts from the main one.
                    "heartbeatUseSameModel" -> {
                        val same = value.toBooleanStrict()
                        prefs.setHeartbeatUseSameModel(same)
                        if (!same && prefs.heartbeatModel.value.isBlank()) {
                            prefs.setHeartbeatProvider(prefs.selectedProvider.value)
                            prefs.setHeartbeatModel(prefs.selectedModel.value)
                        }
                    }
                    "heartbeatProvider" -> {
                        val p = LlmProvider.fromName(value) ?: return false
                        prefs.setHeartbeatProvider(p)
                        prefs.setHeartbeatModel(LauncherSettings.modelAfterProviderChange(
                            prefs.getHeartbeatUserModelForProvider(p),
                            AnthropicModels.defaultForProvider(p).modelId,
                        ))
                    }
                    "heartbeatModel" -> {
                        prefs.setHeartbeatModel(value)
                        prefs.setHeartbeatUserModelForProvider(prefs.heartbeatProvider.value, value)
                    }
                    "smartRoutingEnabled" -> prefs.setSmartRoutingEnabled(value.toBooleanStrict())
                    "toolSearchEnabled" -> prefs.setToolSearchEnabled(value.toBooleanStrict())
                    "routingUseSameModel" -> {
                        val same = value.toBooleanStrict()
                        prefs.setRoutingUseSameModel(same)
                        if (!same && prefs.routingModel.value.isBlank()) {
                            val main = prefs.selectedProvider.value
                            prefs.setRoutingProvider(main)
                            (AnthropicModels.routingModelForProvider(main) ?: AnthropicModels.defaultForProvider(main))
                                .let { prefs.setRoutingModel(it.modelId) }
                        }
                    }
                    "routingProvider" -> {
                        val p = LlmProvider.fromName(value) ?: return false
                        prefs.setRoutingProvider(p)
                        prefs.setRoutingModel(LauncherSettings.modelAfterProviderChange(
                            prefs.getRoutingUserModelForProvider(p),
                            (AnthropicModels.routingModelForProvider(p) ?: AnthropicModels.defaultForProvider(p)).modelId,
                        ))
                    }
                    "routingModel" -> {
                        prefs.setRoutingModel(value)
                        prefs.setRoutingUserModelForProvider(prefs.routingProvider.value, value)
                    }
                    "ledMaxBrightness" -> prefs.setLedMaxBrightness(value.toInt())
                    "telegramBotEnabled" -> prefs.setTelegramBotEnabled(value.toBooleanStrict())
                    "telegramBotToken" -> prefs.setTelegramBotToken(value)
                    "telegramOwnerChatId" -> prefs.setTelegramOwnerChatId(value.toLong())
                    "memoryAutoStore" -> prefs.putString("memory.autoStore", value)
                    "apiKey" -> prefs.setApiKey(value)
                    "tinfoilApiKey" -> prefs.setTinfoilApiKey(value)
                    "openaiApiKey" -> prefs.setOpenaiApiKey(value)
                    "veniceApiKey" -> prefs.setVeniceApiKey(value)
                    "claudeOauthRefreshToken" -> {
                        prefs.setClaudeOauthRefreshToken(value)
                        prefs.setClaudeOauthAccessToken("")
                        prefs.setClaudeOauthExpiresAt(0L)
                    }
                    "selectedRoutingPresetId" -> prefs.setSelectedRoutingPresetId(value)
                    "googleOauthClientId" -> prefs.setGoogleOauthClientId(value)
                    "googleOauthClientSecret" -> prefs.setGoogleOauthClientSecret(value)
                    // Custom provider
                    "customBaseUrl" -> prefs.setCustomBaseUrl(value)
                    "customApiKey" -> prefs.setCustomApiKey(value)
                    "customModelId" -> prefs.setCustomModelId(value)
                    // Local LLM runtime knobs
                    "selectedGguf" -> prefs.setSelectedGgufFilename(value)
                    "localTemperature" -> prefs.setLocalLlmTemperature(value.toFloat())
                    "localTopP" -> prefs.setLocalLlmTopP(value.toFloat())
                    "localTopK" -> prefs.setLocalLlmTopK(value.toInt())
                    "localMaxTokens" -> prefs.setLocalLlmMaxTokens(value.toInt())
                    "localRepeatPenalty" -> prefs.setLocalLlmRepeatPenalty(value.toFloat())
                    "localNCtx" -> prefs.setLocalLlmNCtx(value.toInt())
                    "localNBatch" -> prefs.setLocalLlmNBatch(value.toInt())
                    "localNThreads" -> prefs.setLocalLlmNThreads(value.toInt())
                    "localNGpuLayers" -> prefs.setLocalLlmNGpuLayers(value.toInt())
                    "localUseMmap" -> prefs.setLocalLlmUseMmap(value.toBooleanStrict())
                    else -> return false
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "setSetting($key) failed", e)
                false
            }
        }

        override fun getAvailableProviders(): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return "[]"
            val prefs = app.securePrefs
            val arr = JSONArray()
            for (provider in LlmProvider.entries) {
                // OPENAI_OAUTH (ChatGPT) is hidden until its live round-trip is
                // validated — keep it out of the launcher picker too, matching
                // AndyClaw's own provider list.
                if (provider == LlmProvider.OPENAI_OAUTH) continue
                val isConfigured = when (provider) {
                    LlmProvider.ETHOS_PREMIUM -> OsCapabilities.hasPrivilegedAccess
                    LlmProvider.OPEN_ROUTER -> prefs.apiKey.value.isNotBlank()
                    LlmProvider.TINFOIL -> prefs.tinfoilApiKey.value.isNotBlank()
                    LlmProvider.CLAUDE_OAUTH -> prefs.claudeOauthRefreshToken.value.isNotBlank()
                    LlmProvider.OPENAI_OAUTH -> prefs.chatgptOauthRefreshToken.value.isNotBlank()
                    LlmProvider.OPENAI -> prefs.openaiApiKey.value.isNotBlank()
                    LlmProvider.VENICE -> prefs.veniceApiKey.value.isNotBlank()
                    LlmProvider.LOCAL -> true
                    LlmProvider.CUSTOM -> prefs.customBaseUrl.value.isNotBlank() && prefs.customModelId.value.isNotBlank()
                }
                arr.put(JSONObject().apply {
                    put("name", provider.name)
                    put("displayName", provider.displayName)
                    put("isConfigured", isConfigured)
                })
            }
            return arr.toString()
        }

        override fun getAvailableModels(providerName: String): String {
            enforceCallerIsLauncher()
            val provider = LlmProvider.fromName(providerName) ?: return "[]"
            // CUSTOM: discover models from the user's self-hosted server via
            // GET {base}/v1/models (cached 30s). Blocking HTTP on the binder
            // thread, bounded by a 5s timeout — the launcher calls this from
            // a background coroutine.
            if (provider == LlmProvider.CUSTOM) {
                return fetchCustomModelsJson()
            }
            val models = AnthropicModels.forProvider(provider)
            val arr = JSONArray()
            for (model in models) {
                arr.put(JSONObject().apply {
                    put("modelId", model.modelId)
                    put("name", model.modelId)
                })
            }
            return arr.toString()
        }

        override fun deleteSession(sessionId: String) {
            enforceCallerIsLauncher()
            if (application !is NodeApp) return
            runBlocking(Dispatchers.IO) {
                try {
                    // The stored conversation and what memory holds for it, under either id.
                    conversations.delete(sessionId)
                } catch (e: Exception) {
                    Log.e(TAG, "deleteSession failed", e)
                }
            }
            Log.d(TAG, "Deleted session: $sessionId")
        }

        override fun resumeSession(sessionId: String) {
            enforceCallerIsLauncher()
            if (application !is NodeApp) return
            runBlocking(Dispatchers.IO) {
                try {
                    // Works for a conversation started on the home screen too: its Room session
                    // carries the launcher's id.
                    val size = conversations.resume(sessionId)
                    Log.d(TAG, "Resumed session: $sessionId with $size messages")
                } catch (e: Exception) {
                    Log.e(TAG, "resumeSession failed", e)
                }
            }
        }

        override fun stopInference(sessionId: String) {
            enforceCallerIsLauncher()
            if (turns.stop(sessionId)) {
                Log.i(TAG, "Stopping inference for session: $sessionId")
            } else {
                Log.d(TAG, "No active inference to stop for session: $sessionId")
            }
            // Also stop display capture if running. Only this session's — cancelling every
            // stream because one session was stopped is what the single-field version did.
            finishDisplayCapture(sessionId)
        }

        // ── Telegram ──────────────────────────────────────────────────────

        override fun completeTelegramSetup(token: String, ownerChatId: Long): Boolean {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return false
            return try {
                app.securePrefs.setTelegramBotToken(token)
                app.securePrefs.setTelegramOwnerChatId(ownerChatId)
                app.securePrefs.setTelegramBotEnabled(true)
                notifyOsTelegramRegister(token)
                true
            } catch (e: Exception) {
                Log.w(TAG, "completeTelegramSetup failed", e)
                false
            }
        }

        override fun clearTelegramSetup() {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return
            app.securePrefs.clearTelegramSetup()
            notifyOsTelegramUnregister()
        }

        // ── Memory ────────────────────────────────────────────────────────

        override fun getMemoryCount(): Int {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return 0
            return runBlocking(Dispatchers.IO) {
                try {
                    app.memoryManager.observeCount().first()
                } catch (_: Exception) { 0 }
            }
        }

        override fun reindexMemory() {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return
            scope.launch {
                isReindexingFlag.set(true)
                try {
                    app.memoryManager.reindex(force = true)
                } catch (_: Exception) {
                } finally {
                    isReindexingFlag.set(false)
                }
            }
        }

        override fun clearAllMemories() {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return
            scope.launch {
                try {
                    app.memoryManager.deleteAll()
                } catch (_: Exception) {}
            }
        }

        override fun isReindexing(): Boolean {
            enforceCallerIsLauncher()
            return isReindexingFlag.get()
        }

        // ── Extensions ────────────────────────────────────────────────────

        override fun getExtensions(): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return "[]"
            val exts = app.extensionEngine.registry.getAll()
            return JSONArray().apply {
                for (ext in exts) {
                    put(JSONObject().apply {
                        put("name", ext.name)
                        put("type", ext.type.name)
                        put("functionCount", ext.functions.size)
                        put("version", ext.version)
                        put("trusted", ext.trusted)
                    })
                }
            }.toString()
        }

        override fun rescanExtensions() {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return
            scope.launch {
                try {
                    app.extensionEngine.discoverAndRegister()
                } catch (_: Exception) {}
            }
        }

        // ── Skills ────────────────────────────────────────────────────────

        override fun getRegisteredSkills(): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return "[]"
            val skills = app.nativeSkillRegistry.getAll()
            return JSONArray().apply {
                for (skill in skills) {
                    put(JSONObject().apply {
                        put("id", skill.id)
                        put("name", skill.name)
                        put("description", skill.baseManifest.description)
                        put("toolCount", skill.baseManifest.tools.size +
                            (skill.privilegedManifest?.tools?.size ?: 0))
                        put("requiresPrivileged", skill.privilegedManifest != null &&
                            skill.baseManifest.tools.isEmpty())
                        val toolsArray = JSONArray()
                        for (tool in skill.baseManifest.tools) {
                            toolsArray.put(JSONObject().apply {
                                put("name", tool.name)
                                put("description", tool.description)
                            })
                        }
                        skill.privilegedManifest?.tools?.forEach { tool ->
                            toolsArray.put(JSONObject().apply {
                                put("name", tool.name)
                                put("description", tool.description)
                            })
                        }
                        put("tools", toolsArray)
                    })
                }
            }.toString()
        }

        override fun getEnabledSkills(): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return "[]"
            return JSONArray().apply {
                for (id in app.securePrefs.enabledSkills.value) put(id)
            }.toString()
        }

        override fun toggleSkill(skillId: String, enabled: Boolean) {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return
            app.securePrefs.setSkillEnabled(skillId, enabled)
        }

        // ── Routing Presets ───────────────────────────────────────────────

        override fun getRoutingPresets(): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return "[]"
            val presets = app.securePrefs.routingPresets.value
            return JSONArray().apply {
                for (preset in presets) {
                    put(JSONObject().apply {
                        put("id", preset.id)
                        put("name", preset.name)
                        put("isStock", preset.isStock)
                    })
                }
            }.toString()
        }

        override fun selectRoutingPreset(presetId: String) {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return
            app.securePrefs.setSelectedRoutingPresetId(presetId)
        }

        // ── Paymaster ─────────────────────────────────────────────────────

        override fun getPaymasterBalance(): String? {
            enforceCallerIsLauncher()
            return runBlocking(Dispatchers.IO) {
                try {
                    val sdk = PaymasterSDK(this@LauncherBindingService)
                    if (sdk.initialize()) {
                        val balance = sdk.getCurrentBalance()
                        sdk.cleanup()
                        balance
                    } else null
                } catch (_: Exception) { null }
            }
        }

        // ── Agent Wallet ──────────────────────────────────────────────────

        override fun getAgentWalletAddress(): String? {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return null
            val prefs = app.securePrefs

            // The agent wallet address is counterfactual/deterministic: once known it does not
            // change while its key lives, so it is cached and answered at once — offline too.
            //
            // The address comes from the agent wallet's own integrity check, never from a
            // SubWalletSDK built here: the SDK's constructor runs an RPC nothing can catch (it
            // killed the process offline), and on a keystore hiccup it replaces the key. And the
            // cache is checked behind the answer, once a process: a key that is gone or replaced
            // drops it, so the home screen stops showing an address nothing can spend from.
            val cacheKey = AGENT_WALLET_CACHE_KEY
            prefs.getString(cacheKey)?.takeIf { it.isNotBlank() }?.let { cached ->
                scope.launch { reconcileAgentWalletCache(app) }
                return cached
            }
            if (!isOnline()) return null

            return runBlocking(Dispatchers.IO) {
                try {
                    val integrity = app.agentWalletRepository.checkIntegrity()
                    if (integrity is org.ethereumphone.andyclaw.agentwallet.WalletIntegrity.Ok) {
                        prefs.putString(cacheKey, integrity.address)
                        agentWalletCacheChecked.set(true)
                        integrity.address
                    } else {
                        Log.w(TAG, "Agent wallet address withheld: ${integrity::class.simpleName}")
                        null
                    }
                } catch (_: Exception) { null }
            }
        }

        // ── Google OAuth ──────────────────────────────────────────────────

        override fun startGoogleOAuthFlow() {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return
            scope.launch {
                app.googleAuthManager.startOAuthFlow(this@LauncherBindingService)
            }
        }

        override fun disconnectGoogle() {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return
            app.securePrefs.clearGoogleOauthSetup()
            app.googleAuthManager.forgetFlow()
        }

        // ── Local Model ───────────────────────────────────────────────────

        override fun downloadLocalModel() {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return
            scope.launch { app.modelDownloadManager.download() }
        }

        override fun deleteLocalModel() {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return
            app.modelDownloadManager.deleteModel()
            if (app.llamaCpp.isModelLoaded) {
                app.llamaCpp.unload()
            }
        }

        // ── Routing Presets (extended) ─────────────────────────────────────

        override fun getRoutingPresetsDetailed(): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return "[]"
            val presets = app.securePrefs.routingPresets.value
            return JSONArray().apply {
                for (preset in presets) {
                    put(JSONObject().apply {
                        put("id", preset.id)
                        put("name", preset.name)
                        put("isStock", preset.isStock)
                        put("coreSkillIds", JSONArray(preset.coreSkillIds.toList()))
                        put("coreDgen1SkillIds", JSONArray(preset.coreDgen1SkillIds.toList()))
                        val toolsObj = JSONObject()
                        for ((skillId, tools) in preset.alwaysIncludeTools) {
                            toolsObj.put(skillId, JSONArray(tools.toList()))
                        }
                        put("alwaysIncludeTools", toolsObj)
                    })
                }
            }.toString()
        }

        override fun saveRoutingPreset(presetJson: String) {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return
            try {
                val obj = JSONObject(presetJson)
                val preset = RoutingPreset(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    isStock = obj.getBoolean("isStock"),
                    coreSkillIds = obj.getJSONArray("coreSkillIds").let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }.toSet()
                    },
                    coreDgen1SkillIds = obj.getJSONArray("coreDgen1SkillIds").let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }.toSet()
                    },
                    alwaysIncludeTools = obj.optJSONObject("alwaysIncludeTools")?.let { toolsObj ->
                        val map = mutableMapOf<String, Set<String>>()
                        for (key in toolsObj.keys()) {
                            val arr = toolsObj.getJSONArray(key)
                            map[key] = (0 until arr.length()).map { arr.getString(it) }.toSet()
                        }
                        map
                    } ?: emptyMap(),
                )
                val current = app.securePrefs.routingPresets.value.toMutableList()
                val index = current.indexOfFirst { it.id == preset.id }
                if (index >= 0) current[index] = preset else current.add(preset)
                app.securePrefs.setRoutingPresets(current)
            } catch (e: Exception) {
                Log.w(TAG, "saveRoutingPreset failed", e)
            }
        }

        override fun deleteRoutingPreset(presetId: String) {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return
            val current = app.securePrefs.routingPresets.value.toMutableList()
            current.removeAll { it.id == presetId && !it.isStock }
            app.securePrefs.setRoutingPresets(current)
            if (app.securePrefs.selectedRoutingPresetId.value == presetId) {
                app.securePrefs.setSelectedRoutingPresetId(RoutingPreset.defaultPresetId)
            }
        }

        override fun revertStockPreset(presetId: String) {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return
            val defaults = RoutingPreset.defaults()
            val defaultPreset = defaults.find { it.id == presetId } ?: return
            val current = app.securePrefs.routingPresets.value.toMutableList()
            val index = current.indexOfFirst { it.id == presetId }
            if (index >= 0) current[index] = defaultPreset
            app.securePrefs.setRoutingPresets(current)
        }

        // ── Agent Transactions ────────────────────────────────────────

        override fun getAgentTransactions(): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return "[]"
            return runBlocking(Dispatchers.IO) {
                try {
                    val txs = app.agentTxRepository.getAll()
                    JSONArray().apply {
                        for (tx in txs) {
                            put(JSONObject().apply {
                                put("id", tx.id)
                                put("userOpHash", tx.userOpHash)
                                put("chainId", tx.chainId)
                                put("to", tx.to)
                                put("amount", tx.amount)
                                put("token", tx.token)
                                put("toolName", tx.toolName)
                                put("timestamp", tx.timestamp)
                            })
                        }
                    }.toString()
                } catch (e: Exception) {
                    Log.w(TAG, "getAgentTransactions failed", e)
                    "[]"
                }
            }
        }

        override fun clearAgentTransactions() {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return
            scope.launch {
                try {
                    app.agentTxRepository.clearAll()
                } catch (_: Exception) {}
            }
        }

        // ── Executive Summary ─────────────────────────────────────────

        override fun getExecutiveSummary(): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return ""
            return try {
                app.executiveSummaryManager.readSummaryFromService()
            } catch (e: Exception) {
                Log.e(TAG, "getExecutiveSummary failed", e)
                ""
            }
        }

        override fun registerExecSummaryCallback(callback: IExecSummaryCallback?) {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return

            execSummaryCallback = callback
            if (callback != null) {
                app.executiveSummaryManager.streamListener =
                    object : ExecutiveSummaryManager.SummaryStreamListener {
                        override fun onToken(token: String) {
                            try {
                                callback.onSummaryToken(token)
                            } catch (e: RemoteException) {
                                Log.w(TAG, "Exec summary callback dead, unregistering", e)
                                execSummaryCallback = null
                                app.executiveSummaryManager.streamListener = null
                            }
                        }

                        override fun onComplete(fullSummary: String) {
                            try {
                                callback.onSummaryComplete(fullSummary)
                            } catch (_: RemoteException) {}
                        }

                        override fun onError(message: String) {
                            try {
                                callback.onSummaryError(message)
                            } catch (_: RemoteException) {}
                        }
                    }
                Log.i(TAG, "Exec summary streaming callback registered")
            } else {
                app.executiveSummaryManager.streamListener = null
                Log.i(TAG, "Exec summary streaming callback cleared")
            }
        }

        override fun unregisterExecSummaryCallback() {
            enforceCallerIsLauncher()
            execSummaryCallback = null
            val app = application as? NodeApp ?: return
            app.executiveSummaryManager.streamListener = null
            Log.i(TAG, "Exec summary streaming callback unregistered")
        }

        override fun dismissExecSummaryBullet(bulletText: String?) {
            enforceCallerIsLauncher()
            if (bulletText.isNullOrBlank()) return
            val app = application as? NodeApp ?: return
            app.executiveSummaryManager.addDismissedBullet(bulletText)
            // Also remove from the cached summary so it doesn't reappear on next fetch
            app.executiveSummaryManager.removeBulletFromCachedSummary(bulletText)
        }

        // ── Local LLM GGUF management (launcher port) ────────────────────
        override fun getGgufModels(): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return "[]"
            app.ggufRegistry.refresh()
            val arr = JSONArray()
            for (m in app.ggufRegistry.models.value) {
                arr.put(JSONObject().apply {
                    put("filename", m.filename)
                    put("displayName", m.displayName)
                    put("sizeBytes", m.sizeBytes)
                    put("isBuiltin", m.isBuiltin)
                })
            }
            return arr.toString()
        }

        override fun importGguf(fd: ParcelFileDescriptor?, displayName: String?): Boolean {
            enforceCallerIsLauncher()
            if (fd == null) return false
            val app = application as? NodeApp ?: return false
            return try {
                // Synchronous copy on this binder thread; the launcher calls
                // from a background coroutine and shows a blocking spinner.
                app.ggufRegistry.importFromFd(fd, displayName ?: "imported.gguf") != null
            } catch (e: Exception) {
                Log.e(TAG, "importGguf failed", e)
                false
            } finally {
                try { fd.close() } catch (_: Exception) {}
            }
        }

        override fun deleteGguf(filename: String?): Boolean {
            enforceCallerIsLauncher()
            if (filename.isNullOrBlank()) return false
            val app = application as? NodeApp ?: return false
            return app.ggufRegistry.delete(filename)
        }

        // ── Heartbeat logs (launcher port) ───────────────────────────────
        override fun getHeartbeatLogs(): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return "[]"
            // Newest first from the store, and only as many as fit in one binder reply.
            return LauncherReplies.heartbeatLogs(app.heartbeatLogStore.getAll())
        }

        override fun clearHeartbeatLogs() {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return
            app.heartbeatLogStore.clear()
        }

        // ── ClawHub (ordinals 50-60) ─────────────────────────────────────
        //
        // Placeholders holding the launcher's ordinals; see the AIDL. Each returns what
        // the launcher already copes with today, when the transaction finds no method at
        // all and the reply parcel comes back empty. Nothing here is a feature; the point
        // is that clawHubSearch() lands on clawHubSearch() and not on getPredictedCards().
        override fun clawHubSearch(query: String?, limit: Int): String = "[]"
        override fun clawHubBrowse(cursor: String?): String =
            """{"items":[],"nextCursor":null}"""
        override fun clawHubListInstalled(): String = "[]"
        override fun clawHubIsInstalled(slug: String?): Boolean = false
        override fun clawHubDownloadAndAssess(slug: String?): String =
            """{"status":"failed","reason":"ClawHub is not available on this build"}"""
        override fun clawHubConfirmInstall(slug: String?, version: String?): String =
            """{"status":"failed","reason":"ClawHub is not available on this build"}"""
        override fun clawHubCancelPendingInstall(slug: String?) {}
        override fun clawHubUninstall(slug: String?): Boolean = false
        override fun clawHubUpdate(slug: String?): String =
            """{"status":"failed","reason":"ClawHub is not available on this build"}"""
        override fun clawHubReadSkillContent(slug: String?): String? = null
        override fun clawHubGetRiskData(slug: String?): String? = null

        // ── Ambient card stack (ordinals 61-62) ──────────────────────────

        /**
         * What the device expects to matter soon, ranked.
         *
         * The ranking is [PredictedContextScorer]'s, not this method's and not the
         * launcher's: relevance is "how far into this kind of thing's own window are we",
         * which is why a flight two hours out comes above a standup in twenty minutes. If
         * the launcher re-sorted by `startMs` it would undo that, so the order this
         * returns is the order to draw.
         */
        override fun getPredictedCards(limit: Int): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return "[]"
            // Only ingestion makes cards, and the user turned it off: none are shown, whatever
            // a forget racing an ingest may have left behind.
            if (!app.securePrefs.ambientIngestEnabled.value) return "[]"
            val n = limit.coerceIn(1, 100)
            // Someone is looking at the home screen: a presence signal for ambient ingestion.
            app.onAmbientPresence()
            return try {
                val ranked = runBlocking { app.predictedContextRepository.relevantNow(limit = n) }
                val arr = JSONArray()
                for (scored in ranked) {
                    val c = scored.context
                    arr.put(JSONObject().apply {
                        put("id", c.id)
                        put("kind", c.kind.name)
                        put("title", c.title)
                        c.subtitle?.let { put("subtitle", it) }
                        put("startMs", c.startMs)
                        c.endMs?.let { put("endMs", it) }
                        c.location?.let { put("location", it) }
                        // Typed data, verbatim, as an object -- never as prose. The card
                        // library reads named fields out of it; nothing splices it into a
                        // prompt, which is the whole reason ingestion stores it this way.
                        put("payload", runCatching { JSONObject(c.payloadJson) }.getOrElse { JSONObject() })
                        put("provenance", c.provenance)
                        put("source", c.source)
                        put("score", scored.score)
                        put("untilStartMs", scored.untilStartMs)
                        // When the card last changed, so the launcher can mark a fresh gate change.
                        put("updatedMs", c.updatedMs)
                    })
                }
                arr.toString()
            } catch (e: Exception) {
                Log.w(TAG, "getPredictedCards failed", e)
                "[]"
            }
        }

        override fun dismissPredictedCard(id: String?) {
            enforceCallerIsLauncher()
            if (id.isNullOrBlank()) return
            val app = application as? NodeApp ?: return
            // Applied before this returns: the launcher refreshes the cards straight after, and a
            // dismissal still queued behind an ingest's write lock brought the card back.
            runBlocking(Dispatchers.IO) {
                runCatching { app.predictedContextRepository.dismiss(id) }
                    .onFailure { Log.w(TAG, "dismissPredictedCard failed", it) }
            }
        }

        // ── Pending approvals (ordinals 63-64, 71) ───────────────────────

        /**
         * The queue, with what each card needs to say exactly what will run: v1 keys for an
         * older launcher, and the v2 ones — every parameter as it will be used, who asked, until
         * when, and whether APPROVE can run it at all.
         */
        override fun getPendingApprovals(): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return "[]"
            return try {
                val store = app.pendingApprovalStore
                val arr = JSONArray()
                for (e in store.getAll()) {
                    val input = e.input?.let {
                        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull()
                    }
                    val executable = store.isExecutable(e)
                    // What APPROVE runs is shown whole: a card cut at 300 characters let a benign
                    // start hide what followed it, and the whole input is what runs.
                    val summary = org.ethereumphone.andyclaw.safety.ApprovalSummaries.of(
                        e.toolName, input,
                        maxValue = if (executable) Int.MAX_VALUE else org.ethereumphone.andyclaw.safety.ApprovalSummaries.MAX_VALUE,
                    )
                    arr.put(JSONObject().apply {
                        put("id", e.id)
                        put("timestampMs", e.timestampMs)
                        put("source", e.source)
                        put("provenance", e.provenance)
                        put("toolName", e.toolName)
                        put("description", e.description)
                        e.conversationId?.let { put("conversationId", it) }
                        e.inputPreview?.let { put("inputPreview", it) }
                        put("v", 2)
                        put("title", summary.title)
                        put("summary", summary.summary)
                        put("params", JSONArray().apply {
                            for (p in summary.params) {
                                put(JSONObject()
                                    .put("key", p.key)
                                    .put("label", p.label)
                                    .put("value", p.value)
                                    .put("truncated", p.truncated)
                                    .put("length", p.length))
                            }
                        })
                        e.effect?.let { put("effect", it) }
                        // Every executable request acts with the owner's authority: always the
                        // device credential first.
                        put("requiresDeviceAuth", true)
                        put("sourceLabel", org.ethereumphone.andyclaw.safety.ApprovalSummaries.sourceLabel(e.source, e.conversationId))
                        e.expiresMs?.let { put("expiresMs", it) }
                        put("count", e.count)
                        put("executable", executable)
                        // RUNNING: an approved call is executing (it answered RUNNING to the
                        // launcher). Not a card to act on again, and not one that "can't run".
                        put("state", if (e.state == org.ethereumphone.andyclaw.safety.PendingApprovalStore.State.EXECUTING) "RUNNING" else "PENDING")
                        e.toolReason?.let { put("toolReason", it) }
                        // Where the request is (or will be) recorded: the launcher offers OPEN LEDGER.
                        e.ledgerSessionId?.let { put("ledgerSessionId", it) }
                    })
                }
                arr.toString()
            } catch (e: Exception) {
                Log.w(TAG, "getPendingApprovals failed", e)
                "[]"
            }
        }

        /**
         * The older way to resolve a card. Declining declines. Approving is only acknowledged and
         * recorded, never executed: an older launcher follows it with the request as an ordinary
         * prompt, which is the user's own and runs as one — running the stored call here as well
         * would do the thing twice. APPROVE that runs the call is [resolvePendingApprovalWithResult].
         */
        override fun resolvePendingApproval(id: String?, approved: Boolean): Boolean {
            enforceCallerIsLauncher()
            if (id.isNullOrBlank()) return false
            val app = application as? NodeApp ?: return false
            if (!approved) return app.pendingApprovalExecutor.decline(id).state == "DECLINED"
            val entry = app.pendingApprovalStore.getAll().firstOrNull { it.id == id } ?: return false
            val done = app.pendingApprovalStore.acknowledge(id, "Acknowledged on the card; it was not run from there.")
            if (done) app.recordApprovalDecision(entry, "ACKNOWLEDGED", entry.description)
            return done
        }

        /**
         * APPROVE: runs exactly the stored call, once, under the provenance it came with — or
         * DECLINE. Never while the phone is locked: whoever holds a locked phone is not
         * necessarily its owner. A call that runs long answers RUNNING and finishes on its own.
         */
        override fun resolvePendingApprovalWithResult(id: String?, approved: Boolean): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp
                ?: return org.ethereumphone.andyclaw.safety.PendingApprovalExecutor.Resolution(
                    "FAILED", "The agent isn't ready yet. Nothing was run.").toJson()
            if (id.isNullOrBlank()) {
                return org.ethereumphone.andyclaw.safety.PendingApprovalExecutor.Resolution(
                    "ALREADY_HANDLED", "This request is no longer waiting.").toJson()
            }
            if (approved && getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != false) {
                return org.ethereumphone.andyclaw.safety.PendingApprovalExecutor.Resolution(
                    "LOCKED", "Unlock the phone and try again. Nothing was run.").toJson()
            }
            return try {
                runBlocking { app.pendingApprovalExecutor.resolve(id, approved) }.toJson()
            } catch (e: Exception) {
                Log.w(TAG, "resolvePendingApprovalWithResult failed", e)
                org.ethereumphone.andyclaw.safety.PendingApprovalExecutor.Resolution(
                    org.ethereumphone.andyclaw.safety.PendingApprovalStore.UNKNOWN,
                    "Something went wrong, so it may or may not have run. Check the ledger before trying again.",
                ).toJson()
            }
        }

        // ── Ledger (ordinals 65-69) ──────────────────────────────────────

        override fun getLedgerEntries(limit: Int): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return "[]"
            val n = limit.coerceIn(1, 1000)
            return try {
                // A turn's steps are written before it, so read a little further back than asked
                // to give the oldest turns shown their steps too.
                val rows = runBlocking { app.ledgerRepository.recent(n + ATTRIBUTION_LOOKBACK) }
                // Capped by what it costs on the wire, not by count: a reply over the binder
                // buffer failed outright, and the launcher showed an empty ledger.
                LauncherReplies.ledgerEntries(rows, n)
            } catch (e: Exception) {
                Log.w(TAG, "getLedgerEntries failed", e)
                "[]"
            }
        }

        override fun getLedgerSession(sessionId: String?): String {
            enforceCallerIsLauncher()
            if (sessionId.isNullOrBlank()) return "{}"
            val app = application as? NodeApp ?: return "{}"
            return try {
                val replay = runBlocking { app.sessionReplay.of(sessionId) }
                // A Telegram chat is one session for every message it ever sent, and a binder
                // reply over a megabyte fails outright: the newest rows that fit, and say so.
                LauncherReplies.ledgerSession(replay, MAX_SESSION_ROWS).toString()
            } catch (e: Exception) {
                Log.w(TAG, "getLedgerSession failed", e)
                "{}"
            }
        }

        override fun verifyLedger(): String {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return """{"ok":false,"checked":0}"""
            return try {
                val v = runBlocking { app.ledgerRepository.verify() }
                JSONObject().apply {
                    put("ok", v.ok)
                    put("checked", v.checkedLinks)
                    put("firstSeq", v.firstSeq)
                    put("lastSeq", v.lastSeq)
                    if (v.brokenAtSeq != null) put("brokenAtSeq", v.brokenAtSeq) else put("brokenAtSeq", JSONObject.NULL)
                    v.reason?.let { put("reason", it) }
                }.toString()
            } catch (e: Exception) {
                Log.w(TAG, "verifyLedger failed", e)
                """{"ok":false,"checked":0,"reason":"verification failed"}"""
            }
        }

        override fun openLedgerFrame(frameId: String?): ParcelFileDescriptor? {
            enforceCallerIsLauncher()
            if (frameId.isNullOrBlank()) return null
            val app = application as? NodeApp ?: return null
            return try {
                val file = app.sessionFrameStore.fileFor(frameId) ?: return null
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: Exception) {
                Log.w(TAG, "openLedgerFrame failed for $frameId", e)
                null
            }
        }

        /**
         * The launcher's STOP. Latches the display, so input already queued is dropped, and
         * stops the run holding it: its autopilot performs nothing more, and the turn ends with
         * one line instead of another model call. The launcher also calls `stopInference` for
         * its own session, which cancels that turn outright.
         */
        override fun stopAgent() {
            enforceCallerIsLauncher()
            org.ethereumphone.andyclaw.autopilot.AgentDisplayCapabilities.requestStop()
        }

        /**
         * The ledger as JSONL, one row per line, oldest first.
         *
         * Oldest first because that is chain order, and an export whose whole claim is that
         * it can be re-verified off the device has to be walkable in the direction the
         * hashes run. The temp file is unlinked as soon as it is open: the fd keeps it
         * alive for the reader and nothing is left in cacheDir afterwards.
         */
        override fun exportLedger(): ParcelFileDescriptor? {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return null
            var tmp: File? = null
            return try {
                val exporter = org.ethereumphone.andyclaw.ledger.LedgerExporter(app.ledgerRepository) {
                    app.ledgerRecorder.drain()
                }
                tmp = runBlocking { exporter.export(cacheDir) }
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: Exception) {
                Log.w(TAG, "exportLedger failed", e)
                null
            } finally {
                // The fd keeps an open file alive; nothing is left behind in cacheDir either way.
                tmp?.delete()
            }
        }
    }

    // ── Custom /v1/models discovery (cached 30s) ────────────────────────
    @Volatile private var customModelsCacheJson: String = "[]"
    @Volatile private var customModelsCacheUrl: String = ""
    @Volatile private var customModelsCacheAt: Long = 0L

    private fun fetchCustomModelsJson(): String {
        val app = application as? NodeApp ?: return "[]"
        val baseUrl = app.securePrefs.customBaseUrl.value.trim()
        if (baseUrl.isBlank()) return "[]"
        val now = System.currentTimeMillis()
        if (baseUrl == customModelsCacheUrl && now - customModelsCacheAt < 30_000L) {
            return customModelsCacheJson
        }
        val url = LauncherSettings.modelsUrlFromChatUrl(baseUrl)
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url(url).apply {
                val key = app.securePrefs.customApiKey.value
                if (key.isNotBlank()) addHeader("Authorization", "Bearer $key")
            }.build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "[]"
                val data = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data")
                    ?: return "[]"
                val arr = JSONArray()
                for (i in 0 until data.length()) {
                    val id = data.getJSONObject(i).optString("id", "")
                    if (id.isNotBlank()) arr.put(JSONObject().apply { put("modelId", id); put("name", id) })
                }
                customModelsCacheJson = arr.toString()
                customModelsCacheUrl = baseUrl
                customModelsCacheAt = now
                arr.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchCustomModelsJson failed", e)
            "[]"
        }
    }

    /** Set once this process has checked the cached agent wallet address against its key. */
    private val agentWalletCacheChecked = AtomicBoolean(false)

    /**
     * Checks the cached agent wallet address once a process: a verified address replaces a cache
     * that disagrees with it, and a key that is gone or replaced drops the cache. An unverifiable
     * one (offline, a keystore hiccup) is tried again on the next ask.
     */
    private suspend fun reconcileAgentWalletCache(app: NodeApp) {
        if (!agentWalletCacheChecked.compareAndSet(false, true)) return
        try {
            when (val integrity = app.agentWalletRepository.checkIntegrity()) {
                is org.ethereumphone.andyclaw.agentwallet.WalletIntegrity.Ok -> {
                    if (!integrity.address.equals(app.securePrefs.getString(AGENT_WALLET_CACHE_KEY), ignoreCase = true)) {
                        Log.w(TAG, "Cached agent wallet address disagreed with the verified one; replaced")
                        app.securePrefs.putString(AGENT_WALLET_CACHE_KEY, integrity.address)
                    }
                }
                is org.ethereumphone.andyclaw.agentwallet.WalletIntegrity.Unknown -> agentWalletCacheChecked.set(false)
                else -> {
                    Log.e(TAG, "Agent wallet key is gone or replaced; no longer showing its cached address")
                    app.securePrefs.remove(AGENT_WALLET_CACHE_KEY)
                }
            }
        } catch (e: Exception) {
            org.ethereumphone.andyclaw.ExecutionEngine.rethrowIfCancelled(e)
            agentWalletCacheChecked.set(false)
        }
    }

    /** True only when there's a validated internet-capable active network.
     *  Used to avoid kicking off RPC calls (and the SDK's crash-prone internal
     *  coroutine) while offline. */
    private fun isOnline(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Exception) { false }

    private fun notifyOsTelegramRegister(token: String) {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "andyclawheartbeat") as? IBinder ?: return
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken("com.android.server.IAndyClawHeartbeat")
                data.writeString(token)
                binder.transact(IBinder.FIRST_CALL_TRANSACTION + 0, data, null, IBinder.FLAG_ONEWAY)
            } finally {
                data.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify OS of Telegram register", e)
        }
    }

    private fun notifyOsTelegramUnregister() {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "andyclawheartbeat") as? IBinder ?: return
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken("com.android.server.IAndyClawHeartbeat")
                binder.transact(IBinder.FIRST_CALL_TRANSACTION + 1, data, null, IBinder.FLAG_ONEWAY)
            } finally {
                data.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify OS of Telegram unregister", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "Launcher bound to service")
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        // Close every recording before the scope dies, or the last session's frames are on
        // disk with nothing in the ledger naming them.
        for (sessionId in displayCaptures.keys.toList()) finishDisplayCapture(sessionId)
        scope.cancel()
        Log.i(TAG, "Service destroyed")
    }

    /**
     * Stream frames from the agent display to the launcher, and — this is the new half —
     * keep them.
     *
     * The frames were already being pulled once a second and handed straight to the
     * launcher, which drew them and dropped them. Writing the same bytes to
     * [SessionFrameStore] on the way past turns "the agent did something on your behalf"
     * into "watch exactly what I did as you", which is the trust artifact the ledger exists
     * to support, for the cost of a file write per frame.
     *
     * Two fixes come with it. The capture now uses `captureFrameWithQuality`, which has
     * existed on the AIDL unused all along — the bare `captureFrame()` encodes at the
     * service's default quality, and a preview thumbnail plus a replay recording do not
     * need a maximum-quality JPEG once a second. And the loop is keyed by session, so a
     * second session gets its own stream instead of silently sharing the first one's.
     */
    /** One autopilot event, in the shape `ILauncherCallback.onAgentStep` documents. */
    private fun agentStepJson(e: org.ethereumphone.andyclaw.autopilot.AutopilotEvent): String {
        val o = org.json.JSONObject()
            .put("v", 1)
            .put("run", e.runId)
            .put("kind", e.kind.name)
            .put("step", e.step)
            .put("subgoal", e.subgoalIndex)
            .put("subgoals", org.json.JSONArray(e.subgoals))
            .put("plannerCalls", e.plannerCalls)
        e.action?.let { o.put("action", it) }
        e.target?.let { t ->
            o.put("target", org.json.JSONObject()
                .put("label", t.name ?: org.json.JSONObject.NULL)
                .put("type", t.type)
                .put("x", t.centerX)
                .put("y", t.centerY))
        }
        e.confidence?.let { o.put("confidence", it) }
        e.source?.let { o.put("source", it.name) }
        e.reason?.let { o.put("reason", it) }
        // On DONE/FAILED. A hand-over stays kind FAILED, so an older launcher still finishes its
        // card; `outcome` is how a newer one tells it from a failure, and `message` is what to say.
        e.outcome?.let { o.put("outcome", it) }
        e.message?.let { o.put("message", it) }
        val ms = org.json.JSONObject().put("elapsed", e.elapsedMs)
        e.timings?.let { t -> ms.put("jev", t.jevMs).put("act", t.actMs).put("settle", t.settleMs).put("step", t.stepMs) }
        o.put("ms", ms)
        return o.toString()
    }

    private fun startDisplayCapture(
        sessionId: String,
        callback: ILauncherCallback,
        intervalMs: Long = DISPLAY_FRAME_INTERVAL_MS,
    ) {
        displayCaptures[sessionId]?.takeIf { it.job.isActive }?.let { running ->
            // A new run on a stream the last one slowed down: back to the run's rate.
            if (intervalMs < running.intervalMs.get()) running.intervalMs.set(intervalMs)
            return
        }
        try {
            callback.onDisplayCreated()
        } catch (_: RemoteException) {}
        val rate = java.util.concurrent.atomic.AtomicLong(intervalMs)

        // Only the stream to the launcher lives here. What is kept for the ledger is recorded by
        // AgentDisplayRecording, for whichever run holds the display — launcher or not.
        val job = scope.launch {
            val svc = try {
                val smClass = Class.forName("android.os.ServiceManager")
                val getService = smClass.getMethod("getService", String::class.java)
                val binder = getService.invoke(null, "agentdisplay") as? IBinder
                binder?.let { IAgentDisplayService.Stub.asInterface(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get AgentDisplayService", e)
                null
            } ?: return@launch

            var clientAlive = true
            while (isActive && clientAlive) {
                try {
                    val displayId = svc.displayId
                    // No display yet (the run has not created it) or parked: nothing to show,
                    // and nothing worth a stack trace five times a second.
                    val privateApp = if (displayId < 0) null
                    else org.ethereumphone.andyclaw.services.AgentDisplayAccessibilityService.instance
                        ?.sensitivePackageOnDisplay(displayId)
                    if (displayId >= 0 && privateApp == null) {
                        // On an OS that encodes on demand, a downscaled frame is cheaper on both ends.
                        val frame = if (org.ethereumphone.andyclaw.autopilot.AgentDisplayCapabilities.hasV2) {
                            svc.captureFrameScaled(DISPLAY_FRAME_MAX_WIDTH, DISPLAY_FRAME_QUALITY)
                        } else {
                            svc.captureFrameWithQuality(DISPLAY_FRAME_QUALITY)
                        }
                        if (frame != null && frame.isNotEmpty()) {
                            try {
                                callback.onDisplayFrame(frame)
                            } catch (e: RemoteException) {
                                // Only a launcher that is gone ends the stream: a full async buffer
                                // costs this frame, not the preview for the rest of the turn.
                                val alive = try { callback.asBinder().isBinderAlive } catch (_: Exception) { false }
                                if (FrameStreamPolicy.launcherGone(e, alive)) {
                                    Log.w(TAG, "Launcher went away during display capture")
                                    clientAlive = false
                                } else {
                                    Log.w(TAG, "A frame did not reach the launcher (${e.javaClass.simpleName}); trying the next")
                                }
                            }
                        }
                    }
                    // A private app's screen is neither streamed nor kept.
                } catch (e: IllegalStateException) {
                    // The display went away between the check and the capture.
                } catch (e: Exception) {
                    Log.w(TAG, "Display capture failed: ${e.message}")
                }
                delay(rate.get())
            }
        }
        displayCaptures[sessionId] = DisplayCapture(job, rate)
    }

    /** A run on [sessionId]'s display started or ended: the preview follows at its rate. */
    private fun followRunRate(sessionId: String, kind: org.ethereumphone.andyclaw.autopilot.AutopilotEvent.Kind) {
        val interval = FrameStreamPolicy.intervalAfter(kind) ?: return
        displayCaptures[sessionId]?.intervalMs?.set(interval)
    }

    /**
     * Stops the display capture loop and notifies the launcher.
     */
    private fun stopDisplayCapture(sessionId: String, callback: ILauncherCallback) {
        finishDisplayCapture(sessionId)
        try {
            callback.onDisplayDestroyed()
        } catch (_: RemoteException) {}
    }

    /**
     * End a session's capture: stop the loop, close the recording, and write down what was
     * kept.
     *
     * The ledger row is written here rather than by the agent loop because this is the only
     * place that knows which frames belong to the session — and a row that names frames it
     * cannot produce would be worse than no row. It is the same session id the turn row
     * carries, so the two join without either side knowing about the other.
     */
    private fun finishDisplayCapture(sessionId: String) {
        displayCaptures.remove(sessionId)?.job?.cancel()
    }

    /**
     * Runs the full agent loop for a prompt, streaming tokens back to the launcher.
     */
    private suspend fun runAgentLoop(
        prompt: String,
        sessionId: String,
        callback: ILauncherCallback,
        terminal: TurnTerminal,
        fromLockscreen: Boolean = false,
    ) {
        val app = application as? NodeApp
            ?: throw IllegalStateException("Application is not NodeApp")

        val client = app.getLlmClient()
        val registry = app.nativeSkillRegistry
        val tier = OsCapabilities.currentTier()
        val aiName = app.userStoryManager.getAiName()
        val userStory = app.userStoryManager.read()

        val modelId = app.securePrefs.selectedModel.value
        val model = AnthropicModels.fromModelId(modelId) ?: AnthropicModels.MINIMAX_M3
        // CUSTOM (and an OpenRouter id that is not ours) sends the id the user picked, as the
        // in-app chat does; it went out as minimax/minimax-m3 to the user's own server (SET-10).
        val customModelIdOverride = org.ethereumphone.andyclaw.llm.ModelIdOverride.of(app.securePrefs.selectedProvider.value, modelId)

        val enabledSkillIds = if (app.securePrefs.yoloMode.value) {
            registry.getAll().map { it.id }.toSet()
        } else {
            app.securePrefs.enabledSkills.value
        }
        // Start the likely app while the model plans; never for a confidential or on-device model.
        val turnRoute = app.jevTurnRouter?.prewarm(prompt, client)
        val agentLoop = AgentLoop(
            client = client,
            skillRegistry = registry,
            tier = tier,
            enabledSkillIds = enabledSkillIds,
            model = model,
            aiName = aiName,
            userStory = userStory,
            soulContent = app.soulManager.read(),
            memoryManager = app.memoryManager,
            safetyLayer = app.createSafetyLayer(),
            smartRouter = if (app.securePrefs.smartRoutingEnabled.value && !app.securePrefs.toolSearchEnabled.value) app.smartRouter else null,
            toolSearchService = app.createToolSearchService(tier, enabledSkillIds),
            budgetConfig = app.createBudgetConfig(),
            customModelIdOverride = customModelIdOverride,
            // sendPrompt / sendLockscreenPrompt are the user typing or speaking.
            provenance = Provenance.USER,
            enforceProvenance = app.securePrefs.provenanceEnforcementEnabled.value,
            flowRecorder = app.flowRecorder,
            flowRepository = app.flowRepositoryOrNull,
            // The launcher's session id, so the turn row, its step rows and the display
            // frames all land under one key without any of the three knowing about the
            // others.
            ledger = app.agentLedger(sessionId),
            toolPrefetch = app.jevToolPrefetch,
            routedApp = { turnRoute?.app },
            reflex = app.reflexForTurn,
            // Never from the lock screen: every action there waits for approval anyway.
            reflexInstant = { !fromLockscreen && app.securePrefs.reflexInstantEnabled.value },
        )

        // The conversation so far: in memory, or rebuilt from what was stored (after a restart).
        val history = conversations.history(sessionId)

        val callbacks = object : AgentLoop.Callbacks {
            override fun onToken(text: String) {
                try {
                    callback.onToken(text)
                } catch (_: RemoteException) {
                    Log.w(TAG, "Client disconnected during streaming")
                }
            }

            override fun onToolExecution(toolName: String) {
                try {
                    callback.onToolExecution(toolName)
                } catch (_: RemoteException) {}
            }

            override fun onToolResult(toolName: String, result: SkillResult, input: kotlinx.serialization.json.JsonObject?) {
                Log.d(TAG, "Tool result ($toolName): ${result::class.simpleName}")
                // A refusal onApprovalNeeded queued has been reported to the launcher already.
                if (result is SkillResult.Error && LauncherApprovalPolicy.isRefusalMessage(result.message)) return
                val rawData = when (result) {
                    is SkillResult.Success -> result.data
                    is SkillResult.ImageSuccess -> result.text
                    is SkillResult.Error -> "Error: ${result.message}"
                    is SkillResult.RequiresApproval -> "Requires approval: ${result.description}"
                }
                try {
                    // Capped: the detail shares the oneway buffer with the frames.
                    val formatted = ToolResultFormatter.formatForLauncher(toolName, rawData, input)
                    callback.onToolResult(toolName, formatted.summary, formatted.detail)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to format/send tool result", e)
                }

                // Agent display preview lifecycle
                if (toolName == "agent_display_create" && result !is SkillResult.Error) {
                    startDisplayCapture(sessionId, callback)
                } else if (toolName == "agent_display_destroy" || toolName == "agent_display_destroy_and_promote") {
                    stopDisplayCapture(sessionId, callback)
                }
            }

            override fun onAskUserDisplayed(request: org.ethereumphone.andyclaw.agent.AskUserRequest) {
                Log.i(TAG, "ask_user (launcher): ${request.questions.size} question(s)")
            }

            override fun onAgentStep(event: org.ethereumphone.andyclaw.autopilot.AutopilotEvent) {
                if (event.kind == org.ethereumphone.andyclaw.autopilot.AutopilotEvent.Kind.STARTED) {
                    // The autopilot creates the display itself; show it, and faster.
                    startDisplayCapture(sessionId, callback, AUTOPILOT_FRAME_INTERVAL_MS)
                } else {
                    // Once the run is DONE or FAILED, back to one frame a second.
                    followRunRate(sessionId, event.kind)
                }
                try {
                    callback.onAgentStep(agentStepJson(event))
                } catch (_: RemoteException) {
                } catch (_: AbstractMethodError) {
                    // Built against an older AIDL; nothing to deliver to.
                }
            }

            /** What the model is told about each call refused below, until the engine asks. */
            private val refusals = java.util.concurrent.ConcurrentHashMap<String, String>()

            private fun refusalKey(toolName: String?, toolInput: JsonObject?) = "$toolName|$toolInput"

            override suspend fun onApprovalNeeded(
                description: String,
                toolName: String?,
                toolInput: JsonObject?,
            ): Boolean {
                // Nobody can be asked mid-turn from the home screen. A plain request of the
                // user's own still runs, and so does the user's wallet, whose terminal-screen
                // confirmation is the approval; anything else sensitive, anything after the run
                // has read someone else's words, and anything from the lock screen waits for the
                // user as the exact call (LauncherApprovalPolicy).
                val name = toolName ?: "unknown"
                val effect = org.ethereumphone.andyclaw.safety.ToolEffects.of(
                    name, registry.getTools(tier).firstOrNull { it.name == name },
                )
                val tainted = agentLoop.currentRunToken.readThirdPartyContent
                val decision = LauncherApprovalPolicy.decide(
                    toolName = name,
                    effect = effect,
                    provenance = Provenance.USER,
                    readThirdPartyContent = tainted,
                    yolo = app.securePrefs.yoloMode.value,
                    fromLockscreen = fromLockscreen,
                )
                if (decision == LauncherApprovalPolicy.Decision.RUN) {
                    Log.i(TAG, "Approving '$name' for the user's own request ($effect): $description")
                    return true
                }
                val summary = org.ethereumphone.andyclaw.safety.ApprovalSummaries.of(name, toolInput)
                val entry = try {
                    app.pendingApprovalStore.queue(
                        org.ethereumphone.andyclaw.safety.PendingApprovalStore.Request(
                            source = if (fromLockscreen) LauncherApprovalPolicy.LOCKSCREEN_SOURCE else LauncherApprovalPolicy.SOURCE,
                            provenance = Provenance.USER.name,
                            toolName = name,
                            input = toolInput,
                            description = summary.title,
                            // The conversation on screen: the launcher shows it there, inline.
                            conversationId = sessionId,
                            // The turn's own ledger session (app.agentLedger(sessionId)).
                            ledgerSessionId = sessionId,
                            effect = effect.name,
                            toolReason = description,
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not queue pending approval: ${e.message}")
                    null
                }
                Log.w(TAG, "Not running '$name' in a launcher turn ($effect, read others' content: $tainted, " +
                    "lockscreen: $fromLockscreen); queued: ${entry != null}")
                refusals[refusalKey(toolName, toolInput)] =
                    if (entry != null) LauncherApprovalPolicy.QUEUED_FOR_MODEL else LauncherApprovalPolicy.NOT_QUEUED_FOR_MODEL
                // Told now: a call refused before it started never reaches onToolResult.
                try {
                    callback.onToolResult(
                        name,
                        if (entry != null) LauncherApprovalPolicy.awaitingSummary(summary.title)
                        else LauncherApprovalPolicy.notQueuedSummary(summary.title),
                        ToolResultFormatter.capDetail(org.ethereumphone.andyclaw.safety.ApprovalSummaries.asText(summary)),
                    )
                } catch (_: RemoteException) {}
                return false
            }

            override fun notApprovedMessage(toolName: String?, toolInput: JsonObject?): String? =
                refusals.remove(refusalKey(toolName, toolInput))

            override suspend fun onPermissionsNeeded(permissions: List<String>): Boolean {
                // Can't request permissions from a bound service - check if already granted
                val allGranted = permissions.all { perm ->
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        this@LauncherBindingService, perm
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                Log.i(TAG, "Permissions check: $permissions -> $allGranted")
                return allGranted
            }

            // One terminal callback per turn (TurnTerminal): whatever else reports an end later is
            // not the end, and must not put the display away under a turn still running either.
            override fun onComplete(fullText: String, tokenUsage: org.ethereumphone.andyclaw.agent.TokenUsageSnapshot?) {
                terminal.end {
                    // Stop this session's display capture if the agent left it running.
                    if (displayCaptures.containsKey(sessionId)) {
                        stopDisplayCapture(sessionId, callback)
                    }
                    try {
                        callback.onComplete(fullText)
                    } catch (_: RemoteException) {}
                }
            }

            override fun onError(error: Throwable) {
                terminal.end {
                    if (displayCaptures.containsKey(sessionId)) {
                        stopDisplayCapture(sessionId, callback)
                    }
                    try {
                        callback.onError(error.message ?: "Unknown error")
                    } catch (_: RemoteException) {}
                }
            }
        }

        // The Room session for persistence: the launcher's own id, created with it if new, so the
        // session list, resumeSession and deleteSession all know the conversation by that name.
        val titlePrefix = if (fromLockscreen) "Lockscreen: " else ""
        val dbSessionId = conversations.dbSessionId(sessionId, model.modelId, "$titlePrefix${prompt.take(50)}")

        val fullResponseText = StringBuilder()
        val wrappedCallbacks = object : AgentLoop.Callbacks by callbacks {
            override fun onComplete(fullText: String, tokenUsage: org.ethereumphone.andyclaw.agent.TokenUsageSnapshot?) {
                fullResponseText.append(fullText)
                callbacks.onComplete(fullText, tokenUsage)
                if (fromLockscreen) {
                    scope.launch {
                        try {
                            app.executiveSummaryManager.generateAndStoreForLockscreen(
                                prompt, fullText
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Lockscreen executive summary update failed", e)
                        }
                    }
                }
            }
        }

        agentLoop.run(prompt, history, wrappedCallbacks)

        // Both messages into the context for the next call in this session, and into Room.
        try {
            conversations.recordTurn(sessionId, dbSessionId, prompt, fullResponseText.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist launcher chat to database", e)
        }
    }
}
