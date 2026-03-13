package org.ethereumphone.andyclaw.services

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.agent.AgentLoop
import org.ethereumphone.andyclaw.ipc.ILauncherCallback
import org.ethereumphone.andyclaw.ipc.ILauncherService
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.sessions.model.MessageRole
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities
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

    /** Per-session conversation histories for multi-turn support. */
    private val sessionHistories = mutableMapOf<String, MutableList<Message>>()

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
            scope.launch {
                try {
                    runAgentLoop(prompt, sessionId, callback)
                } catch (e: Exception) {
                    Log.e(TAG, "sendPrompt failed", e)
                    try {
                        callback.onError(e.message ?: "Unknown error")
                    } catch (_: RemoteException) {}
                }
            }
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
            Log.d(TAG, "Cleared session: $sessionId")
        }

        override fun sendLockscreenPrompt(
            prompt: String,
            sessionId: String,
            callback: ILauncherCallback,
        ) {
            enforceCallerIsLauncher()
            scope.launch {
                try {
                    runAgentLoop(prompt, sessionId, callback, fromLockscreen = true)
                } catch (e: Exception) {
                    Log.e(TAG, "sendLockscreenPrompt failed", e)
                    try {
                        callback.onError(e.message ?: "Unknown error")
                    } catch (_: RemoteException) {}
                }
            }
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
                try {
                    callback.onComplete(fullText)
                } catch (_: RemoteException) {}
            }

            override fun onError(error: Throwable) {
                try {
                    callback.onError(error.message ?: "Unknown error")
                } catch (_: RemoteException) {}
            }
        }

        val fullResponseText = StringBuilder()
        val wrappedCallbacks = object : AgentLoop.Callbacks by callbacks {
            override fun onComplete(fullText: String) {
                fullResponseText.append(fullText)
                callbacks.onComplete(fullText)
                // Update executive summary and save chat if this came from the lockscreen
                if (fromLockscreen) {
                    scope.launch {
                        try {
                            app.executiveSummaryManager.generateAndStoreForLockscreen(
                                prompt, fullText
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Lockscreen executive summary update failed", e)
                        }
                        try {
                            val sm = app.sessionManager
                            val session = sm.createSession(
                                model = model.modelId,
                                title = "Lockscreen: ${prompt.take(50)}",
                            )
                            sm.addMessage(session.id, MessageRole.USER, prompt)
                            sm.addMessage(session.id, MessageRole.ASSISTANT, fullText)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to save lockscreen chat session", e)
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
    }
}
