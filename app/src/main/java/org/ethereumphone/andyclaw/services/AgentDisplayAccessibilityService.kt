package org.ethereumphone.andyclaw.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.IBinder
import android.os.IAgentAccessibilityProxy
import android.os.IAgentDisplayService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.ethereumphone.andyclaw.analyzer.ScreenAnalyzer
import org.ethereumphone.andyclaw.autopilot.ScreenElement
import org.ethereumphone.andyclaw.autopilot.ScreenSnapshot
import org.ethereumphone.andyclaw.autopilot.SensitiveApps
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * App-hosted accessibility service that provides UI tree queries for the
 * framework's AgentDisplayService via a binder callback proxy.
 *
 * Node actions use a hybrid approach: try the a11y performAction() first,
 * then fall back to coordinate-based input injection via the framework
 * service if performAction() fails (common on virtual displays).
 */
class AgentDisplayAccessibilityService : AccessibilityService() {

    /** Framework service reference for input-injection fallback. */
    private var frameworkService: IAgentDisplayService? = null

    private val proxy = object : IAgentAccessibilityProxy.Stub() {
        override fun getTreeForDisplay(displayId: Int): String =
            buildTreeForDisplay(displayId)

        override fun clickNodeByViewId(displayId: Int, viewId: String): String =
            doClickNode(displayId, viewId)

        override fun setNodeTextByViewId(displayId: Int, viewId: String, text: String): String =
            doSetNodeText(displayId, viewId, text)

        override fun longClickNodeByViewId(displayId: Int, viewId: String): String =
            doLongClickNode(displayId, viewId)

        override fun scrollNodeForwardByViewId(displayId: Int, viewId: String): String =
            doScrollNode(displayId, viewId, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

        override fun scrollNodeBackwardByViewId(displayId: Int, viewId: String): String =
            doScrollNode(displayId, viewId, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

        override fun focusNodeByViewId(displayId: Int, viewId: String): String =
            doFocusNode(displayId, viewId)

        override fun getNodeInfoByViewId(displayId: Int, viewId: String): String =
            doGetNodeInfo(displayId, viewId)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "onServiceConnected")

        val info = serviceInfo ?: run {
            Log.e(TAG, "serviceInfo is null")
            return
        }
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        serviceInfo = info
        instance = this
        applyEventConfig(watchedDisplayId != INVALID_DISPLAY)
        Log.i(TAG, "flags=0x${Integer.toHexString(info.flags)}")

        registerProxyWithFramework()
    }

    /**
     * Every event, unthrottled, only while a run is watching the agent display: the settle
     * detection measures the quiet between events, which the XML's 100 ms throttle would blur.
     * The rest of the time this service hears every app on the phone, so it asks for window
     * changes only, throttled — anything more is work on the main thread for nothing.
     */
    private fun applyEventConfig(watching: Boolean) {
        val info = serviceInfo ?: return
        val types = if (watching) AccessibilityEvent.TYPES_ALL_MASK
        else AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val timeout = if (watching) 0L else IDLE_NOTIFICATION_TIMEOUT_MS
        if (info.eventTypes == types && info.notificationTimeout == timeout) return
        info.eventTypes = types
        info.notificationTimeout = timeout
        try {
            serviceInfo = info
        } catch (e: Exception) {
            Log.w(TAG, "could not update the event config: ${e.message}")
        }
    }

    /**
     * Only counts. The tree is still queried on demand; events just tell the autopilot when the
     * agent display has stopped changing (see `ScreenSettler`). Runs on the main thread, so it
     * must stay this cheap.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val watched = watchedDisplayId
        if (watched == INVALID_DISPLAY || event.displayId != watched) return
        val now = SystemClock.uptimeMillis()
        lastEventUptime = now
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                lastWindowChangeUptime = now
                event.packageName?.let { lastWindowChangePackage = it.toString() }
            }
        }
        eventSeq.incrementAndGet()
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        frameworkService = null
        Log.i(TAG, "onDestroy")
    }

    // ---- proxy registration ----

    private fun registerProxyWithFramework() {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "agentdisplay") as? IBinder
            if (binder == null) {
                Log.e(TAG, "agentdisplay service binder is null")
                return
            }
            val service = IAgentDisplayService.Stub.asInterface(binder)
            frameworkService = service
            service.registerAccessibilityProxy(proxy)
            Log.i(TAG, "Proxy registered with AgentDisplayService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register proxy with AgentDisplayService", e)
        }
    }

    // ========================================================================
    // Node actions — hybrid: try a11y performAction, fall back to input injection
    // ========================================================================

    internal fun doClickNode(displayId: Int, viewId: String): String {
        Log.i(DTAG, "A11Y_CLICK_NODE: viewId=$viewId displayId=$displayId")
        val node = try {
            findNodeByViewId(displayId, viewId)
        } catch (e: PrivateNodeException) {
            return privateRefusal(e)
        }
            ?: run {
                Log.e(DTAG, "A11Y_CLICK_NODE: node NOT FOUND: $viewId")
                return JSONObject().put("ok", false).put("error", "Node not found: $viewId").toString()
            }

        // Try a11y action first
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            node.recycle()
            Log.d(TAG, "clickNode $viewId -> a11y OK")
            Log.i(DTAG, "A11Y_CLICK_NODE: $viewId -> a11y performAction OK")
            return """{"ok":true,"method":"a11y"}"""
        }

        // Fallback: tap at node center via framework input injection
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        node.recycle()
        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()
        return try {
            frameworkService?.tap(cx, cy)
                ?: return """{"ok":false,"error":"Framework service unavailable for tap fallback"}"""
            Log.d(TAG, "clickNode $viewId -> tap fallback ($cx, $cy)")
            """{"ok":true,"method":"tap","x":$cx,"y":$cy}"""
        } catch (e: Exception) {
            Log.e(TAG, "clickNode tap fallback failed for $viewId", e)
            JSONObject().put("ok", false).put("error", "Tap fallback failed: ${e.message}").toString()
        }
    }

    internal fun doLongClickNode(displayId: Int, viewId: String): String {
        val node = try {
            findNodeByViewId(displayId, viewId)
        } catch (e: PrivateNodeException) {
            return privateRefusal(e)
        }
            ?: return JSONObject().put("ok", false).put("error", "Node not found: $viewId").toString()

        if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            node.recycle()
            Log.d(TAG, "longClickNode $viewId -> a11y OK")
            return """{"ok":true,"method":"a11y"}"""
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        node.recycle()
        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()
        return try {
            frameworkService?.longPress(cx, cy, 500)
                ?: return """{"ok":false,"error":"Framework service unavailable for longPress fallback"}"""
            Log.d(TAG, "longClickNode $viewId -> longPress fallback ($cx, $cy)")
            """{"ok":true,"method":"longPress","x":$cx,"y":$cy}"""
        } catch (e: Exception) {
            Log.e(TAG, "longClickNode longPress fallback failed for $viewId", e)
            JSONObject().put("ok", false).put("error", "LongPress fallback failed: ${e.message}").toString()
        }
    }

    internal fun doSetNodeText(displayId: Int, viewId: String, text: String): String {
        Log.i(DTAG, "A11Y_SET_TEXT: viewId=$viewId text=\"${text.take(50)}\" displayId=$displayId")
        val node = try {
            findNodeByViewId(displayId, viewId)
        } catch (e: PrivateNodeException) {
            return privateRefusal(e)
        }
            ?: run {
                Log.e(DTAG, "A11Y_SET_TEXT: node NOT FOUND: $viewId")
                return JSONObject().put("ok", false).put("error", "Node not found: $viewId").toString()
            }

        // Try a11y ACTION_SET_TEXT first
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            node.recycle()
            Log.d(TAG, "setNodeText $viewId -> a11y OK")
            return """{"ok":true,"method":"a11y"}"""
        }

        // Fallback: tap it, and type only once it really has the focus
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        node.recycle()
        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()
        return try {
            val svc = frameworkService
                ?: return """{"ok":false,"error":"Framework service unavailable for text fallback"}"""
            svc.tap(cx, cy) // tap to focus
            when (typeIntoFocusedField(displayId, cx.toInt(), cy.toInt(), text)) {
                FocusedTyping.DONE -> """{"ok":true,"method":"focused","x":$cx,"y":$cy}"""
                FocusedTyping.NO_FOCUS -> """{"ok":false,"error":"field did not take focus"}"""
                FocusedTyping.UNVERIFIED -> {
                    svc.pressKeyWithMeta(29 /* KEYCODE_A */, 4096 /* META_CTRL_ON */) // Ctrl+A select all
                    svc.inputText(text)
                    Log.d(TAG, "setNodeText $viewId -> type fallback ($cx, $cy)")
                    """{"ok":true,"method":"type","x":$cx,"y":$cy}"""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "setNodeText type fallback failed for $viewId", e)
            JSONObject().put("ok", false).put("error", "Type fallback failed: ${e.message}").toString()
        }
    }

    /** How [typeIntoFocusedField] went. */
    enum class FocusedTyping {
        /** The field took the text and reads it back. */
        DONE,
        /** No editable field near the tap took input focus: typing now would land elsewhere. */
        NO_FOCUS,
        /** The right field has focus but would not take the text directly; type it as keys. */
        UNVERIFIED,
    }

    /**
     * Types [text] into the field just tapped at ([x], [y]) — once, and only there.
     *
     * The old way was: tap, wait a fixed 80 ms, select-all and type into whatever had focus. When
     * focus had not moved yet, that selected and overwrote a *different* field. Now the focused
     * editable node is polled for up to [waitMs]; the text is set on that node and read back.
     * Some WebView and React fields accept ACTION_SET_TEXT without taking it — then the caller
     * types it as keys, into the field that is now known to have focus.
     */
    fun typeIntoFocusedField(displayId: Int, x: Int, y: Int, text: String, waitMs: Long = FOCUS_WAIT_MS): FocusedTyping {
        val deadline = SystemClock.uptimeMillis() + waitMs
        while (true) {
            val node = focusedEditable(displayId)
            if (node != null) {
                try {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    val near = bounds.contains(x, y) ||
                        (Math.abs(bounds.centerX() - x) < NEAR_PX && Math.abs(bounds.centerY() - y) < NEAR_PX)
                    if (near) {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                        }
                        val set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        node.refresh()
                        return if (set && node.text?.toString() == text) FocusedTyping.DONE else FocusedTyping.UNVERIFIED
                    }
                } finally {
                    try { node.recycle() } catch (_: Exception) {}
                }
            }
            if (SystemClock.uptimeMillis() >= deadline) return FocusedTyping.NO_FOCUS
            Thread.sleep(FOCUS_POLL_MS)
        }
    }

    /** The editable node holding input focus on [displayId], never a private app's; or null. */
    private fun focusedEditable(displayId: Int): AccessibilityNodeInfo? {
        val windows = windowsOnAllDisplays.get(displayId) ?: return null
        for (window in windows) {
            val root = window.root ?: continue
            val focused = try {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            } ?: continue
            if (focused.isEditable && !SensitiveApps.isSensitive(focused.packageName?.toString())) return focused
            try { focused.recycle() } catch (_: Exception) {}
        }
        return null
    }

    internal fun doScrollNode(displayId: Int, viewId: String, action: Int): String {
        val directionStr = if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) "forward" else "backward"
        Log.i(DTAG, "A11Y_SCROLL_NODE: viewId=$viewId direction=$directionStr displayId=$displayId")
        val node = try {
            findNodeByViewId(displayId, viewId)
        } catch (e: PrivateNodeException) {
            return privateRefusal(e)
        }
            ?: run {
                Log.e(DTAG, "A11Y_SCROLL_NODE: node NOT FOUND: $viewId")
                return JSONObject().put("ok", false).put("error", "Node not found: $viewId").toString()
            }

        if (node.performAction(action)) {
            node.recycle()
            Log.d(TAG, "scrollNode $viewId $directionStr -> a11y OK")
            return """{"ok":true,"method":"a11y"}"""
        }

        // Fallback: swipe within bounds
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        node.recycle()
        val cx = bounds.centerX().toFloat()
        val quarterH = bounds.height() / 4f
        return try {
            val svc = frameworkService
                ?: return """{"ok":false,"error":"Framework service unavailable for scroll fallback"}"""
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                // Swipe up (content moves up = scroll forward)
                svc.swipe(cx, bounds.bottom - quarterH, cx, bounds.top + quarterH, 200)
            } else {
                // Swipe down (content moves down = scroll backward)
                svc.swipe(cx, bounds.top + quarterH, cx, bounds.bottom - quarterH, 200)
            }
            Log.d(TAG, "scrollNode $viewId $directionStr -> swipe fallback")
            """{"ok":true,"method":"swipe"}"""
        } catch (e: Exception) {
            Log.e(TAG, "scrollNode swipe fallback failed for $viewId", e)
            JSONObject().put("ok", false).put("error", "Scroll fallback failed: ${e.message}").toString()
        }
    }

    private fun doFocusNode(displayId: Int, viewId: String): String {
        val node = try {
            findNodeByViewId(displayId, viewId)
        } catch (e: PrivateNodeException) {
            return privateRefusal(e)
        }
            ?: return JSONObject().put("ok", false).put("error", "Node not found: $viewId").toString()

        if (node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
            node.recycle()
            Log.d(TAG, "focusNode $viewId -> a11y OK")
            return """{"ok":true,"method":"a11y"}"""
        }

        // Fallback: tap to focus
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        node.recycle()
        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()
        return try {
            frameworkService?.tap(cx, cy)
                ?: return """{"ok":false,"error":"Framework service unavailable for focus fallback"}"""
            Log.d(TAG, "focusNode $viewId -> tap fallback ($cx, $cy)")
            """{"ok":true,"method":"tap","x":$cx,"y":$cy}"""
        } catch (e: Exception) {
            Log.e(TAG, "focusNode tap fallback failed for $viewId", e)
            JSONObject().put("ok", false).put("error", "Focus fallback failed: ${e.message}").toString()
        }
    }

    private fun doGetNodeInfo(displayId: Int, viewId: String): String {
        val node = try {
            findNodeByViewId(displayId, viewId)
        } catch (e: PrivateNodeException) {
            return privateRefusal(e, key = "error")
        }
            ?: return JSONObject().put("error", "Node not found: $viewId").toString()
        return try {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            JSONObject().apply {
                put("viewId", node.viewIdResourceName ?: viewId)
                put("className", node.className?.toString() ?: "")
                node.text?.let { put("text", it.toString()) }
                node.contentDescription?.let { put("contentDescription", it.toString()) }
                put("bounds", JSONObject().apply {
                    put("left", bounds.left)
                    put("top", bounds.top)
                    put("right", bounds.right)
                    put("bottom", bounds.bottom)
                })
                put("enabled", node.isEnabled)
                put("clickable", node.isClickable)
                put("scrollable", node.isScrollable)
                put("focused", node.isFocused)
                put("checked", node.isChecked)
                put("selected", node.isSelected)
                put("editable", node.isEditable)
                put("childCount", node.childCount)
            }.toString()
        } catch (e: Exception) {
            Log.e(TAG, "getNodeInfo $viewId failed", e)
            JSONObject().put("error", e.message ?: "failed").toString()
        } finally {
            node.recycle()
        }
    }

    // ========================================================================
    // Node lookup — strict display targeting, safe recycling
    // ========================================================================

    /** Thrown by [findNodeByViewId] instead of handing out a private app's node. */
    private class PrivateNodeException(val packageName: String) : Exception("private app")

    /** A private app's package among [windows] of [type] (or all app and system windows). */
    private fun windowPackages(windows: List<AccessibilityWindowInfo>, type: Int? = null): List<String> =
        windows.filter { type == null || it.type == type }.mapNotNull { w ->
            val root = w.root ?: return@mapNotNull null
            try { root.packageName?.toString() } finally { try { root.recycle() } catch (_: Exception) {} }
        }

    /**
     * The private app ([SensitiveApps]) open among the agent display's app windows, or null.
     * For the frame capture: a private app's screen is neither streamed nor recorded.
     */
    /** Whether any window on [displayId] can be read at all. */
    fun hasWindowsOn(displayId: Int): Boolean = !windowsOnAllDisplays.get(displayId).isNullOrEmpty()

    fun sensitivePackageOnDisplay(displayId: Int): String? {
        val windows = windowsOnAllDisplays.get(displayId) ?: return null
        return SensitiveApps.sensitiveAmong(windowPackages(windows, AccessibilityWindowInfo.TYPE_APPLICATION))
    }

    /** The refusal a node action or a node read answers with for a private app. */
    private fun privateRefusal(e: PrivateNodeException, key: String = "ok"): String =
        JSONObject().apply {
            if (key == "ok") put("ok", false)
            put("error", "${e.packageName} is a private app; the agent does not operate or read it")
        }.toString()

    /**
     * The node with [viewId] on [displayId]. Never a private app's: FLAG_SECURE hides a window from
     * frame captures, not from the tree, and one node read at a time is still the whole screen.
     */
    private fun findNodeByViewId(displayId: Int, viewId: String): AccessibilityNodeInfo? {
        val allWindows = windowsOnAllDisplays
        val windows = allWindows.get(displayId)
        if (windows.isNullOrEmpty()) {
            Log.w(TAG, "findNodeByViewId: no windows on display $displayId (available: ${
                (0 until allWindows.size()).joinToString { "${allWindows.keyAt(it)}" }
            })")
            return null
        }

        for (window in windows) {
            val root = window.getRoot() ?: continue
            root.packageName?.toString()?.takeIf(SensitiveApps::isSensitive)?.let { pkg ->
                try { root.recycle() } catch (_: Exception) {}
                throw PrivateNodeException(pkg)
            }
            val found = root.findAccessibilityNodeInfosByViewId(viewId)
            // Don't recycle root before using found nodes — found nodes may
            // reference internal state tied to the root's connection.
            if (!found.isNullOrEmpty()) {
                // Return the first match, recycle extras
                for (i in 1 until found.size) found[i].recycle()
                root.recycle()
                return found[0]
            }
            root.recycle()
        }
        return null
    }

    // ========================================================================
    // Smart analysis (A11yJSONExpert ScreenAnalyzer)
    // ========================================================================

    /**
     * Build an actionable JSON using ScreenAnalyzer for the given display.
     * Produces clean, flat, semantic elements optimized for LLM consumption.
     */
    fun buildSmartTreeForDisplay(displayId: Int): String {
        Log.i(TAG, "buildSmartTreeForDisplay: displayId=$displayId")
        Log.i(DTAG, "SMART_ANALYSIS_START: displayId=$displayId")
        return try {
            val allWindows = windowsOnAllDisplays

            // Only ever the agent display's own windows. Falling back to getWindows() when it had
            // none handed the model the *main* screen — whatever the user had open.
            // Nullable: a display between two activities has no entry at all, and a non-null
            // declaration here threw before the empty check below could answer "no windows".
            val windows: List<AccessibilityWindowInfo>? = allWindows.get(displayId)
            if (windows.isNullOrEmpty()) {
                Log.w(DTAG, "SMART_ANALYSIS: no windows found for displayId=$displayId")
                return """{"screen":{},"elements":[],"scrollable":false}"""
            }
            Log.i(DTAG, "SMART_ANALYSIS: found ${windows.size} window(s) on displayId=$displayId")

            // Find the primary application window and analyze it
            val appWindow = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?: windows.firstOrNull()
            Log.i(DTAG, "SMART_ANALYSIS: appWindow type=${appWindow?.type} title=${appWindow?.title}")

            val root = appWindow?.getRoot()
            if (root == null) {
                Log.w(TAG, "buildSmartTreeForDisplay: no root node")
                Log.w(DTAG, "SMART_ANALYSIS: no root node — returning empty")
                return """{"screen":{},"elements":[],"scrollable":false}"""
            }

            try {
                val result = ScreenAnalyzer.analyze(root)
                Log.i(DTAG, "SMART_ANALYSIS_DONE: package=${result.screen.packageName} title=${result.screen.title} elements=${result.elements.size} scrollable=${result.scrollable}")
                result.toJsonString()
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in smart tree analysis", e)
            Log.e(DTAG, "SMART_ANALYSIS_ERROR: ${e.message}", e)
            JSONObject().put("error", e.message ?: "failed").toString()
        }
    }

    // ========================================================================
    // In-process snapshot for the autopilot
    // ========================================================================

    /**
     * The whole agent display as the autopilot sees it, read without leaving this process.
     *
     * Unlike [buildSmartTreeForDisplay] — whose output feeds flow checksums and so must not
     * change shape — this looks at every window on the display: an app dialog, a system prompt
     * and the keyboard are all part of what the next action depends on. Elements are numbered
     * across windows, topmost window first; `window` tells them apart.
     */
    fun snapshot(displayId: Int, width: Int, height: Int): ScreenSnapshot? {
        val windows = windowsOnAllDisplays.get(displayId)
        if (windows.isNullOrEmpty()) return null
        // A private app anywhere among the app windows — under a dialog, beside another app —
        // means none of the screen is read: its package is reported and nothing else, and the
        // autopilot stops there. System windows are left out of the check because SystemUI,
        // itself on the private list, draws the text toasts every app shows.
        SensitiveApps.sensitiveAmong(windowPackages(windows, AccessibilityWindowInfo.TYPE_APPLICATION))?.let { pkg ->
            return ScreenSnapshot(packageName = pkg, title = null, elements = emptyList(), width = width, height = height)
        }
        val elements = ArrayList<ScreenElement>()
        val others = ArrayList<String>()
        var packageName: String? = null
        var title: String? = null
        var scrollable = false
        var keyboardVisible = false
        var windowIndex = 0
        for (window in windows) {
            when (window.type) {
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> {
                    keyboardVisible = true
                    continue
                }
                AccessibilityWindowInfo.TYPE_APPLICATION, AccessibilityWindowInfo.TYPE_SYSTEM -> Unit
                else -> continue
            }
            val root = window.root ?: continue
            try {
                val result = ScreenAnalyzer.analyze(root)
                if (packageName == null && window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    packageName = result.screen.packageName
                    title = result.screen.title
                    scrollable = result.scrollable
                } else {
                    others += if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM) "system" else "dialog"
                }
                for (e in result.elements) {
                    // A list lays out rows past its visible end ahead of a scroll. Such a row is
                    // not on the screen: a tap at its centre (y=920 on a 720-high display) lands
                    // on nothing. Keep only what shows, and aim at the part that shows.
                    val left = e.bounds.x.coerceAtLeast(0)
                    val top = e.bounds.y.coerceAtLeast(0)
                    val right = (e.bounds.x + e.bounds.w).coerceAtMost(width)
                    val bottom = (e.bounds.y + e.bounds.h).coerceAtMost(height)
                    if (right <= left || bottom <= top) continue
                    elements += ScreenElement(
                        id = elements.size,
                        type = e.type.jsonName,
                        label = e.label,
                        summary = e.summary,
                        hint = e.hint,
                        value = e.value,
                        checked = e.checked,
                        enabled = e.enabled != false,
                        selected = e.selected == true,
                        password = e.password == true,
                        viewId = e.viewId,
                        actions = e.actions,
                        centerX = (left + right) / 2,
                        centerY = (top + bottom) / 2,
                        window = windowIndex,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                    )
                }
                // ScreenAnalyzer folds a list into its rows, so a row below the fold had no way to
                // be reached: the autopilot offers scrolling only on an element that scrolls, and
                // there was none. Settings' "Display" and the rest of a long page were out of reach.
                // Listed after the rows so a row's number does not depend on them.
                for (list in scrollableLists(root, width, height)) {
                    elements += list.copy(id = elements.size, window = windowIndex)
                }
                windowIndex++
            } catch (e: Exception) {
                Log.w(TAG, "snapshot: analysis failed for window ${window.id}", e)
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        }
        return ScreenSnapshot(
            packageName = packageName.orEmpty(),
            title = title,
            elements = elements,
            scrollable = scrollable,
            keyboardVisible = keyboardVisible,
            windows = others,
            width = width,
            height = height,
        )
    }

    /**
     * The scrollable lists on the window under [root] that show on the display and can still
     * move, outermost first, at most [MAX_SCROLL_LISTS]. A list inside a list is left to scroll
     * with its parent.
     */
    private fun scrollableLists(root: AccessibilityNodeInfo, width: Int, height: Int): List<ScreenElement> {
        val found = ArrayList<ScreenElement>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30 || found.size >= MAX_SCROLL_LISTS || !node.isVisibleToUser) return
            if (node.isScrollable) {
                val actions = node.actionList.map { it.id }
                val scrolls = buildList {
                    if (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actions) add("scroll_forward")
                    if (AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actions) add("scroll_backward")
                }
                val r = Rect().also(node::getBoundsInScreen)
                r.intersect(0, 0, width, height)
                if (scrolls.isNotEmpty() && !r.isEmpty) {
                    found += ScreenElement(
                        id = 0,
                        type = "list",
                        label = "scrollable list",
                        viewId = node.viewIdResourceName,
                        actions = scrolls,
                        centerX = r.centerX(),
                        centerY = r.centerY(),
                        left = r.left,
                        top = r.top,
                        right = r.right,
                        bottom = r.bottom,
                    )
                    return
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    try { child.recycle() } catch (_: Exception) {}
                }
            }
        }
        visit(root, 0)
        return found
    }

    // ========================================================================
    // Tree building — uses ScreenAnalyzer with legacy fallback
    // ========================================================================

    private fun buildTreeForDisplay(displayId: Int): String {
        Log.i(TAG, "buildTreeForDisplay: displayId=$displayId (smart analysis)")
        Log.i(DTAG, "BUILD_TREE: trying smart analysis for displayId=$displayId")
        // Try the smart ScreenAnalyzer first
        val smart = buildSmartTreeForDisplay(displayId)
        if (!smart.contains(""""error":""") && !smart.contains(""""elements":[]""")) {
            Log.i(DTAG, "BUILD_TREE: smart analysis succeeded (${smart.length} chars)")
            return smart
        }
        // Fallback to legacy tree building
        Log.w(DTAG, "BUILD_TREE: smart analysis empty/failed, falling back to LEGACY for displayId=$displayId")
        Log.d(TAG, "Smart analysis empty/failed, falling back to legacy tree")
        return buildLegacyTreeForDisplay(displayId)
    }

    private fun buildLegacyTreeForDisplay(displayId: Int): String {
        Log.i(TAG, "buildLegacyTreeForDisplay: displayId=$displayId")
        return try {
            val allWindows = windowsOnAllDisplays

            // The agent display's windows only; see buildSmartTreeForDisplay.
            val windows: List<AccessibilityWindowInfo>? = allWindows.get(displayId)
            if (windows.isNullOrEmpty()) {
                return """{"windows":[]}"""
            }

            val interactiveElements = mutableListOf<JSONObject>()
            var elementIndex = 0

            val windowsArray = JSONArray()
            for (window in windows) {
                val windowObj = JSONObject().apply {
                    put("id", window.id)
                    put("type", windowTypeToString(window.type))
                    put("title", window.title?.toString() ?: "")
                    put("displayId", window.displayId)
                    val bounds = Rect()
                    window.getBoundsInScreen(bounds)
                    put("bounds", bounds.flattenToString())
                }
                val root = window.getRoot()
                if (root != null) {
                    nodeToJson(root, 0)?.let { windowObj.put("tree", it) }
                    collectInteractiveElements(root, interactiveElements, elementIndex)
                    elementIndex = interactiveElements.size
                    root.recycle()
                } else {
                    Log.d(TAG, "window ${window.id} (${windowTypeToString(window.type)}) has null root")
                }
                windowsArray.put(windowObj)
            }

            val result = JSONObject().apply {
                put("windows", windowsArray)
                if (interactiveElements.isNotEmpty()) {
                    val elemArr = JSONArray()
                    interactiveElements.forEach { elemArr.put(it) }
                    put("elements", elemArr)
                }
            }
            Log.d(TAG, "buildLegacyTreeForDisplay: ${windowsArray.length()} windows, ${interactiveElements.size} interactive elements")
            result.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error building legacy tree", e)
            JSONObject().put("error", e.message ?: "failed").toString()
        }
    }

    private fun collectInteractiveElements(
        node: AccessibilityNodeInfo,
        out: MutableList<JSONObject>,
        startIndex: Int,
        depth: Int = 0,
    ) {
        if (depth > 30 || !node.isVisibleToUser) return

        val isInteractive = node.isClickable || node.isLongClickable ||
            node.isEditable || node.isScrollable || node.isCheckable
        val hasContent = !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty()

        if (isInteractive || (hasContent && node.viewIdResourceName != null)) {
            val idx = startIndex + out.size
            val elem = JSONObject().apply {
                put("idx", idx)
                put("cls", shortClassName(node.className))
                node.viewIdResourceName?.let { put("id", it) }
                node.text?.let { put("text", it.toString()) }
                node.contentDescription?.let { put("desc", it.toString()) }
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                put("bounds", bounds.flattenToString())
                val flags = mutableListOf<String>()
                if (node.isClickable) flags.add("clickable")
                if (node.isEditable) flags.add("editable")
                if (node.isScrollable) flags.add("scrollable")
                if (node.isCheckable) {
                    flags.add(if (node.isChecked) "checked" else "unchecked")
                }
                if (node.isFocused) flags.add("focused")
                if (flags.isNotEmpty()) put("flags", flags.joinToString(","))
            }
            out.add(elem)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectInteractiveElements(child, out, startIndex, depth + 1)
            child.recycle()
        }
    }

    // ---- JSON serialisation (compact, LLM-optimised) ----

    private fun isMeaningful(node: AccessibilityNodeInfo): Boolean {
        if (node.viewIdResourceName != null) return true
        if (!node.text.isNullOrEmpty()) return true
        if (!node.contentDescription.isNullOrEmpty()) return true
        if (node.isClickable || node.isLongClickable) return true
        if (node.isEditable) return true
        if (node.isScrollable) return true
        if (node.isCheckable) return true
        if (node.isFocusable) return true
        return false
    }

    private fun shortClassName(className: CharSequence?): String {
        val full = className?.toString() ?: return ""
        val dot = full.lastIndexOf('.')
        return if (dot >= 0) full.substring(dot + 1) else full
    }

    private fun nodeToJson(node: AccessibilityNodeInfo, depth: Int): JSONObject? {
        if (depth > 30) return null
        if (!node.isVisibleToUser) return null
        return try {
            if (!isMeaningful(node) && node.childCount > 0) {
                var soleVisibleChild: AccessibilityNodeInfo? = null
                var visibleCount = 0
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    if (child.isVisibleToUser) {
                        visibleCount++
                        if (visibleCount == 1) {
                            soleVisibleChild = child
                        } else {
                            child.recycle()
                        }
                    } else {
                        child.recycle()
                    }
                    if (visibleCount > 1) break
                }
                if (visibleCount == 1 && soleVisibleChild != null) {
                    val result = nodeToJson(soleVisibleChild, depth)
                    soleVisibleChild.recycle()
                    return result
                }
                soleVisibleChild?.recycle()
            }

            JSONObject().apply {
                put("cls", shortClassName(node.className))
                node.viewIdResourceName?.let { put("id", it) }
                node.text?.let { put("text", it.toString()) }
                node.contentDescription?.let { put("desc", it.toString()) }

                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                put("bounds", bounds.flattenToString())

                if (node.isClickable) put("clickable", true)
                if (node.isEnabled) put("enabled", true)
                if (node.isEditable) put("editable", true)
                if (node.isCheckable) {
                    put("checkable", true)
                    put("checked", node.isChecked)
                }
                if (node.isScrollable) put("scrollable", true)
                if (node.isFocused) put("focused", true)

                val childCount = node.childCount
                if (childCount > 0) {
                    val children = JSONArray()
                    for (i in 0 until childCount) {
                        val child = node.getChild(i) ?: continue
                        nodeToJson(child, depth + 1)?.let { children.put(it) }
                        child.recycle()
                    }
                    if (children.length() > 0) put("children", children)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun windowTypeToString(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "divider"
        else -> "unknown"
    }

    companion object {
        private const val TAG = "AgentDisplayA11y"
        private const val DTAG = "AGENTDISPLAYDEBUGKEY"
        const val INVALID_DISPLAY = -1
        /** Scrollable lists offered per window: the page, and at most one more beside it. */
        private const val MAX_SCROLL_LISTS = 2

        /** The running service. It lives in this process, so the autopilot can call it directly. */
        @Volatile
        var instance: AgentDisplayAccessibilityService? = null
            private set

        /** Only events from this display are counted. Set by the autopilot while it runs. */
        @Volatile
        var watchedDisplayId: Int = INVALID_DISPLAY
            set(value) {
                val changed = (field == INVALID_DISPLAY) != (value == INVALID_DISPLAY)
                field = value
                if (changed) instance?.applyEventConfig(value != INVALID_DISPLAY)
            }

        private const val IDLE_NOTIFICATION_TIMEOUT_MS = 100L
        private const val FOCUS_WAIT_MS = 600L
        private const val FOCUS_POLL_MS = 40L
        /** How far from the tap a focused field may be and still be the one tapped. */
        private const val NEAR_PX = 150

        /** Bumped on every event from [watchedDisplayId]. */
        val eventSeq = AtomicLong()

        @Volatile
        var lastEventUptime: Long = 0

        @Volatile
        var lastWindowChangeUptime: Long = 0

        @Volatile
        var lastWindowChangePackage: String? = null
    }
}
