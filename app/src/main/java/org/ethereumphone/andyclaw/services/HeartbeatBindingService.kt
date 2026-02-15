package org.ethereumphone.andyclaw.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.PowerManager
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.agent.HeartbeatAgentRunner
import org.ethereumphone.andyclaw.heartbeat.HeartbeatConfig
import org.ethereumphone.andyclaw.heartbeat.HeartbeatInstructions
import org.ethereumphone.andyclaw.ipc.IHeartbeatService

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
    }

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught exception in heartbeat scope", throwable)
        }
    )

    private var runtimeReady = false

    private val binder = object : IHeartbeatService.Stub() {
        override fun heartbeatNow() {
            enforceSystemCaller()
            Log.i(TAG, "heartbeatNow() called by OS (uid=${Binder.getCallingUid()})")
            performHeartbeat()
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
        ensureRuntimeReady()
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind()")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "HeartbeatBindingService destroyed")
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureRuntimeReady() {
        if (runtimeReady) return
        runtimeReady = true

        val app = application as NodeApp
        val runtime = app.runtime

        runtime.nativeSkillRegistry = app.nativeSkillRegistry
        runtime.anthropicClient = app.anthropicClient
        runtime.agentRunner = HeartbeatAgentRunner(app)

        runtime.heartbeatConfig = HeartbeatConfig(
            heartbeatFilePath = File(filesDir, "HEARTBEAT.md").absolutePath,
        )

        seedHeartbeatFile()
        runtime.initialize()

        Log.i(TAG, "Runtime initialized for OS-triggered heartbeat")
    }

    private fun performHeartbeat() {
        serviceScope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG,
            ).apply { setReferenceCounted(false) }

            try {
                wakeLock.acquire(HEARTBEAT_TIMEOUT_MS + 5000)
                Log.i(TAG, "Wake lock acquired, running heartbeat...")

                val result = withTimeoutOrNull(HEARTBEAT_TIMEOUT_MS) {
                    (application as NodeApp).runtime.requestHeartbeatNow()
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
    }

    private fun seedHeartbeatFile() {
        val file = File(filesDir, "HEARTBEAT.md")
        file.writeText(HeartbeatInstructions.CONTENT)
        Log.i(TAG, "Seeded HEARTBEAT.md")
    }
}
