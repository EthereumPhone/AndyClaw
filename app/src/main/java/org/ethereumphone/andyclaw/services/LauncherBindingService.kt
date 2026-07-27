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
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.agent.AgentLoop
import org.ethereumphone.andyclaw.ipc.IExecSummaryCallback
import org.ethereumphone.andyclaw.ipc.ILauncherCallback
import org.ethereumphone.andyclaw.ipc.ILauncherService
import org.ethereumphone.andyclaw.summary.ExecutiveSummaryManager
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.LlmProvider
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.sessions.model.MessageRole
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

        /** Packages allowed to bind to this service. */
        private val ALLOWED_CALLER_PACKAGES = setOf(
            "org.ethosmobile.ethoslauncher",
            "com.android.systemui"
        )
    }

    /**
     * Validates that the calling process belongs to an authorised caller.
     * Throws [SecurityException] if the caller is not authorized.
     */
    private fun enforceCallerIsLauncher() {
        val callingUid = Binder.getCallingUid()
        val pm = packageManager
        val callerPackages = pm.getPackagesForUid(callingUid)
        if (callerPackages != null) {
            for (pkg in callerPackages) {
                if (pkg in ALLOWED_CALLER_PACKAGES) return
            }
        }
        val callerNames = callerPackages?.joinToString() ?: "unknown (uid=$callingUid)"
        Log.w(TAG, "Rejected IPC from unauthorized caller: $callerNames")
        throw SecurityException(
            "Only authorised packages may bind to LauncherBindingService. " +
            "Caller: $callerNames"
        )
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught coroutine error", throwable)
        }
    )

    /** Active display capture job for streaming frames to the launcher. */
    private var displayCaptureJob: Job? = null

    /** Active prompt jobs keyed by launcher sessionId, so we can cancel inference. */
    private val activePromptJobs = mutableMapOf<String, Job>()

    /** Per-session conversation histories for multi-turn support. */
    private val sessionHistories = mutableMapOf<String, MutableList<Message>>()

    /** Maps launcher sessionId → Room database sessionId for persistence. */
    private val dbSessionIds = mutableMapOf<String, String>()

    /** Tracks whether a memory reindex is in progress. */
    private val isReindexingFlag = AtomicBoolean(false)

    /** Currently registered exec summary streaming callback from the launcher. */
    private var execSummaryCallback: IExecSummaryCallback? = null

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
            val job = scope.launch {
                try {
                    runAgentLoop(prompt, sessionId, callback)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.i(TAG, "Inference cancelled for session $sessionId")
                    try {
                        callback.onError("Cancelled")
                    } catch (_: RemoteException) {}
                } catch (e: Exception) {
                    Log.e(TAG, "sendPrompt failed", e)
                    try {
                        callback.onError(e.message ?: "Unknown error")
                    } catch (_: RemoteException) {}
                } finally {
                    activePromptJobs.remove(sessionId)
                }
            }
            activePromptJobs[sessionId] = job
        }

        override fun transcribeAudio(audioFd: ParcelFileDescriptor, callback: ILauncherCallback) {
            enforceCallerIsLauncher()
            scope.launch {
                // Copy the audio data from the PFD to a local temp file so
                // WhisperTranscriber (which needs a file path) can access it.
                val tempFile = File(cacheDir, "launcher_audio_${System.currentTimeMillis()}.wav")
                try {
                    FileInputStream(audioFd.fileDescriptor).use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    audioFd.close()

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
            sessionHistories.remove(sessionId)
            dbSessionIds.remove(sessionId)
            Log.d(TAG, "Cleared session: $sessionId")
        }

        override fun sendLockscreenPrompt(
            prompt: String,
            sessionId: String,
            callback: ILauncherCallback,
        ) {
            enforceCallerIsLauncher()
            val job = scope.launch {
                try {
                    runAgentLoop(prompt, sessionId, callback, fromLockscreen = true)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.i(TAG, "Lockscreen inference cancelled for session $sessionId")
                    try {
                        callback.onError("Cancelled")
                    } catch (_: RemoteException) {}
                } catch (e: Exception) {
                    Log.e(TAG, "sendLockscreenPrompt failed", e)
                    try {
                        callback.onError(e.message ?: "Unknown error")
                    } catch (_: RemoteException) {}
                } finally {
                    activePromptJobs.remove(sessionId)
                }
            }
            activePromptJobs[sessionId] = job
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
                    val arr = JSONArray()
                    for (m in messages) {
                        arr.put(JSONObject().apply {
                            put("role", m.role.name.lowercase())
                            put("content", m.content)
                            put("timestamp", m.timestamp)
                        })
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
                put("notificationReplyEnabled", prefs.notificationReplyEnabled.value)
                put("executiveSummaryEnabled", prefs.executiveSummaryEnabled.value)
                put("heartbeatOnNotification", prefs.heartbeatOnNotificationEnabled.value)
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
                        prefs.setSelectedModel(AnthropicModels.defaultForProvider(provider).modelId)
                    }
                    "model" -> prefs.setSelectedModel(value)
                    "aiName" -> prefs.setAiName(value)
                    "yoloMode" -> prefs.setYoloMode(value.toBooleanStrict())
                    "safetyEnabled" -> prefs.setSafetyEnabled(value.toBooleanStrict())
                    "notificationReplyEnabled" -> prefs.setNotificationReplyEnabled(value.toBooleanStrict())
                    "executiveSummaryEnabled" -> prefs.setExecutiveSummaryEnabled(value.toBooleanStrict())
                    "heartbeatOnNotification" -> prefs.setHeartbeatOnNotificationEnabled(value.toBooleanStrict())
                    "heartbeatOnXmtpMessage" -> prefs.setHeartbeatOnXmtpMessageEnabled(value.toBooleanStrict())
                    "heartbeatIntervalMinutes" -> prefs.setHeartbeatIntervalMinutes(value.toInt())
                    "heartbeatUseSameModel" -> prefs.setHeartbeatUseSameModel(value.toBooleanStrict())
                    "heartbeatProvider" -> {
                        val p = LlmProvider.fromName(value) ?: return false
                        prefs.setHeartbeatProvider(p)
                    }
                    "heartbeatModel" -> prefs.setHeartbeatModel(value)
                    "smartRoutingEnabled" -> prefs.setSmartRoutingEnabled(value.toBooleanStrict())
                    "routingUseSameModel" -> prefs.setRoutingUseSameModel(value.toBooleanStrict())
                    "routingProvider" -> {
                        val p = LlmProvider.fromName(value) ?: return false
                        prefs.setRoutingProvider(p)
                    }
                    "routingModel" -> prefs.setRoutingModel(value)
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
            val app = application as? NodeApp ?: return
            runBlocking(Dispatchers.IO) {
                try {
                    app.sessionManager.deleteSession(sessionId)
                } catch (e: Exception) {
                    Log.e(TAG, "deleteSession failed", e)
                }
            }
            sessionHistories.remove(sessionId)
            dbSessionIds.remove(sessionId)
            Log.d(TAG, "Deleted session: $sessionId")
        }

        override fun resumeSession(sessionId: String) {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return
            runBlocking(Dispatchers.IO) {
                try {
                    val messages = app.sessionManager.getMessages(sessionId)
                    val history = mutableListOf<Message>()
                    for (m in messages) {
                        when (m.role) {
                            MessageRole.USER -> history.add(Message.user(m.content))
                            MessageRole.ASSISTANT -> history.add(
                                Message.assistant(listOf(ContentBlock.TextBlock(m.content)))
                            )
                            else -> {} // skip system/tool for agent loop reconstruction
                        }
                    }
                    sessionHistories[sessionId] = history
                    dbSessionIds[sessionId] = sessionId
                    Log.d(TAG, "Resumed session: $sessionId with ${history.size} messages")
                } catch (e: Exception) {
                    Log.e(TAG, "resumeSession failed", e)
                }
            }
        }

        override fun stopInference(sessionId: String) {
            enforceCallerIsLauncher()
            val job = activePromptJobs.remove(sessionId)
            if (job != null && job.isActive) {
                Log.i(TAG, "Stopping inference for session: $sessionId")
                job.cancel()
            } else {
                Log.d(TAG, "No active inference to stop for session: $sessionId")
            }
            // Also stop display capture if running
            displayCaptureJob?.cancel()
            displayCaptureJob = null
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

            // The agent wallet address is counterfactual/deterministic — once
            // known it never changes. Cache it so we never need an RPC again.
            //
            // This also fixes a crash: SubWalletSDK.getAddress() performs the
            // Alchemy eth_call on its OWN internal coroutine scope, so a network
            // failure there throws UNCAUGHT — our try/catch below cannot see it,
            // and it kills the whole AndyClaw process. (Repro: open dGent
            // settings while offline.) So: return the cached value if we have
            // one, and only ever touch the SDK when we're actually online.
            val cacheKey = "agent.wallet.cachedAddress"
            prefs.getString(cacheKey)?.takeIf { it.isNotBlank() }?.let { return it }
            if (!isOnline()) return null

            return runBlocking(Dispatchers.IO) {
                try {
                    val sdk = org.ethereumphone.subwalletsdk.SubWalletSDK(
                        context = this@LauncherBindingService,
                        web3jInstance = org.web3j.protocol.Web3j.build(
                            org.web3j.protocol.http.HttpService(
                                "https://eth-mainnet.g.alchemy.com/v2/${org.ethereumphone.andyclaw.BuildConfig.ALCHEMY_API}"
                            )
                        ),
                        bundlerRPCUrl = "https://api.pimlico.io/v2/1/rpc?apikey=${org.ethereumphone.andyclaw.BuildConfig.BUNDLER_API}",
                    )
                    val addr = sdk.getAddress()
                    if (!addr.isNullOrBlank()) prefs.putString(cacheKey, addr)
                    addr
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
            val arr = JSONArray()
            // Already newest-first from the store.
            for (entry in app.heartbeatLogStore.getAll()) {
                val toolCalls = JSONArray()
                for (tc in entry.toolCalls) {
                    toolCalls.put(JSONObject().apply {
                        put("toolName", tc.toolName)
                        put("result", tc.result)
                    })
                }
                arr.put(JSONObject().apply {
                    put("timestampMs", entry.timestampMs)
                    put("outcome", entry.outcome)
                    put("prompt", entry.prompt)
                    put("responseText", entry.responseText)
                    entry.error?.let { put("error", it) }
                    put("durationMs", entry.durationMs)
                    put("toolCalls", toolCalls)
                })
            }
            return arr.toString()
        }

        override fun clearHeartbeatLogs() {
            enforceCallerIsLauncher()
            val app = application as? NodeApp ?: return
            app.heartbeatLogStore.clear()
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
        val url = modelsUrlFromChatUrl(baseUrl)
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

    private fun modelsUrlFromChatUrl(chatUrl: String): String {
        val t = chatUrl.trim().trimEnd('/')
        return when {
            t.endsWith("/chat/completions") -> t.removeSuffix("/chat/completions") + "/models"
            t.endsWith("/v1") -> "$t/models"
            t.contains("/v1/") -> t.substringBefore("/v1/") + "/v1/models"
            else -> "$t/v1/models"
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
        scope.cancel()
        Log.i(TAG, "Service destroyed")
    }

    /**
     * Starts capturing frames from the agent virtual display and streaming them
     * to the launcher via [ILauncherCallback.onDisplayFrame].
     */
    private fun startDisplayCapture(callback: ILauncherCallback) {
        if (displayCaptureJob?.isActive == true) return
        try {
            callback.onDisplayCreated()
        } catch (_: RemoteException) {}

        displayCaptureJob = scope.launch {
            val svc = try {
                val smClass = Class.forName("android.os.ServiceManager")
                val getService = smClass.getMethod("getService", String::class.java)
                val binder = getService.invoke(null, "agentdisplay") as? IBinder
                binder?.let { IAgentDisplayService.Stub.asInterface(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get AgentDisplayService", e)
                null
            } ?: return@launch

            while (isActive) {
                try {
                    val frame = svc.captureFrame()
                    if (frame != null) {
                        callback.onDisplayFrame(frame)
                    }
                } catch (e: RemoteException) {
                    Log.w(TAG, "Client disconnected during display capture")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Display capture failed", e)
                }
                delay(1000)
            }
        }
    }

    /**
     * Stops the display capture loop and notifies the launcher.
     */
    private fun stopDisplayCapture(callback: ILauncherCallback) {
        displayCaptureJob?.cancel()
        displayCaptureJob = null
        try {
            callback.onDisplayDestroyed()
        } catch (_: RemoteException) {}
    }

    /**
     * Runs the full agent loop for a prompt, streaming tokens back to the launcher.
     */
    private suspend fun runAgentLoop(
        prompt: String,
        sessionId: String,
        callback: ILauncherCallback,
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
        val model = AnthropicModels.fromModelId(modelId) ?: AnthropicModels.MINIMAX_M25

        val enabledSkillIds = if (app.securePrefs.yoloMode.value) {
            registry.getAll().map { it.id }.toSet()
        } else {
            app.securePrefs.enabledSkills.value
        }
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
        )

        // Get or create conversation history for this session
        val history = sessionHistories.getOrPut(sessionId) { mutableListOf() }

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
                val rawData = when (result) {
                    is SkillResult.Success -> result.data
                    is SkillResult.ImageSuccess -> result.text
                    is SkillResult.Error -> "Error: ${result.message}"
                    is SkillResult.RequiresApproval -> "Requires approval: ${result.description}"
                }
                try {
                    val formatted = ToolResultFormatter.format(toolName, rawData, input)
                    callback.onToolResult(toolName, formatted.summary, formatted.detail)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to format/send tool result", e)
                }

                // Agent display preview lifecycle
                if (toolName == "agent_display_create" && result !is SkillResult.Error) {
                    startDisplayCapture(callback)
                } else if (toolName == "agent_display_destroy" || toolName == "agent_display_destroy_and_promote") {
                    stopDisplayCapture(callback)
                }
            }

            override fun onAskUserDisplayed(request: org.ethereumphone.andyclaw.agent.AskUserRequest) {
                Log.i(TAG, "ask_user (launcher): ${request.questions.size} question(s)")
            }

            override suspend fun onApprovalNeeded(
                description: String,
                toolName: String?,
                toolInput: JsonObject?,
            ): Boolean {
                // Auto-approve from launcher context (same as heartbeat)
                Log.i(TAG, "Auto-approving: $description")
                return true
            }

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

            override fun onComplete(fullText: String, tokenUsage: org.ethereumphone.andyclaw.agent.TokenUsageSnapshot?) {
                // Stop display capture if still running
                if (displayCaptureJob?.isActive == true) {
                    stopDisplayCapture(callback)
                }
                try {
                    callback.onComplete(fullText)
                } catch (_: RemoteException) {}
            }

            override fun onError(error: Throwable) {
                // Stop display capture if still running
                if (displayCaptureJob?.isActive == true) {
                    stopDisplayCapture(callback)
                }
                try {
                    callback.onError(error.message ?: "Unknown error")
                } catch (_: RemoteException) {}
            }
        }

        // Get or create the database session for persistence
        val sm = app.sessionManager
        val dbSessionId = dbSessionIds.getOrPut(sessionId) {
            val titlePrefix = if (fromLockscreen) "Lockscreen: " else ""
            val session = sm.createSession(
                model = model.modelId,
                title = "$titlePrefix${prompt.take(50)}",
            )
            session.id
        }

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

        // Add both user and assistant messages to history so the next call
        // in this session sees the full conversation.
        history.add(Message.user(prompt))
        if (fullResponseText.isNotEmpty()) {
            history.add(
                Message.assistant(listOf(ContentBlock.TextBlock(fullResponseText.toString())))
            )
        }

        // Persist to Room database
        try {
            sm.addMessage(dbSessionId, MessageRole.USER, prompt)
            if (fullResponseText.isNotEmpty()) {
                sm.addMessage(dbSessionId, MessageRole.ASSISTANT, fullResponseText.toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist launcher chat to database", e)
        }
    }
}
