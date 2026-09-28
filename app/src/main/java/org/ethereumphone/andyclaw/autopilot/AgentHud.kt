package org.ethereumphone.andyclaw.autopilot

import android.os.SystemClock
import org.ethereumphone.andyclaw.skills.RunEnd
import org.json.JSONObject

/**
 * The rear-screen HUD for the run that holds the agent display.
 *
 * The autopilot reports every step, and after it hands over the model keeps driving with the
 * individual display tools, which used to leave the HUD blank. Both report here, and the run that
 * put progress on the rear screen ends it when it lets go of the display: DONE or ERROR, with the
 * step count and time so far, lingering through the park. A stopped run adds nothing — the OS is
 * already showing STOPPED.
 *
 * Only numbers, a phase, an action verb and short labels go out; the OS whitelists, filters and
 * rate-limits them again before anything reaches the screen.
 */
object AgentHud {

    private class Session(val runId: String, val startedUptime: Long) {
        var autopilotSteps = 0
        var manualSteps = 0
        var appLabel: String? = null
        val steps get() = autopilotSteps + manualSteps
    }

    @Volatile private var session: Session? = null

    private fun sessionFor(runId: String?): Session? {
        val id = runId ?: return session
        session?.let { if (it.runId == id) return it }
        return Session(id, SystemClock.uptimeMillis()).also { session = it }
    }

    /** An autopilot event, already turned into a HUD state by the tool handler. */
    fun autopilot(runId: String?, state: JSONObject) {
        sessionFor(runId)?.let { s ->
            s.autopilotSteps = state.optInt("step", s.autopilotSteps)
            state.optString("appLabel").takeIf { it.isNotBlank() }?.let { s.appLabel = it }
        }
        AgentDisplayCapabilities.setHud(state)
    }

    /** One of the model's own display actions. */
    fun action(runId: String?, verb: String, appLabel: String?) {
        val s = sessionFor(runId)
        if (s != null) {
            s.manualSteps++
            if (!appLabel.isNullOrBlank()) s.appLabel = appLabel
        }
        val state = JSONObject().put("phase", "RUNNING").put("action", verb)
        s?.let {
            state.put("step", it.steps).put("elapsedMs", SystemClock.uptimeMillis() - it.startedUptime)
            it.appLabel?.let { label -> state.put("appLabel", label) }
        }
        AgentDisplayCapabilities.setHud(state)
    }

    /** [runId] let go of the display. */
    fun endRun(runId: String, end: RunEnd) {
        val s = session?.takeIf { it.runId == runId } ?: return
        session = null
        if (end == RunEnd.STOPPED) return
        val elapsed = SystemClock.uptimeMillis() - s.startedUptime
        val state = JSONObject()
            .put("phase", if (end == RunEnd.OK) "DONE" else "ERROR")
            .put("step", s.steps)
            .put("elapsedMs", elapsed)
        if (s.steps > 0) {
            state.put("msPerStep", elapsed / s.steps)
            state.put("stepsPerSec", s.steps * 1000.0 / elapsed.coerceAtLeast(1))
        }
        s.appLabel?.let { state.put("appLabel", it) }
        AgentDisplayCapabilities.setHud(state)
    }

    /** The HUD's verb for an autopilot action key or a display tool. */
    fun verb(action: String): String = when (action) {
        "tap", "double_tap", "click_node" -> "TAP"
        // HOLD is new in OS v3; an older one would drop the field, so say TAP there.
        "long", "long_press", "long_click_node" -> if (AgentDisplayCapabilities.hasV3) "HOLD" else "TAP"
        "type", "type_text", "set_node_text" -> "TYPE"
        "scroll_fwd", "scroll_back", "reveal", "swipe", "fling", "drag", "pinch", "gesture", "scroll_node" -> "SCROLL"
        "back", "press_back" -> "BACK"
        "create", "launch_activity", "launch_intent" -> "OPEN"
        else -> "KEY"
    }
}
