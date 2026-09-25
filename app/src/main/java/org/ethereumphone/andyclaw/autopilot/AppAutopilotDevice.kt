package org.ethereumphone.andyclaw.autopilot

import android.os.IAgentDisplayService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
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
    private val onDisplayCreated: () -> Unit = {},
) : AutopilotDevice {

    private val service: IAgentDisplayService get() = AgentDisplayBinder.service()

    private val displayId: Int get() = service.displayId

    /** The last screen read, for redacting the replay frame captured alongside it. */
    @Volatile private var lastSnapshot: ScreenSnapshot? = null

    override suspend fun ensureApp(packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val svc = service
            if (svc.displayId < 0) svc.createAgentDisplay(WIDTH, HEIGHT, DPI)
            // Claim the display even when a prewarm created it, so the turn's cleanup puts it away.
            onDisplayCreated()
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
                            svc.tap(target!!.centerX.toFloat(), target.centerY.toFloat())
                            delay(80)
                            svc.pressKeyWithMeta(KEYCODE_A, META_CTRL_ON)
                            svc.inputText(text)
                            OK
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
            val settle = ScreenSettler.await(seq, kind)
            var changed = settle.changed
            var settleMs = settle.ms
            if (!changed) {
                // No accessibility event is not proof of no change: WebViews and canvas apps
                // redraw without announcing it. Ask the OS whether any frame visibly changed.
                val t = SystemClock.uptimeMillis()
                changed = AgentDisplayCapabilities.visuallyChanged(quietMs = 100, timeoutMs = 600) ?: false
                settleMs += SystemClock.uptimeMillis() - t
            }
            ActionOutcome(ok = true, changedScreen = changed, actMs = actMs, settleMs = settleMs)
        }

    override val stopRequested: Boolean get() = AgentDisplayCapabilities.stopRequested

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
