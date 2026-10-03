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
import org.ethereumphone.andyclaw.llm.LlmClient
import org.ethereumphone.andyclaw.llm.LocalLlmClient
import org.ethereumphone.andyclaw.llm.TinfoilClient
import org.ethereumphone.andyclaw.llm.TinfoilProxyClient
import org.ethereumphone.andyclaw.services.AgentDisplayAccessibilityService
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayBinder
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayLease

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

    /**
     * The installed app Jev chose for one turn's request, confident enough to act on, whether or
     * not the request looked UI-bound enough to prelaunch it. The autopilot falls back to it when
     * the plan names a package that is not installed: the model guesses package names
     * (`com.google.android.calculator` on a phone whose calculator is `com.dgen.dgencalculator`),
     * and this is the one place that chose from what is actually on the phone.
     *
     * One per turn, handed to that turn's run (`AutopilotRunContext.routedApp`). It was one value
     * for the whole process: a cron run hours later, which never asks, opened the app the last
     * chat had been routed to, and two turns from different screens overwrote each other's.
     */
    class Route {
        @Volatile var app: String? = null
    }

    /**
     * Fire-and-forget. Never blocks or fails the turn. [client] is the model the turn runs on:
     * a confidential (Tinfoil) or on-device model is the user's choice that the request does not
     * leave for anyone else, and Jev is reached through the backend and OpenRouter.
     */
    fun prewarm(userMessage: String, client: LlmClient): Route {
        val turnRoute = Route()
        if (client is TinfoilProxyClient || client is TinfoilClient || client is LocalLlmClient) return turnRoute
        if (!enabled()) return turnRoute
        val jevClient = jev() ?: return turnRoute
        if (userMessage.isBlank()) return turnRoute
        scope.launch {
            try {
                route(jevClient, userMessage, turnRoute)
            } catch (e: Exception) {
                Log.d(TAG, "prewarm skipped: ${e.message}")
            }
        }
        return turnRoute
    }

    private suspend fun route(client: JevHttpClient, message: String, turnRoute: Route) {
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
        // Only on the turn that asked, which is the only one holding this route.
        if (app != null && app.choice != NONE && app.confidence >= THRESHOLD) {
            turnRoute.app = app.choice
        }
        if (needsUi < THRESHOLD || app == null || app.choice == NONE || app.confidence < THRESHOLD) return
        prelaunch(app.choice)
    }

    private suspend fun prelaunch(packageName: String) {
        // Another run is driving the display (a heartbeat mid-autopilot): launching an app on
        // it now would pull the screen out from under that task.
        if (AgentDisplayLease.isHeld()) return
        val svc = AgentDisplayBinder.serviceOrNull() ?: return
        val claimsBefore = AgentDisplayLease.claims
        // The isHeld() check above is only a hint: a run could claim the display between it and
        // the launch, and then have this app launched over its task. parkIfUnclaimed is the
        // lease's one "only while nobody has claimed since" section — it refuses if a claim came
        // in, and holds any claim arriving meanwhile until the block is done — so the create and
        // the launch run inside it, although nothing is parked here.
        val launched = AgentDisplayLease.parkIfUnclaimed(claimsBefore) {
            if (svc.displayId < 0 || AgentDisplayCapabilities.latched()) {
                svc.createAgentDisplay(AppAutopilotDevice.WIDTH, AppAutopilotDevice.HEIGHT, AppAutopilotDevice.DPI)
            }
            AgentDisplayAccessibilityService.watchedDisplayId = svc.displayId
            svc.launchApp(packageName)
        }
        if (!launched) {
            Log.i(TAG, "prelaunch of $packageName skipped: a run claimed the display")
            return
        }
        Log.i(TAG, "prelaunched $packageName")
        delay(UNUSED_DISPLAY_MS)
        // Nothing used it: the turn did not need the app after all. Park the display so the app
        // does not linger there, suppressed from the rear screen, and stop watching it at full
        // accessibility rate. Anything that did use it — a display tool, the autopilot, a flow
        // replay — claimed it, and puts it away itself when its run ends. Checked and parked as
        // one step, so a claim arriving meanwhile is never handed a display that is then parked.
        val parked = AgentDisplayLease.parkIfUnclaimed(claimsBefore) {
            AgentDisplayAccessibilityService.watchedDisplayId = android.view.Display.INVALID_DISPLAY
            try { svc.destroyAgentDisplay() } catch (_: Exception) {}
        }
        if (parked) Log.i(TAG, "prewarmed display unused; parked")
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
