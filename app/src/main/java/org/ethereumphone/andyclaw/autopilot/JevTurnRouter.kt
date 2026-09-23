package org.ethereumphone.andyclaw.autopilot

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.services.AgentDisplayAccessibilityService
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayBinder

/**
 * Hides app launch behind the planner.
 *
 * At the start of a turn, one Jev call (~100 ms, in parallel with everything else) asks whether
 * the request needs an app's screen and, if so, which app. When both answers are confident, the
 * app is launched on the agent display while the planner model is still writing its plan — so
 * by the time the autopilot starts, the app is already up and its first step reads the screen
 * instead of waiting a second for a cold launch.
 *
 * A wrong guess costs only a launch: the autopilot opens the app the plan names anyway. A display
 * prewarmed for nothing is put away after [UNUSED_DISPLAY_MS].
 */
class JevTurnRouter(
    private val context: Context,
    private val jev: () -> JevHttpClient?,
    private val enabled: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var apps: List<Pair<String, String>> = emptyList()
    @Volatile private var appsLoadedUptime = 0L

    /** Fire-and-forget. Never blocks or fails the turn. */
    fun prewarm(userMessage: String) {
        if (!enabled()) return
        val client = jev() ?: return
        if (userMessage.isBlank()) return
        scope.launch {
            try {
                route(client, userMessage)
            } catch (e: Exception) {
                Log.d(TAG, "prewarm skipped: ${e.message}")
            }
        }
    }

    private suspend fun route(client: JevHttpClient, message: String) {
        val candidates = rankedApps(message)
        // Only a message that names an installed app goes to Jev here. Everything else is not
        // an app task worth prewarming, and need not leave the device for this.
        val words = ElementRanker.tokens(message)
        val named = candidates.firstOrNull()?.let { (pkg, label) ->
            ElementRanker.tokens("$label ${pkg.replace('.', ' ')}").any { it in words && it.length > 2 }
        } ?: false
        if (!named) return
        val started = SystemClock.elapsedRealtime()
        val questions = mapOf(
            NEEDS_UI to JevQuestion.Noul(
                "Does fulfilling this request require operating an app's screen on the phone " +
                    "(opening an app and tapping, typing or reading in it)?"),
            APP to JevQuestion.Choice(
                "Which installed app would the request be carried out in?",
                candidates.associate { (pkg, label) -> pkg to "$label ($pkg)" } + (NONE to "None of these"),
            ),
        )
        val response = client.evaluate(JevRequest("USER REQUEST: $message", questions))
        val needsUi = response.noul(NEEDS_UI) ?: 0.0
        val app = response.choice(APP)
        Log.i(TAG, "route needsUi=${"%.2f".format(needsUi)} app=${app?.choice} " +
            "conf=${app?.confidence?.let { "%.2f".format(it) }} in ${SystemClock.elapsedRealtime() - started}ms")
        if (needsUi < THRESHOLD || app == null || app.choice == NONE || app.confidence < THRESHOLD) return
        prelaunch(app.choice)
    }

    private suspend fun prelaunch(packageName: String) {
        val svc = AgentDisplayBinder.serviceOrNull() ?: return
        val usesBefore = AgentDisplayCapabilities.displayUses
        if (svc.displayId < 0) svc.createAgentDisplay(AppAutopilotDevice.WIDTH, AppAutopilotDevice.HEIGHT, AppAutopilotDevice.DPI)
        AgentDisplayAccessibilityService.watchedDisplayId = svc.displayId
        svc.launchApp(packageName)
        Log.i(TAG, "prelaunched $packageName")
        delay(UNUSED_DISPLAY_MS)
        if (AgentDisplayCapabilities.displayUses == usesBefore) {
            // Nothing used it: the turn did not need the app after all. Park the display so
            // the app does not linger there, suppressed from the rear screen.
            try { svc.destroyAgentDisplay() } catch (_: Exception) {}
            Log.i(TAG, "prewarmed display unused; parked")
        }
    }

    /** Launchable apps, most relevant to [message] first, within Jev's option limit. */
    private fun rankedApps(message: String): List<Pair<String, String>> {
        if (SystemClock.elapsedRealtime() - appsLoadedUptime > APPS_TTL_MS || apps.isEmpty()) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            apps = pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .filter { it.first != context.packageName }
                // Never pre-launch a private app; the autopilot would refuse it anyway.
                .filterNot { SensitiveApps.isSensitive(it.first) }
            appsLoadedUptime = SystemClock.elapsedRealtime()
        }
        val words = ElementRanker.tokens(message)
        return apps
            .sortedByDescending { (pkg, label) -> ElementRanker.tokens("$label ${pkg.replace('.', ' ')}").count { it in words } }
            .take(MAX_APPS)
    }

    private companion object {
        const val TAG = "JevTurnRouter"
        const val NEEDS_UI = "needs_ui"
        const val APP = "app"
        const val NONE = "none"
        const val THRESHOLD = 0.85
        const val MAX_APPS = 200
        const val APPS_TTL_MS = 5 * 60_000L
        const val UNUSED_DISPLAY_MS = 20_000L
    }
}
