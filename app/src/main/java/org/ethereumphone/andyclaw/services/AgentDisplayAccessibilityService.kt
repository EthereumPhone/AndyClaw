package org.ethereumphone.andyclaw.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.IBinder
import android.os.IAgentAccessibilityProxy
import android.os.IAgentDisplayService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.ethereumphone.andyclaw.analyzer.ScreenAnalyzer
import org.json.JSONArray
import org.json.JSONObject

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
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        serviceInfo = info
        Log.i(TAG, "flags=0x${Integer.toHexString(info.flags)}")

        registerProxyWithFramework()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed — we query on demand
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()
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

    private fun doClickNode(displayId: Int, viewId: String): String {
        Log.i(DTAG, "A11Y_CLICK_NODE: viewId=$viewId displayId=$displayId")
        val node = findNodeByViewId(displayId, viewId)
            ?: run {
                Log.e(DTAG, "A11Y_CLICK_NODE: node NOT FOUND: $viewId")
                return """{"ok":false,"error":"Node not found: $viewId"}"""
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
            """{"ok":false,"error":"Tap fallback failed: ${e.message}"}"""
        }
    }

    private fun doLongClickNode(displayId: Int, viewId: String): String {
        val node = findNodeByViewId(displayId, viewId)
            ?: return """{"ok":false,"error":"Node not found: $viewId"}"""

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
            """{"ok":false,"error":"LongPress fallback failed: ${e.message}"}"""
        }
    }

    private fun doSetNodeText(displayId: Int, viewId: String, text: String): String {
        Log.i(DTAG, "A11Y_SET_TEXT: viewId=$viewId text=\"${text.take(50)}\" displayId=$displayId")
        val node = findNodeByViewId(displayId, viewId)
            ?: run {
                Log.e(DTAG, "A11Y_SET_TEXT: node NOT FOUND: $viewId")
                return """{"ok":false,"error":"Node not found: $viewId"}"""
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

        // Fallback: tap to focus, select all, then type text
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        node.recycle()
        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()
        return try {
            val svc = frameworkService
                ?: return """{"ok":false,"error":"Framework service unavailable for text fallback"}"""
            svc.tap(cx, cy) // tap to focus
            Thread.sleep(80)
            svc.pressKeyWithMeta(29 /* KEYCODE_A */, 4096 /* META_CTRL_ON */) // Ctrl+A select all
            Thread.sleep(30)
            svc.inputText(text)
            Log.d(TAG, "setNodeText $viewId -> type fallback ($cx, $cy)")
            """{"ok":true,"method":"type","x":$cx,"y":$cy}"""
        } catch (e: Exception) {
            Log.e(TAG, "setNodeText type fallback failed for $viewId", e)
            """{"ok":false,"error":"Type fallback failed: ${e.message}"}"""
        }
    }

    private fun doScrollNode(displayId: Int, viewId: String, action: Int): String {
        val directionStr = if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) "forward" else "backward"
        Log.i(DTAG, "A11Y_SCROLL_NODE: viewId=$viewId direction=$directionStr displayId=$displayId")
        val node = findNodeByViewId(displayId, viewId)
            ?: run {
                Log.e(DTAG, "A11Y_SCROLL_NODE: node NOT FOUND: $viewId")
                return """{"ok":false,"error":"Node not found: $viewId"}"""
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
            """{"ok":false,"error":"Scroll fallback failed: ${e.message}"}"""
        }
    }

    private fun doFocusNode(displayId: Int, viewId: String): String {
        val node = findNodeByViewId(displayId, viewId)
            ?: return """{"ok":false,"error":"Node not found: $viewId"}"""

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
            """{"ok":false,"error":"Focus fallback failed: ${e.message}"}"""
        }
    }

    private fun doGetNodeInfo(displayId: Int, viewId: String): String {
        val node = findNodeByViewId(displayId, viewId)
            ?: return """{"error":"Node not found: $viewId"}"""
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
            """{"error":"${e.message}"}"""
        } finally {
            node.recycle()
        }
    }

    // ========================================================================
    // Node lookup — strict display targeting, safe recycling
    // ========================================================================

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

            var windows: List<AccessibilityWindowInfo>? = allWindows.get(displayId)
            if (windows.isNullOrEmpty()) {
                windows = getWindows()
                if (windows.isNullOrEmpty()) {
                    Log.w(DTAG, "SMART_ANALYSIS: no windows found for displayId=$displayId")
                    return """{"screen":{},"elements":[],"scrollable":false}"""
                }
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
            """{"error":"${e.message}"}"""
        }
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

            var windows: List<AccessibilityWindowInfo>? = allWindows.get(displayId)
            if (windows.isNullOrEmpty()) {
                windows = getWindows()
                if (windows.isNullOrEmpty()) {
                    return """{"windows":[]}"""
                }
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
            """{"error":"${e.message}"}"""
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
    }
}
