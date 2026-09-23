package org.ethereumphone.andyclaw.autopilot

import android.os.IAgentDisplayListener
import android.os.IAgentDisplayService
import android.util.Log
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayBinder
import org.json.JSONObject

/**
 * Which `IAgentDisplayService` the running OS implements, and the v2 calls behind a check.
 *
 * AndyClaw ships inside the OS image, but a build can still meet an older system_server (a
 * rollback, a sideloaded APK). An older one answers an unknown transaction with an empty reply,
 * which the generated proxy reads as 0 — so `getAgentApiVersion() >= 2` is the whole check.
 */
object AgentDisplayCapabilities {

    private const val TAG = "AgentDisplayCaps"

    @Volatile private var cachedFor: IAgentDisplayService? = null
    @Volatile private var cachedVersion = 0

    fun apiVersion(): Int {
        val svc = AgentDisplayBinder.serviceOrNull() ?: return 0
        if (cachedFor === svc) return cachedVersion
        val v = try { svc.agentApiVersion } catch (e: Exception) { 0 }
        cachedFor = svc
        cachedVersion = v
        return v
    }

    val hasV2: Boolean get() = apiVersion() >= 2

    /**
     * Frame-quiet wait on the OS side. Returns true if the screen visibly changed while waiting,
     * false if not, null when unsupported.
     */
    fun visuallyChanged(quietMs: Long, timeoutMs: Long): Boolean? {
        if (!hasV2) return null
        return try {
            val json = AgentDisplayBinder.service().waitForIdle(quietMs, timeoutMs) ?: return null
            val o = JSONObject(json)
            if (o.has("error")) null else !o.optBoolean("noChange", true)
        } catch (e: Exception) {
            Log.d(TAG, "waitForIdle failed: ${e.message}")
            null
        }
    }

    /** Best-effort progress for the rear-screen HUD; the OS sanitises and rate-limits it. */
    fun setHud(json: JSONObject) {
        if (!hasV2) return
        try {
            AgentDisplayBinder.service().setHudState(json.toString())
        } catch (e: Exception) {
            Log.d(TAG, "setHudState failed: ${e.message}")
        }
    }

    // ---- Stop requests (the rear-screen hold-to-stop, or the app's own button) ----

    @Volatile var stopRequested = false
        private set

    private val listener = object : IAgentDisplayListener.Stub() {
        override fun onStopRequested(source: String?) {
            Log.i(TAG, "stop requested by $source")
            stopRequested = true
        }

        override fun onDisplayStateChanged(displayId: Int, state: Int) {}
    }

    @Volatile private var listenerRegisteredWith: IAgentDisplayService? = null

    /**
     * Agent-display tool calls and autopilot runs in this process. A prewarmed display that saw
     * none was not needed; one that saw any belongs to that session and must be left alone.
     */
    @Volatile var displayUses = 0
        private set

    fun noteDisplayUse() {
        displayUses++
    }

    /** Call when a run starts: clears a previous stop and makes sure we hear about the next one. */
    fun beginRun() {
        displayUses++
        stopRequested = false
        if (!hasV2) return
        val svc = AgentDisplayBinder.serviceOrNull() ?: return
        if (listenerRegisteredWith === svc) return
        try {
            svc.registerDisplayListener(listener)
            listenerRegisteredWith = svc
        } catch (e: Exception) {
            Log.d(TAG, "registerDisplayListener failed: ${e.message}")
        }
    }

    /** Stop the agent from the app (the live view's STOP button). */
    fun requestStop() {
        stopRequested = true
        if (!hasV2) return
        try {
            AgentDisplayBinder.service().requestStop("app")
        } catch (e: Exception) {
            Log.d(TAG, "requestStop failed: ${e.message}")
        }
    }
}
