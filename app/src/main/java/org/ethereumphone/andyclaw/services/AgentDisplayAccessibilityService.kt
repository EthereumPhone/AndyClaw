package org.ethereumphone.andyclaw.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.IBinder
import android.os.IAgentAccessibilityProxy
import android.os.IAgentDisplayService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-hosted accessibility service that provides UI tree queries for the
 * framework's AgentDisplayService via a binder callback proxy.
 *
 * The framework cannot host this service itself because AccessibilityManagerService
 * cannot resolve/bind services declared in the "android" framework package.
 */
class AgentDisplayAccessibilityService : AccessibilityService() {

    private val proxy = object : IAgentAccessibilityProxy.Stub() {
        override fun getTreeForDisplay(displayId: Int): String =
            buildTreeForDisplay(displayId)

        override fun clickNodeByViewId(displayId: Int, viewId: String): Boolean =
            doClickNode(displayId, viewId)

        override fun setNodeTextByViewId(displayId: Int, viewId: String, text: String): Boolean =
            doSetNodeText(displayId, viewId, text)
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
            service.registerAccessibilityProxy(proxy)
            Log.i(TAG, "Proxy registered with AgentDisplayService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register proxy with AgentDisplayService", e)
        }
    }

    // ---- tree building (ported from framework AgentDisplayAccessibilityService.java) ----

    private fun buildTreeForDisplay(displayId: Int): String {
        Log.i(TAG, "buildTreeForDisplay: displayId=$displayId")
        return try {
            val allWindows = windowsOnAllDisplays

            // Diagnostics: log known displays
            val displayInfo = buildString {
                for (i in 0 until allWindows.size()) {
                    if (i > 0) append(", ")
                    val id = allWindows.keyAt(i)
                    val wins = allWindows.valueAt(i)
                    append("$id(${wins?.size ?: 0} windows)")
                }
            }
            Log.d(TAG, "buildTreeForDisplay: target=$displayId, displays=[$displayInfo]")

            var windows: List<AccessibilityWindowInfo>? = allWindows.get(displayId)
            if (windows.isNullOrEmpty()) {
                Log.w(TAG, "No windows on display $displayId, fallback to getWindows()")
                windows = getWindows()
                if (windows.isNullOrEmpty()) {
                    Log.w(TAG, "getWindows() also empty")
                    return "{\"windows\":[]}"
                }
                Log.d(TAG, "getWindows() returned ${windows.size} windows (default display fallback)")
            }

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
                    root.recycle()
                } else {
                    Log.d(TAG, "window ${window.id} (${windowTypeToString(window.type)}) has null root")
                }
                windowsArray.put(windowObj)
            }

            val result = JSONObject().apply { put("windows", windowsArray) }
            Log.d(TAG, "buildTreeForDisplay: returning ${windowsArray.length()} windows")
            result.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error building tree", e)
            "{\"error\":\"${e.message}\"}"
        }
    }

    private fun doClickNode(displayId: Int, viewId: String): Boolean {
        val node = findNodeByViewId(displayId, viewId) ?: return false
        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.recycle()
        Log.d(TAG, "clickNode $viewId -> $result")
        return result
    }

    private fun doSetNodeText(displayId: Int, viewId: String, text: String): Boolean {
        val node = findNodeByViewId(displayId, viewId) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        node.recycle()
        Log.d(TAG, "setNodeText $viewId -> $result")
        return result
    }

    private fun findNodeByViewId(displayId: Int, viewId: String): AccessibilityNodeInfo? {
        val allWindows = windowsOnAllDisplays
        var windows: List<AccessibilityWindowInfo>? = allWindows.get(displayId)
        if (windows.isNullOrEmpty()) windows = getWindows()
        if (windows == null) return null

        for (window in windows) {
            val root = window.getRoot() ?: continue
            val found = root.findAccessibilityNodeInfosByViewId(viewId)
            root.recycle()
            if (!found.isNullOrEmpty()) {
                // Recycle extras, return the first match
                for (i in 1 until found.size) found[i].recycle()
                return found[0]
            }
        }
        return null
    }

    // ---- JSON serialisation ----

    private fun nodeToJson(node: AccessibilityNodeInfo, depth: Int): JSONObject? {
        if (depth > 30) return null
        return try {
            JSONObject().apply {
                put("class", node.className?.toString() ?: "")
                node.viewIdResourceName?.let { put("id", it) }
                node.text?.let { put("text", it.toString()) }
                node.contentDescription?.let { put("desc", it.toString()) }

                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                put("bounds", bounds.flattenToString())

                put("clickable", node.isClickable)
                put("enabled", node.isEnabled)
                put("visible", node.isVisibleToUser)
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
    }
}
