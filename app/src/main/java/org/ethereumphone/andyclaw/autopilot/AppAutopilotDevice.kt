package org.ethereumphone.andyclaw.autopilot

import android.content.Context
import android.os.IAgentDisplayService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.ethereumphone.andyclaw.services.AgentDisplayAccessibilityService
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayBinder
import org.ethereumphone.andyclaw.ExecutionEngine.rethrowIfCancelled

/**
 * The agent display as the autopilot drives it.
 *
 * Reads go straight to the accessibility service in this process — no JSON, no binder round
 * trip through system_server and back. Node actions go there too when the target has a unique
 * view id; everything else is a coordinate gesture through `AgentDisplayService`. Every action
 * is followed by an event-driven settle, not a fixed sleep.
 */
class AppAutopilotDevice(
    private val context: Context? = null,
    /** Whether STOP was pressed for the run this device drives; see `AgentRunToken`. */
    private val stopCheck: () -> Boolean = AgentDisplayCapabilities.stopGeneration.let { base ->
        { AgentDisplayCapabilities.stoppedSince(base) }
    },
) : AutopilotDevice {

    private val service: IAgentDisplayService get() = AgentDisplayBinder.service()

    private val displayId: Int get() = service.displayId

    /** The last screen read, for redacting the replay frame captured alongside it. */
    @Volatile private var lastSnapshot: ScreenSnapshot? = null

    /** Where the OS's frame-quiet check runs; see [perform]. */
    private val frameCheckScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    override suspend fun isLaunchable(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val pm = context?.packageManager ?: return@withContext true
        try {
            pm.getLaunchIntentForPackage(packageName) != null
        } catch (e: Exception) {
            true // unknown is not "missing": let the launch itself decide
        }
    }

    override suspend fun appLabel(packageName: String): String? = withContext(Dispatchers.IO) {
        val pm = context?.packageManager ?: return@withContext null
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun ensureApp(packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val svc = service
            // A display the last STOP left latched drops every input, and one the model resized
            // would put the annotations, the live view's crop and the replay's redaction boxes
            // in the wrong place — re-create either. On a live display this is the OS's cheap
            // reuse path.
            if (svc.displayId < 0 || AgentDisplayCapabilities.latched() || !hasAutopilotGeometry(svc)) {
                svc.createAgentDisplay(WIDTH, HEIGHT, DPI)
            }
            AgentDisplayAccessibilityService.watchedDisplayId = svc.displayId
            if (snapshotNow()?.packageName == packageName) return@withContext true
            val seq = ScreenSettler.mark()
            svc.launchApp(packageName)
            ScreenSettler.await(seq, ScreenSettler.Kind.LAUNCH, packageName)
            true
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            Log.e(TAG, "ensureApp($packageName) failed", e)
            false
        }
    }

    private fun hasAutopilotGeometry(svc: IAgentDisplayService): Boolean = try {
        val info = org.json.JSONObject(svc.displayInfo ?: "{}")
        info.optInt("width", WIDTH) == WIDTH && info.optInt("height", HEIGHT) == HEIGHT && info.optInt("dpi", DPI) == DPI
    } catch (e: Exception) {
        true
    }

    override suspend fun snapshot(): ScreenSnapshot? = withContext(Dispatchers.IO) { snapshotNow() }

    private fun snapshotNow(): ScreenSnapshot? {
        val id = displayId
        if (id < 0) return null
        val snap = AgentDisplayAccessibilityService.instance?.snapshot(id, WIDTH, HEIGHT)
            // The accessibility service is not bound in this process: go through system_server.
            ?: SmartTreeParser.parse(service.accessibilityTree, WIDTH, HEIGHT)
        lastSnapshot = snap
        return snap
    }

    /** One frame for the replay, taken while Jev thinks, so it costs no step time. */
    override suspend fun captureFrame(step: Int): Unit = withContext(Dispatchers.IO) {
        // A private app's screen never goes into a replay the user can share.
        if (lastSnapshot?.packageName?.let(SensitiveApps::isSensitive) == true) return@withContext
        try {
            val svc = service
            val jpeg = if (AgentDisplayCapabilities.hasV2) svc.captureFrameScaled(REPLAY_WIDTH, 75)
            else svc.captureFrameWithQuality(60)
            if (jpeg != null && jpeg.isNotEmpty()) {
                org.ethereumphone.andyclaw.autopilot.replay.ReplayRecorder.addFrame(step, jpeg, lastSnapshot)
            }
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            Log.d(TAG, "replay frame skipped: ${e.message}")
        }
    }

    override suspend fun perform(option: StepOption, screen: ScreenSnapshot, plan: AutopilotPlan): ActionOutcome =
        withContext(Dispatchers.IO) {
            // Accessibility clicks do not pass through the OS's input latch, so this check is
            // what keeps a STOP pressed a moment ago from being followed by one more tap.
            if (stopRequested) return@withContext ActionOutcome(ok = false, changedScreen = false, error = "stopped")
            val target = option.elementId?.let { screen.byId(it) }
            val uniqueViewId = target?.viewId?.takeIf { vid -> screen.elements.count { it.viewId == vid } == 1 }
            val a11y = AgentDisplayAccessibilityService.instance
            val id = displayId
            val seq = ScreenSettler.mark()
            val started = SystemClock.uptimeMillis()

            val (result, kind) = try {
                when (option) {
                    is StepOption.Tap -> {
                        val r = if (uniqueViewId != null && a11y != null) a11y.doClickNode(id, uniqueViewId)
                        else service.tap(target!!.centerX.toFloat(), target.centerY.toFloat()).let { OK }
                        r to ScreenSettler.Kind.TAP
                    }
                    is StepOption.LongPress -> {
                        val r = if (uniqueViewId != null && a11y != null) a11y.doLongClickNode(id, uniqueViewId)
                        else service.longPress(target!!.centerX.toFloat(), target.centerY.toFloat(), 500).let { OK }
                        r to ScreenSettler.Kind.TAP
                    }
                    is StepOption.Type -> {
                        val text = plan.values[option.valueKey].orEmpty()
                        val r = if (uniqueViewId != null && a11y != null) {
                            a11y.doSetNodeText(id, uniqueViewId, text)
                        } else {
                            val svc = service
                            val x = target!!.centerX
                            val y = target.centerY
                            svc.tap(x.toFloat(), y.toFloat())
                            if (stopRequested) return@withContext ActionOutcome(ok = false, changedScreen = false, error = "stopped")
                            // Type only once the tapped field has focus: typing blind after a fixed
                            // wait overwrote whichever field still had it.
                            when (a11y?.typeIntoFocusedField(id, x, y, text)) {
                                AgentDisplayAccessibilityService.FocusedTyping.DONE -> OK
                                AgentDisplayAccessibilityService.FocusedTyping.NO_FOCUS -> """{"ok":false,"error":"field did not take focus"}"""
                                else -> {
                                    if (a11y == null) delay(80)
                                    svc.pressKeyWithMeta(KEYCODE_A, META_CTRL_ON)
                                    svc.inputText(text)
                                    OK
                                }
                            }
                        }
                        r to ScreenSettler.Kind.TYPE
                    }
                    is StepOption.ScrollForward, is StepOption.ScrollBackward -> {
                        val forward = option is StepOption.ScrollForward
                        val r = if (uniqueViewId != null && a11y != null) {
                            a11y.doScrollNode(id, uniqueViewId,
                                if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        } else {
                            val cx = target!!.centerX.toFloat()
                            val cy = target.centerY.toFloat()
                            val dy = if (forward) -SCROLL_PX else SCROLL_PX
                            service.swipe(cx, cy - dy / 2, cx, cy + dy / 2, 200)
                            OK
                        }
                        r to ScreenSettler.Kind.SCROLL
                    }
                    StepOption.Back -> {
                        service.pressBack()
                        OK to ScreenSettler.Kind.BACK
                    }
                    StepOption.Wait, StepOption.None -> OK to ScreenSettler.Kind.TAP
                }
            } catch (e: Exception) {
                rethrowIfCancelled(e)
                Log.w(TAG, "perform ${option.key} failed", e)
                return@withContext ActionOutcome(ok = false, changedScreen = false, error = e.message)
            }
            val actMs = SystemClock.uptimeMillis() - started
            if (!result.contains("\"ok\":true")) {
                val error = Regex("\"error\":\"([^\"]*)\"").find(result)?.groupValues?.get(1) ?: "failed"
                return@withContext ActionOutcome(ok = false, changedScreen = false, actMs = actMs, error = error)
            }
            // No accessibility event is not proof of no change: WebViews and canvas apps redraw
            // without announcing it. The OS's frame check runs alongside the event wait rather
            // than after it, so a quiet app costs the longer of the two, not their sum.
            // Detached: the binder wait cannot be interrupted, and withContext would otherwise sit
            // out the rest of it even when the event wait already had the answer.
            val visual = frameCheckScope.async { AgentDisplayCapabilities.visuallyChanged(quietMs = 100, timeoutMs = 600) }
            val settle = ScreenSettler.await(seq, kind)
            var changed = settle.changed
            var settleMs = settle.ms
            if (!changed) {
                val t = SystemClock.uptimeMillis()
                changed = visual.await() ?: false
                settleMs += SystemClock.uptimeMillis() - t
            } else {
                visual.cancel() // its answer is not needed; the binder wait finishes on its own
            }
            ActionOutcome(ok = true, changedScreen = changed, actMs = actMs, settleMs = settleMs)
        }

    override val stopRequested: Boolean get() = stopCheck()

    override suspend fun waitForSettle() {
        ScreenSettler.await(ScreenSettler.mark(), ScreenSettler.Kind.TAP)
        delay(150)
    }

    companion object {
        private const val TAG = "AppAutopilotDevice"
        const val WIDTH = 720
        const val HEIGHT = 720
        const val DPI = 240
        private const val OK = """{"ok":true}"""
        private const val KEYCODE_A = 29
        private const val META_CTRL_ON = 4096
        private const val SCROLL_PX = 300f
        private const val REPLAY_WIDTH = 480
    }
}
