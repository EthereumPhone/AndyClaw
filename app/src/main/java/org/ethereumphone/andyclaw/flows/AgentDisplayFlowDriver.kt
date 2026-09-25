package org.ethereumphone.andyclaw.flows

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.delay
import org.ethereumphone.andyclaw.autopilot.AgentDisplayCapabilities
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayBinder
import org.json.JSONObject
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

    override suspend fun clickNode(viewId: String, index: Int): Boolean {
        // The proxy selects a node by view id and nothing else, so a flow that wants
        // the second match of a repeated id cannot be replayed faithfully. Refusing is
        // the whole contract of this rung: abort, fall back, recompile — never guess
        // which row the user meant.
        if (index != 0) {
            Log.w(TAG, "clickNode($viewId, index=$index) refused — the a11y proxy has no index selector")
            return false
        }
        return ok(runCatching { AgentDisplayBinder.serviceOrNull()?.clickNode(viewId) }.getOrNull())
    }

    override suspend fun setNodeText(viewId: String, text: String): Boolean =
        ok(runCatching { AgentDisplayBinder.serviceOrNull()?.setNodeText(viewId, text) }.getOrNull())

    /** The node actions answer with `{"ok":true,"method":"..."}` or an error object. */
    private fun ok(result: String?): Boolean {
        if (result == null) return false
        return try {
            JSONObject(result).optBoolean("ok", false)
        } catch (e: Exception) {
            false
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
