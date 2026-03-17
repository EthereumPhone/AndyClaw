package org.ethereumphone.andyclaw.services

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
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
import org.ethereumphone.andyclaw.ipc.ILauncherCallback
import org.ethereumphone.andyclaw.ipc.ILauncherService
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.LlmProvider
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.sessions.model.MessageRole
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities
import org.ethereumphone.andyclaw.ui.chat.ToolResultFormatter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

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
                put("isPrivileged", OsCapabilities.hasPrivilegedAccess)
                put("currentTier", OsCapabilities.currentTier().name)
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
                val isConfigured = when (provider) {
                    LlmProvider.ETHOS_PREMIUM -> OsCapabilities.hasPrivilegedAccess
                    LlmProvider.OPEN_ROUTER -> prefs.apiKey.value.isNotBlank()
                    LlmProvider.TINFOIL -> prefs.tinfoilApiKey.value.isNotBlank()
                    LlmProvider.CLAUDE_OAUTH -> prefs.claudeOauthRefreshToken.value.isNotBlank()
                    LlmProvider.OPENAI -> prefs.openaiApiKey.value.isNotBlank()
                    LlmProvider.VENICE -> prefs.veniceApiKey.value.isNotBlank()
                    LlmProvider.LOCAL -> true
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

        val agentLoop = AgentLoop(
            client = client,
            skillRegistry = registry,
            tier = tier,
            enabledSkillIds = if (app.securePrefs.yoloMode.value) {
                registry.getAll().map { it.id }.toSet()
            } else {
                app.securePrefs.enabledSkills.value
            },
            model = model,
            aiName = aiName,
            userStory = userStory,
            memoryManager = app.memoryManager,
            safetyLayer = app.createSafetyLayer(),
            skillRouter = if (app.securePrefs.smartRoutingEnabled.value) app.skillRouter else null,
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

            override fun onComplete(fullText: String) {
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
            override fun onComplete(fullText: String) {
                fullResponseText.append(fullText)
                callbacks.onComplete(fullText)
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
