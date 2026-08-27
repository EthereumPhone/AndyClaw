package org.ethereumphone.andyclaw.skills.builtin

import android.os.IAgentDisplayService
import android.os.IBinder
import android.util.Log

/**
 * The one place `agentdisplay` is looked up.
 *
 * `IAgentDisplayService` is a hidden framework interface, so it is reached through
 * `ServiceManager` by reflection rather than `getSystemService`. Both callers — the
 * display skill and the flow interpreter's driver — go through here so there is a
 * single reconnect path when `system_server` restarts and takes the binder with it.
 */
object AgentDisplayBinder {

    private const val TAG = "AgentDisplayBinder"

    @Volatile
    private var cached: IAgentDisplayService? = null

    /** The service, or null when it is not available (open tier, or system_server down). */
    fun serviceOrNull(): IAgentDisplayService? {
        cached?.let { svc ->
            if (svc.asBinder().isBinderAlive) return svc
            Log.w(TAG, "AgentDisplayService binder died, reconnecting")
            cached = null
        }
        val svc = try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "agentdisplay") as? IBinder
            binder?.let { IAgentDisplayService.Stub.asInterface(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get AgentDisplayService", e)
            null
        }
        cached = svc
        return svc
    }

    /** The service, or a thrown [IllegalStateException] — the display skill's contract. */
    fun service(): IAgentDisplayService =
        serviceOrNull() ?: throw IllegalStateException("AgentDisplayService not available")
}
