package org.ethereumphone.andyclaw.autopilot

import android.os.IAgentDisplayListener
import android.os.IAgentDisplayService
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayBinder
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Which `IAgentDisplayService` the running OS implements, and the v2 calls behind a check.
 *
 * AndyClaw ships inside the OS image, but a build can still meet an older system_server (a
 * rollback, a sideloaded APK). An older one answers an unknown transaction with an empty reply,
 * which the generated proxy reads as 0 — so `getAgentApiVersion() >= 2` is the whole check.
 */
object AgentDisplayCapabilities {

    private const val TAG = "AgentDisplayCaps"

    /** The service and its version together, so a reconnect can never pair one with the other's. */
    private class Probe(val service: IAgentDisplayService, val version: Int)

    @Volatile private var probe: Probe? = null

    fun apiVersion(): Int {
        val svc = AgentDisplayBinder.serviceOrNull() ?: return 0
        probe?.let { if (it.service === svc) return it.version }
        val v = try { svc.agentApiVersion } catch (e: Exception) { 0 }
        probe = Probe(svc, v)
        return v
    }

    val hasV2: Boolean get() = apiVersion() >= 2

    /** v3: node actions and launches honour STOP too, and the rear HUD knows HOLD and HANDOFF. */
    val hasV3: Boolean get() = apiVersion() >= 3

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

    // ---- STOP (the rear-screen hold, the launcher, the live view) ----

    /**
     * Bumped by every STOP. An agent run hears about a STOP through
     * [org.ethereumphone.andyclaw.skills.builtin.AgentDisplayLease.noteStop]; this counter is for
     * a caller outside any run, which compares it with the value it started with — so nothing
     * ever has to clear a flag, which used to be the next run's `beginRun()`, silently
     * cancelling a STOP pressed a moment before it.
     */
    private val stopGen = AtomicLong()

    private fun onStop() {
        stopGen.incrementAndGet()
        org.ethereumphone.andyclaw.skills.builtin.AgentDisplayLease.noteStop()
    }

    val stopGeneration: Long get() = stopGen.get()

    fun stoppedSince(baseline: Long): Boolean = stopGen.get() != baseline

    /**
     * The OS refused an action as `"stopped"`: STOP latched the display, and the listener's word of
     * it may not have reached this process yet. The answer says the same, so the run hears it now.
     */
    fun noteStopLatched() = onStop()

    /**
     * Whether the OS is still holding the display latched from a STOP. A latched display drops
     * every tap, key and text, so a new run that finds one live re-creates it first.
     */
    fun latched(): Boolean {
        if (!hasV2) return false
        return try {
            val stats = AgentDisplayBinder.service().frameStats ?: return false
            JSONObject(stats).optBoolean("stopped", false)
        } catch (e: Exception) {
            false
        }
    }

    // ---- Display state, for the live view's mirror ----

    const val STATE_UNKNOWN = -1
    const val STATE_RELEASED = 0
    const val STATE_LIVE = 1
    const val STATE_PARKED = 2

    private val _displayState = MutableStateFlow(STATE_UNKNOWN)

    /** What the OS last said about the agent display: [STATE_LIVE], [STATE_PARKED] or [STATE_RELEASED]. */
    val displayState: StateFlow<Int> = _displayState.asStateFlow()

    private val listener = object : IAgentDisplayListener.Stub() {
        override fun onStopRequested(source: String?) {
            Log.i(TAG, "stop requested by $source")
            onStop()
        }

        override fun onDisplayStateChanged(displayId: Int, state: Int) {
            _displayState.value = state
        }
    }

    @Volatile private var listenerRegisteredWith: IAgentDisplayService? = null

    /**
     * Makes sure the OS tells this process about STOPs and display changes. Called at process
     * start — a hold on the rear screen must reach a run that began before any autopilot did —
     * and again before each run, which re-registers after system_server restarted.
     */
    fun ensureListener() {
        if (!hasV2) return
        val svc = AgentDisplayBinder.serviceOrNull() ?: return
        if (listenerRegisteredWith === svc) return
        synchronized(this) {
            if (listenerRegisteredWith === svc) return
            try {
                svc.registerDisplayListener(listener)
                listenerRegisteredWith = svc
            } catch (e: Exception) {
                Log.d(TAG, "registerDisplayListener failed: ${e.message}")
            }
        }
    }

    /**
     * Stop the agent from the app (the live view's and the launcher's STOP). Latches the OS, so
     * input already queued is dropped, and ends the run that is using the display.
     */
    fun requestStop() {
        onStop()
        if (!hasV2) return
        try {
            AgentDisplayBinder.service().requestStop("app")
        } catch (e: Exception) {
            Log.d(TAG, "requestStop failed: ${e.message}")
        }
    }
}
