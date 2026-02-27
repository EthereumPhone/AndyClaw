package org.ethereumphone.andyclaw.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class AndyClawAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "A11yService"
        private const val MAX_TREE_DEPTH = 40
        private const val MAX_NODES = 800
        private const val PREFETCH_DEBOUNCE_MS = 150L

        @Volatile
        var instance: AndyClawAccessibilityService? = null
            private set

        /**
         * Known container/structural class names that should always be shown
         * even if they have no text or interactive flags.
         */
        val STRUCTURAL_CLASSES = setOf(
            "RecyclerView", "ListView", "GridView", "GridLayout",
            "ScrollView", "HorizontalScrollView", "NestedScrollView",
            "ViewPager", "ViewPager2", "TabLayout", "TabWidget",
            "FrameLayout", "LinearLayout", "RelativeLayout", "ConstraintLayout",
            "CoordinatorLayout", "DrawerLayout", "NavigationView",
            "Toolbar", "ActionBar", "AppBarLayout",
            "CardView", "ChipGroup",
            "SurfaceView", "GLSurfaceView", "TextureView",
            "WebView",
        )

        /**
         * Opaque rendering surfaces where visual content is NOT in the
         * accessibility tree.
         */
        val OPAQUE_RENDERERS = setOf(
            "SurfaceView", "GLSurfaceView", "TextureView",
        )
    }

    // ── Cache ─────────────────────────────────────────────────────────

    data class HierarchySnapshot(
        val text: String,
        val refMap: Map<String, AccessibilityNodeInfo>,
        val nodeCount: Int,
        val timestampMs: Long,
        val packageName: String,
        val windowId: Int,
    )

    @Volatile
    private var cachedSnapshot: HierarchySnapshot? = null

    /** Monotonic counter bumped on every screen-changing event. */
    @Volatile
    private var eventGeneration: Long = 0

    /** The generation at which the cache was last built. */
    @Volatile
    private var cacheGeneration: Long = -1

    private val handler = Handler(Looper.getMainLooper())
    private val prefetchRunnable = Runnable { prefetchHierarchy() }

    val isCacheFresh: Boolean
        get() = cacheGeneration == eventGeneration && cachedSnapshot != null

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                eventGeneration++
                schedulePrefetch()
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(prefetchRunnable)
        instance = null
        Log.i(TAG, "Accessibility service destroyed")
    }

    // ── Pre-fetch ─────────────────────────────────────────────────────

    private fun schedulePrefetch() {
        handler.removeCallbacks(prefetchRunnable)
        handler.postDelayed(prefetchRunnable, PREFETCH_DEBOUNCE_MS)
    }

    private fun prefetchHierarchy() {
        try {
            val gen = eventGeneration
            val snapshot = buildSnapshot(MAX_TREE_DEPTH, MAX_NODES)
            if (snapshot != null) {
                cachedSnapshot = snapshot
                cacheGeneration = gen
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pre-fetch failed: ${e.message}")
        }
    }

    // ── Public API for UiAutomationSkill ──────────────────────────────

    /**
     * Returns the cached hierarchy if fresh, otherwise does a live walk.
     * This is the fast path — typically <1ms when returning from cache.
     */
    fun getHierarchy(forceRefresh: Boolean = false): HierarchySnapshot? {
        if (!forceRefresh && isCacheFresh) {
            return cachedSnapshot
        }
        val gen = eventGeneration
        val snapshot = buildSnapshot(MAX_TREE_DEPTH, MAX_NODES) ?: return null
        cachedSnapshot = snapshot
        cacheGeneration = gen
        return snapshot
    }

    /**
     * Get hierarchy and update the ref map for subsequent actions.
     * Returns the text representation. Uses cache when available.
     */
    fun dumpAndRetain(forceRefresh: Boolean = false): String {
        val snapshot = getHierarchy(forceRefresh) ?: return "No active window"
        return snapshot.text
    }

    /**
     * Walk the subtree of a specific node reference at increased depth,
     * including nodes the top-level dump skips (non-interactive, no text).
     * Useful for exploring complex containers deeper.
     */
    fun expandSubtree(ref: String, maxDepth: Int = 15): String? {
        val node = findNodeByRef(ref) ?: return null
        val sb = StringBuilder()
        var counter = 0

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > maxDepth || counter > 300) return
            counter++
            val indent = "  ".repeat(depth)
            val className = n.className?.toString()?.substringAfterLast('.') ?: "View"
            val text = n.text?.toString()
            val desc = n.contentDescription?.toString()
            val resId = n.viewIdResourceName

            sb.append("$indent$className")
            if (!text.isNullOrBlank()) sb.append(" text=\"$text\"")
            if (!desc.isNullOrBlank()) sb.append(" desc=\"$desc\"")
            if (resId != null) sb.append(" id=\"$resId\"")

            val attrs = mutableListOf<String>()
            if (n.isClickable) attrs.add("clickable")
            if (n.isScrollable) attrs.add("scrollable")
            if (n.isEditable) attrs.add("editable")
            if (n.isCheckable) attrs.add(if (n.isChecked) "checked" else "unchecked")
            if (!n.isEnabled) attrs.add("disabled")
            if (n.isFocused) attrs.add("focused")
            if (n.isVisibleToUser) attrs.add("visible") else attrs.add("hidden")
            if (attrs.isNotEmpty()) sb.append(" (${attrs.joinToString(", ")})")
            sb.appendLine()

            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }

        walk(node, 0)
        return "Subtree of [$ref] — $counter nodes:\n$sb"
    }

    /**
     * Dump all visible windows (app, dialogs, system overlays, IME, etc.).
     * Returns a multi-section text with one section per window.
     */
    fun dumpAllWindows(): String {
        val windowList = try {
            windows
        } catch (e: Exception) {
            return "Cannot access windows: ${e.message}"
        }

        if (windowList.isNullOrEmpty()) return "No windows available"

        val sb = StringBuilder()
        sb.appendLine("Visible windows: ${windowList.size}")
        sb.appendLine("===")

        for (window in windowList) {
            val typeStr = when (window.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "KEYBOARD"
                AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "OVERLAY"
                AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_DIVIDER"
                else -> "OTHER(${window.type})"
            }
            val title = window.title?.toString() ?: "(no title)"
            sb.appendLine("--- Window: $title [$typeStr] layer=${window.layer} active=${window.isActive} focused=${window.isFocused}")

            val root = window.root
            if (root != null) {
                var nodeCount = 0
                fun walkWindow(node: AccessibilityNodeInfo, depth: Int) {
                    if (depth > 20 || nodeCount > 200) return
                    if (shouldInclude(node, depth)) {
                        nodeCount++
                        val indent = "  ".repeat(depth)
                        val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
                        val text = node.text?.toString()
                        val desc = node.contentDescription?.toString()
                        val resId = node.viewIdResourceName

                        sb.append("$indent$className")
                        if (!text.isNullOrBlank()) sb.append(" text=\"$text\"")
                        if (!desc.isNullOrBlank()) sb.append(" desc=\"$desc\"")
                        if (resId != null) sb.append(" id=\"$resId\"")
                        val attrs = mutableListOf<String>()
                        if (node.isClickable) attrs.add("clickable")
                        if (node.isScrollable) attrs.add("scrollable")
                        if (node.isEditable) attrs.add("editable")
                        if (node.childCount > 0) attrs.add("${node.childCount} children")
                        if (className in OPAQUE_RENDERERS) attrs.add("opaque_render_surface")
                        if (attrs.isNotEmpty()) sb.append(" (${attrs.joinToString(", ")})")
                        sb.appendLine()
                    }

                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i) ?: continue
                        walkWindow(child, depth + 1)
                    }
                }
                walkWindow(root, 1)
                if (nodeCount == 0) sb.appendLine("  (empty)")
            } else {
                sb.appendLine("  (no root node)")
            }
        }

        return sb.toString()
    }

    fun findNodeByRef(ref: String): AccessibilityNodeInfo? = cachedSnapshot?.refMap?.get(ref)

    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (result) invalidateCache()
        return result
    }

    fun longPressNode(node: AccessibilityNodeInfo): Boolean {
        val result = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        if (result) invalidateCache()
        return result
    }

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (result) invalidateCache()
        return result
    }

    fun scrollNode(node: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val result = node.performAction(action)
        if (result) invalidateCache()
        return result
    }

    fun findNodesByText(text: String): List<Pair<String, AccessibilityNodeInfo>> {
        val refMap = cachedSnapshot?.refMap ?: return emptyList()
        val results = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        for ((ref, node) in refMap) {
            val nodeText = node.text?.toString() ?: ""
            val nodeDesc = node.contentDescription?.toString() ?: ""
            if (nodeText.contains(text, ignoreCase = true) ||
                nodeDesc.contains(text, ignoreCase = true)) {
                results.add(ref to node)
            }
        }
        return results
    }

    fun findNodesByResourceId(resourceId: String): List<Pair<String, AccessibilityNodeInfo>> {
        val refMap = cachedSnapshot?.refMap ?: return emptyList()
        val results = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        for ((ref, node) in refMap) {
            if (node.viewIdResourceName?.contains(resourceId, ignoreCase = true) == true) {
                results.add(ref to node)
            }
        }
        return results
    }

    suspend fun performSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 300,
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val result = CompletableDeferred<Boolean>()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result.complete(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                result.complete(false)
            }
        }, null)

        val success = withTimeoutOrNull(durationMs + 2000) { result.await() } ?: false
        if (success) invalidateCache()
        return success
    }

    suspend fun tapAtCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val result = CompletableDeferred<Boolean>()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result.complete(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                result.complete(false)
            }
        }, null)

        val success = withTimeoutOrNull(3000) { result.await() } ?: false
        if (success) invalidateCache()
        return success
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK).also { if (it) invalidateCache() }
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME).also { if (it) invalidateCache() }
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS).also { if (it) invalidateCache() }
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS).also { if (it) invalidateCache() }
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS).also { if (it) invalidateCache() }

    // ── Internal ──────────────────────────────────────────────────────

    private fun invalidateCache() {
        eventGeneration++
        schedulePrefetch()
    }

    private fun shouldInclude(node: AccessibilityNodeInfo, depth: Int): Boolean {
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val resId = node.viewIdResourceName
        val className = node.className?.toString()?.substringAfterLast('.') ?: ""
        val childCount = node.childCount

        // Always include interactive nodes
        if (node.isClickable || node.isScrollable ||
            node.isEditable || node.isCheckable) return true

        // Always include nodes with text or content description
        if (!text.isNullOrBlank() || !desc.isNullOrBlank()) return true

        // Include nodes that have a resource ID (developer-named)
        if (resId != null) return true

        // Include structural containers that have children
        if (childCount > 0 && className in STRUCTURAL_CLASSES) return true

        // Include opaque renderers (even with 0 children — that's the point)
        if (className in OPAQUE_RENDERERS) return true

        // At shallow depths (≤3), include anything with children to show structure
        if (depth <= 3 && childCount > 0) return true

        // Include leaf nodes that are visible and have bounds (e.g. ImageView
        // thumbnails that lack contentDescription)
        if (childCount == 0 && node.isVisibleToUser) {
            val parent = node.parent
            if (parent != null && parent.isClickable) return true
        }

        return false
    }

    private fun buildSnapshot(maxDepth: Int, maxNodes: Int): HierarchySnapshot? {
        val root = rootInActiveWindow ?: return null
        val sb = StringBuilder()
        val refMap = mutableMapOf<String, AccessibilityNodeInfo>()
        var counter = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > maxDepth || counter > maxNodes) return

            val include = shouldInclude(node, depth)
            val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val resId = node.viewIdResourceName
            val childCount = node.childCount

            if (include) {
                counter++
                val ref = "N$counter"
                refMap[ref] = node

                val indent = "  ".repeat(depth)
                sb.append("$indent[$ref] $className")

                if (!text.isNullOrBlank()) sb.append(" text=\"$text\"")
                if (!desc.isNullOrBlank()) sb.append(" desc=\"$desc\"")
                if (resId != null) sb.append(" id=\"$resId\"")

                val attrs = mutableListOf<String>()
                if (node.isClickable) attrs.add("clickable")
                if (node.isScrollable) attrs.add("scrollable")
                if (node.isEditable) attrs.add("editable")
                if (node.isCheckable) {
                    attrs.add(if (node.isChecked) "checked" else "unchecked")
                }
                if (!node.isEnabled) attrs.add("disabled")
                if (node.isFocused) attrs.add("focused")
                if (className in OPAQUE_RENDERERS) {
                    attrs.add("opaque_render_surface")
                }
                if (childCount > 0) attrs.add("$childCount children")
                if (attrs.isNotEmpty()) sb.append(" (${attrs.joinToString(", ")})")

                sb.appendLine()
            }

            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }

        walk(root, 0)

        val packageName = root.packageName?.toString() ?: "unknown"
        val header = "Package: $packageName\nNodes: $counter\n---\n"
        return HierarchySnapshot(
            text = header + sb.toString(),
            refMap = refMap,
            nodeCount = counter,
            timestampMs = System.currentTimeMillis(),
            packageName = packageName,
            windowId = root.windowId,
        )
    }
}
