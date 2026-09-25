package org.ethereumphone.andyclaw.flows

import android.content.Context
import android.content.pm.PackageManager
import android.os.IAgentDisplayService
import android.util.Log
import kotlinx.coroutines.delay
import org.ethereumphone.andyclaw.autopilot.AgentDisplayCapabilities
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayBinder
import org.ethereumphone.andyclaw.ExecutionEngine.rethrowIfCancelled

/**
 * [FlowDisplayDriver] over the real `IAgentDisplayService` — the same binder methods
 * `AgentDisplaySkill` calls, with no model between them.
 *
 * Node actions only, and that is the point: there is no coordinate path here, so a flow
 * cannot reach one even if something managed to compile with coordinates in it.
 */
class AgentDisplayFlowDriver(
    private val context: Context,
    private val displayWidth: Int = DISPLAY_WIDTH,
    private val displayHeight: Int = DISPLAY_HEIGHT,
    private val displayDpi: Int = DISPLAY_DPI,
) : FlowDisplayDriver {

    override suspend fun installedVersion(packageName: String): String? = try {
        context.packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (e: Exception) {
        Log.w(TAG, "version lookup for $packageName failed: ${e.message}")
        null
    }

    override suspend fun ensureApp(packageName: String): Boolean {
        val service = AgentDisplayBinder.serviceOrNull() ?: return false
        return try {
            // A display the last STOP left latched would drop every step of the replay.
            if (service.displayId <= 0 || AgentDisplayCapabilities.latched()) {
                service.createAgentDisplay(displayWidth, displayHeight, displayDpi)
            }
            val current = try {
                service.currentActivity
            } catch (e: Exception) {
                null
            }
            if (current == null || !current.startsWith("$packageName/")) {
                service.launchApp(packageName)
                delay(LAUNCH_SETTLE_MS)
            }
            true
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            Log.w(TAG, "ensureApp($packageName) failed: ${e.message}")
            false
        }
    }

    override suspend fun uiTree(): String? = try {
        AgentDisplayBinder.serviceOrNull()?.accessibilityTree
    } catch (e: Exception) {
        Log.w(TAG, "uiTree failed: ${e.message}")
        null
    }

    override suspend fun clickNode(viewId: String, index: Int): FlowDispatch {
        // The proxy selects a node by view id and nothing else, so a flow that wants
        // the second match of a repeated id cannot be replayed faithfully. Refusing is
        // the whole contract of this rung: abort, fall back, recompile — never guess
        // which row the user meant.
        if (index != 0) {
            Log.w(TAG, "clickNode($viewId, index=$index) refused — the a11y proxy has no index selector")
            return FlowDispatch.NOT_DISPATCHED
        }
        return nodeAction("clickNode($viewId)") { it.clickNode(viewId) }
    }

    override suspend fun setNodeText(viewId: String, text: String): FlowDispatch =
        nodeAction("setNodeText($viewId)") { it.setNodeText(viewId, text) }

    /**
     * One node action, and what is known about whether it happened. No service means the call never
     * went out. An exception once it has gone out, or an answer saying the OS stopped waiting for
     * the app, means it may still happen — it used to read as "not tapped", and a replay that
     * believed that could let the task be done a second time. [FlowDispatch.ofNodeActionResult]
     * has the rest.
     */
    private fun nodeAction(what: String, call: (IAgentDisplayService) -> String?): FlowDispatch {
        val service = AgentDisplayBinder.serviceOrNull() ?: return FlowDispatch.NOT_DISPATCHED
        val answer = try {
            call(service)
        } catch (e: Exception) {
            Log.w(TAG, "$what threw; it may still have gone through, so it counts as possibly done: ${e.message}")
            return FlowDispatch.UNKNOWN
        }
        return FlowDispatch.ofNodeActionResult(answer).also {
            if (it != FlowDispatch.DONE) Log.w(TAG, "$what -> $it: ${answer?.take(200)}")
        }
    }

    companion object {
        private const val TAG = "AgentDisplayFlowDriver"
        // Same geometry AgentDisplaySkill creates, so a flow recorded through the skill
        // replays against an identically-sized screen.
        const val DISPLAY_WIDTH = 720
        const val DISPLAY_HEIGHT = 720
        const val DISPLAY_DPI = 240
        const val LAUNCH_SETTLE_MS = 1_200L
    }
}
