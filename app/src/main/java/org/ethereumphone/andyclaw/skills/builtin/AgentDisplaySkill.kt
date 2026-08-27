package org.ethereumphone.andyclaw.skills.builtin

import android.os.IAgentDisplayService
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.skills.ToolRoutes

class AgentDisplaySkill : AndyClawSkill {

    companion object {
        private const val TAG = "AgentDisplaySkill"
        private const val LTAG = "AGENT_VIRTUAL_SCREEN" // verbose logging tag
        private const val DTAG = "AGENTDISPLAYDEBUGKEY" // debug filter tag for tracing agent decisions
        private const val DISPLAY_WIDTH = 720
        private const val DISPLAY_HEIGHT = 720
        private const val DISPLAY_DPI = 240
        /** Max compressed image size in bytes (before base64 encoding). */
        private const val MAX_IMAGE_BYTES = 200_000 // ~266 KB as base64
        private const val MIN_QUALITY = 40
        // Wait times (ms) after actions before auto-capturing UI tree.
        // These are aggressive — a11y tree queries are fast and the tree
        // reflects committed state, so we only need minimal settling time.
        private const val DELAY_TAP = 80L
        private const val DELAY_SWIPE = 150L
        private const val DELAY_TYPE = 50L
        private const val DELAY_KEY = 80L
        private const val DELAY_LAUNCH = 1200L
        private const val DELAY_NODE_CLICK = 50L
        private const val DELAY_NODE_TEXT = 30L
        private const val DELAY_DRAG = 100L
        private const val DELAY_PINCH = 100L
    }

    override val id = "agent_display"
    override val name = "Agent Display"

    // No tools on the OPEN tier — this is privileged-only.
    override val baseManifest = SkillManifest(
        description = "Operate a virtual Android display (${DISPLAY_WIDTH}x${DISPLAY_HEIGHT}). Privileged-only.",
        tools = emptyList(),
    )

    override val privilegedManifest = SkillManifest(
        description = buildString {
            append("Operate a virtual Android display to perform tasks in apps on behalf of the user. ")
            append("CRITICAL — every action (create, tap, click_node, press_back, etc.) automatically returns the full UI state as structured text listing every visible element with its id, type, label, actions, viewId, and center coordinates. ")
            append("You NEVER need to call agent_display_screenshot to see the screen — the UI state IS the screen. Read it. ")
            append("To interact: use click_node(viewId) when a viewId is shown, or tap(center_x, center_y) when there is no viewId. ")
            append("NEVER call agent_display_screenshot unless the UI state says 'No elements found' (rare — only custom-drawn apps like games). ")
            append("NEVER call agent_display_look right after agent_display_create — create already returns the UI state.")
        },
        tools = listOf(
            // ── Display Lifecycle ───────────────────────────────────────
            tool(
                name = "agent_display_create",
                description = "Create the virtual display (${DISPLAY_WIDTH}x${DISPLAY_HEIGHT} @ ${DISPLAY_DPI}dpi) and launch an app. Must be called first. Returns the FULL UI state with all elements — read the output to see every button, text field, menu item on screen. Then interact using click_node(viewId) or tap(center_x, center_y). Do NOT call look or screenshot after this — you already have the screen.",
                props = mapOf(
                    "package_name" to propString("The Android package name, e.g. com.android.settings"),
                ),
                required = listOf("package_name"),
            ),
            tool(
                name = "agent_display_destroy",
                description = "Destroy the virtual display and release resources. The app that was running on the virtual display is closed. Use this when you are done with a task and the user does NOT need the app to remain open.",
                props = emptyMap(),
            ),
            tool(
                name = "agent_display_destroy_and_promote",
                description = "Destroy the virtual display but move the currently running app to the user's main screen so they can continue using it. Use this when the user will want to keep interacting with the app after you are done — for example after starting navigation, playing music, opening a webpage, or setting up a video call.",
                props = emptyMap(),
            ),
            tool(
                name = "agent_display_resize",
                description = "Hot-resize the display without destroying it. Returns the UI tree after resize.",
                props = mapOf(
                    "width" to propNumber("New width in pixels"),
                    "height" to propNumber("New height in pixels"),
                    "dpi" to propNumber("New DPI"),
                ),
                required = listOf("width", "height", "dpi"),
            ),

            // ── App Management ──────────────────────────────────────────
            tool(
                name = "agent_display_launch_activity",
                description = "Launch a specific activity by component name. Example: package_name='com.android.settings', activity_name='com.android.settings.Settings'. Returns the UI tree.",
                props = mapOf(
                    "package_name" to propString("The Android package name"),
                    "activity_name" to propString("Fully qualified activity class name"),
                ),
                required = listOf("package_name", "activity_name"),
            ),
            tool(
                name = "agent_display_launch_intent",
                description = "Launch an arbitrary intent from a URI string. Example: 'intent:#Intent;action=android.intent.action.VIEW;data=https://example.com;end'. Returns the UI tree.",
                props = mapOf(
                    "uri" to propString("Intent URI string"),
                ),
                required = listOf("uri"),
            ),
            tool(
                name = "agent_display_current_activity",
                description = "Get the currently running activity on the virtual display as 'package/activity'. Returns null if nothing is running.",
                props = emptyMap(),
            ),

            // ── Screenshot (LAST RESORT — almost never needed) ─────
            tool(
                name = "agent_display_screenshot",
                description = "LAST RESORT — take a visual screenshot. ONLY use when the UI state says 'No elements found' (games, custom-drawn canvas apps). Every other tool already returns the full UI state as text — read that instead. Screenshots are slow and expensive. Do NOT use for normal apps like Settings, browsers, messaging apps, etc.",
                props = emptyMap(),
            ),
            // capture_region removed — agent_display_screenshot covers the use case.

            // ── Touch Gestures (fallback — prefer a11y node actions when viewId available) ──
            tool(
                name = "agent_display_tap",
                description = "Tap at (x, y) coordinates. Use the center_x, center_y from the UI state output. Only use this when the element has no viewId — prefer click_node when viewId is available. Returns updated UI state.",
                props = mapOf(
                    "x" to propNumber("X coordinate"),
                    "y" to propNumber("Y coordinate"),
                ),
                required = listOf("x", "y"),
            ),
            tool(
                name = "agent_display_long_press",
                description = "Long press at (x, y) for the given duration. Use ~500ms for standard long press (context menus). Returns the UI tree.",
                props = mapOf(
                    "x" to propNumber("X coordinate"),
                    "y" to propNumber("Y coordinate"),
                    "duration_ms" to propNumber("Hold duration in milliseconds (default 500)"),
                ),
                required = listOf("x", "y"),
            ),
            tool(
                name = "agent_display_double_tap",
                description = "Double tap at (x, y). Commonly used for zoom or text selection. Returns the UI tree.",
                props = mapOf(
                    "x" to propNumber("X coordinate"),
                    "y" to propNumber("Y coordinate"),
                    "interval_ms" to propNumber("Interval between taps in ms (default 100, keep under 300)"),
                ),
                required = listOf("x", "y"),
            ),
            tool(
                name = "agent_display_swipe",
                description = "Swipe from (x1,y1) to (x2,y2). Prefer agent_display_scroll_node for scrolling lists. Use swipe only for custom scroll targets without viewId. Returns the UI tree.",
                props = mapOf(
                    "x1" to propNumber("Start X coordinate"),
                    "y1" to propNumber("Start Y coordinate"),
                    "x2" to propNumber("End X coordinate"),
                    "y2" to propNumber("End Y coordinate"),
                    "duration_ms" to propNumber("Swipe duration in milliseconds (default 300)"),
                ),
                required = listOf("x1", "y1", "x2", "y2"),
            ),
            tool(
                name = "agent_display_fling",
                description = "Fast ~50ms swipe with momentum/inertia. Triggers fling/scroll momentum in list views. Use for quick scrolling through long lists. Returns the UI tree.",
                props = mapOf(
                    "x1" to propNumber("Start X coordinate"),
                    "y1" to propNumber("Start Y coordinate"),
                    "x2" to propNumber("End X coordinate"),
                    "y2" to propNumber("End Y coordinate"),
                ),
                required = listOf("x1", "y1", "x2", "y2"),
            ),
            tool(
                name = "agent_display_drag",
                description = "Drag from start to end position. Holds at start for holdBeforeDragMs (to trigger drag mode), then moves to end. Use for drag-and-drop operations. Returns the UI tree.",
                props = mapOf(
                    "start_x" to propNumber("Start X coordinate"),
                    "start_y" to propNumber("Start Y coordinate"),
                    "end_x" to propNumber("End X coordinate"),
                    "end_y" to propNumber("End Y coordinate"),
                    "hold_before_drag_ms" to propNumber("How long to hold before starting drag (default 500)"),
                    "drag_duration_ms" to propNumber("Duration of the drag movement (default 500)"),
                ),
                required = listOf("start_x", "start_y", "end_x", "end_y"),
            ),
            tool(
                name = "agent_display_pinch",
                description = "Two-finger pinch gesture centered at (center_x, center_y). start_span > end_span = pinch in (zoom out), start_span < end_span = pinch out (zoom in). Returns the UI tree.",
                props = mapOf(
                    "center_x" to propNumber("Center X coordinate"),
                    "center_y" to propNumber("Center Y coordinate"),
                    "start_span" to propNumber("Initial distance between fingers in pixels"),
                    "end_span" to propNumber("Final distance between fingers in pixels"),
                    "duration_ms" to propNumber("Gesture duration in milliseconds (default 500)"),
                ),
                required = listOf("center_x", "center_y", "start_span", "end_span"),
            ),
            tool(
                name = "agent_display_gesture",
                description = "Arbitrary touch path with timed waypoints. All three arrays must have the same length (min 2). Timestamps are relative (first is base). Example L-shape: x=[100,100,300], y=[100,500,500], timestamps=[0,300,500]. Returns the UI tree.",
                props = mapOf(
                    "x_points" to propNumberArray("Array of X coordinates for each waypoint"),
                    "y_points" to propNumberArray("Array of Y coordinates for each waypoint"),
                    "timestamps_ms" to propNumberArray("Array of relative timestamps in ms for each waypoint"),
                ),
                required = listOf("x_points", "y_points", "timestamps_ms"),
            ),

            // ── Key Input ───────────────────────────────────────────────
            tool(
                name = "agent_display_press_back",
                description = "Press the Back button on the virtual display. Returns the UI tree.",
                props = emptyMap(),
            ),
            tool(
                name = "agent_display_press_home",
                description = "Press the Home button on the virtual display. Returns the UI tree.",
                props = emptyMap(),
            ),
            tool(
                name = "agent_display_press_enter",
                description = "Press the Enter key. Useful for submitting text fields and confirming dialogs. Returns the UI tree.",
                props = emptyMap(),
            ),
            tool(
                name = "agent_display_press_recents",
                description = "Press the Recents/Overview button to show recent apps. Returns the UI tree.",
                props = emptyMap(),
            ),
            tool(
                name = "agent_display_press_key",
                description = "Press any key by keycode, with optional modifier keys and hold duration. Common keycodes: 67=Backspace, 112=Delete, 61=Tab, 111=Escape, 84=Search. Meta flags: 0x1=Shift, 0x2=Alt, 0x1000=Ctrl, 0x10000=Meta. For Ctrl+A: key_code=29, meta_state=4096. For Ctrl+V: key_code=50, meta_state=4096. Returns the UI tree.",
                props = mapOf(
                    "key_code" to propNumber("Android keycode integer"),
                    "meta_state" to propNumber("Modifier key bitmask (optional, default 0). 1=Shift, 2=Alt, 4096=Ctrl, 65536=Meta"),
                    "hold_duration_ms" to propNumber("Hold duration in ms (optional, default 0 for normal press)"),
                ),
                required = listOf("key_code"),
            ),

            // ── Text Input (prefer set_node_text when viewId available) ──
            tool(
                name = "agent_display_type_text",
                description = "Type text into the focused field via key injection. Prefer agent_display_set_node_text when the field has a viewId (faster, no focus needed). Returns the UI tree.",
                props = mapOf(
                    "text" to propString("The text to type"),
                    "delay_ms" to propNumber("Optional per-character delay in ms. Use ~50ms for search fields that show live suggestions per keystroke. Default 0 (instant)."),
                ),
                required = listOf("text"),
            ),

            // ── Clipboard ───────────────────────────────────────────────
            tool(
                name = "agent_display_set_clipboard",
                description = "Set the system clipboard to the given text. Use with agent_display_press_key (Ctrl+V: key_code=50, meta_state=4096) to paste into fields. Useful for complex text that can't be typed directly.",
                props = mapOf(
                    "text" to propString("Text to put on the clipboard"),
                ),
                required = listOf("text"),
            ),
            tool(
                name = "agent_display_get_clipboard",
                description = "Get the current clipboard text. Returns the text or null if empty.",
                props = emptyMap(),
            ),

            // ── See the screen + Accessibility (PRIMARY interaction method) ──
            tool(
                name = "agent_display_look",
                description = "Re-read the current screen state. Returns every visible UI element with id, type, label, actions, viewId, and center coordinates. You usually do NOT need this — every other action (create, tap, click_node, press_back, etc.) already returns the UI state automatically. Only call this if you need to refresh without performing an action.",
                props = emptyMap(),
            ),
            tool(
                name = "agent_display_click_node",
                description = "Click by viewId (e.g. 'com.android.settings:id/search_bar'). Use the viewId shown in agent_display_look output. If no viewId is available, use agent_display_tap with the element's bounds center instead. Returns updated UI state.",
                props = mapOf(
                    "view_id" to propString("The full accessibility view ID (e.g. 'com.android.settings:id/search_bar')"),
                ),
                required = listOf("view_id"),
            ),
            tool(
                name = "agent_display_long_click_node",
                description = "Long-click by viewId. Triggers context menus. Returns updated UI tree.",
                props = mapOf(
                    "view_id" to propString("The accessibility view ID of the node to long-click"),
                ),
                required = listOf("view_id"),
            ),
            tool(
                name = "agent_display_set_node_text",
                description = "Set text directly on an editable field by viewId. Instant, no focus needed. Returns updated UI state.",
                props = mapOf(
                    "view_id" to propString("The full accessibility view ID of the text field"),
                    "text" to propString("The text to set"),
                ),
                required = listOf("view_id", "text"),
            ),
            tool(
                name = "agent_display_scroll_node",
                description = "Scroll a list/container by viewId. More reliable than coordinate-based swiping. Returns updated UI state.",
                props = mapOf(
                    "view_id" to propString("The full accessibility view ID of the scrollable node"),
                    "direction" to propEnum("Scroll direction", listOf("forward", "backward")),
                ),
                required = listOf("view_id", "direction"),
            ),
            tool(
                name = "agent_display_focus_node",
                description = "Set focus on a node by viewId. Use before type_text if a field needs focus. Returns updated UI tree.",
                props = mapOf(
                    "view_id" to propString("The accessibility view ID of the node to focus"),
                ),
                required = listOf("view_id"),
            ),
            tool(
                name = "agent_display_get_node_info",
                description = "Get detailed info about a specific node — bounds, text, contentDescription, all state flags. Use to inspect a single element without fetching the full tree.",
                props = mapOf(
                    "view_id" to propString("The accessibility view ID of the node to inspect"),
                ),
                required = listOf("view_id"),
            ),
        ),
    )

    @Volatile private var displayActive = false

    /**
     * The lookup and the reconnect-on-binder-death live in [AgentDisplayBinder] so the
     * flow interpreter's driver shares exactly one connection path with this skill.
     */
    private fun getService(): IAgentDisplayService = AgentDisplayBinder.service()

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        Log.i(LTAG, "execute START tool=$tool params=$params tier=$tier")
        Log.i(DTAG, "TOOL_EXECUTE: $tool | params=$params")
        val startMs = System.currentTimeMillis()
        return try {
            val result = when (tool) {
                // Display lifecycle
                "agent_display_create" -> doCreate(params)
                "agent_display_destroy" -> doDestroy()
                "agent_display_destroy_and_promote" -> doDestroyAndPromote()
                "agent_display_get_info" -> doGetInfo()
                "agent_display_resize" -> doResize(params)
                "agent_display_launch_activity" -> doLaunchActivity(params)
                "agent_display_launch_intent" -> doLaunchIntent(params)
                "agent_display_current_activity" -> doCurrentActivity()
                // Screenshots
                "agent_display_screenshot" -> captureScreenshot()
                // Touch gestures
                "agent_display_tap" -> doTap(params)
                "agent_display_long_press" -> doLongPress(params)
                "agent_display_double_tap" -> doDoubleTap(params)
                "agent_display_swipe" -> doSwipe(params)
                "agent_display_fling" -> doFling(params)
                "agent_display_drag" -> doDrag(params)
                "agent_display_pinch" -> doPinch(params)
                "agent_display_gesture" -> doGesture(params)
                // Key input
                "agent_display_press_back" -> doPressBack()
                "agent_display_press_home" -> doPressHome()
                "agent_display_press_enter" -> doPressEnter()
                "agent_display_press_recents" -> doPressRecents()
                "agent_display_press_key" -> doPressKey(params)
                // Text input
                "agent_display_type_text" -> doTypeText(params)
                "agent_display_type_text_slow" -> doTypeText(params) // legacy alias
                // Clipboard
                "agent_display_set_clipboard" -> doSetClipboard(params)
                "agent_display_get_clipboard" -> doGetClipboard()
                // Accessibility / look at screen
                "agent_display_look" -> doGetUiTree()
                "agent_display_get_ui_tree" -> doGetUiTree()
                "agent_display_click_node" -> doClickNode(params)
                "agent_display_long_click_node" -> doLongClickNode(params)
                "agent_display_set_node_text" -> doSetNodeText(params)
                "agent_display_scroll_node" -> doScrollNode(params)
                "agent_display_focus_node" -> doFocusNode(params)
                "agent_display_get_node_info" -> doGetNodeInfo(params)
                else -> SkillResult.Error("Unknown tool: $tool")
            }
            val elapsed = System.currentTimeMillis() - startMs
            when (result) {
                is SkillResult.ImageSuccess -> {
                    Log.i(LTAG, "execute DONE tool=$tool elapsed=${elapsed}ms resultType=ImageSuccess base64Len=${result.base64.length} mediaType=${result.mediaType} textLen=${result.text.length}")
                    Log.w(DTAG, "TOOL_RESULT: $tool -> IMAGE (${elapsed}ms) base64Len=${result.base64.length} — WARNING: screenshot was taken!")
                }
                is SkillResult.Success -> {
                    Log.i(LTAG, "execute DONE tool=$tool elapsed=${elapsed}ms resultType=Success dataLen=${result.data.length}")
                    Log.i(DTAG, "TOOL_RESULT: $tool -> SUCCESS (${elapsed}ms) dataLen=${result.data.length}")
                    // Log first 2000 chars of result data for debugging
                    val preview = result.data.take(2000)
                    Log.i(DTAG, "TOOL_RESULT_DATA: $tool -> $preview")
                    if (result.data.length > 2000) {
                        Log.i(DTAG, "TOOL_RESULT_DATA: $tool -> ... (${result.data.length - 2000} more chars)")
                    }
                }
                is SkillResult.Error -> {
                    Log.w(LTAG, "execute DONE tool=$tool elapsed=${elapsed}ms resultType=Error msg=${result.message}")
                    Log.e(DTAG, "TOOL_RESULT: $tool -> ERROR (${elapsed}ms) msg=${result.message}")
                }
                is SkillResult.RequiresApproval -> {
                    Log.i(LTAG, "execute DONE tool=$tool elapsed=${elapsed}ms resultType=RequiresApproval")
                    Log.i(DTAG, "TOOL_RESULT: $tool -> REQUIRES_APPROVAL (${elapsed}ms)")
                }
            }
            result
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startMs
            Log.e(LTAG, "execute EXCEPTION tool=$tool elapsed=${elapsed}ms", e)
            Log.e(TAG, "Tool $tool failed", e)
            SkillResult.Error("$tool failed: ${e.message}")
        }
    }

    override fun cleanup() {
        if (displayActive) {
            Log.w(TAG, "cleanup: virtual display still active — destroying")
            try {
                getService().destroyAgentDisplay()
            } catch (e: Exception) {
                Log.e(TAG, "cleanup: failed to destroy display", e)
            }
            displayActive = false
        }
    }

    // ── Screenshot capture ──────────────────────────────────────────────

    private fun captureScreenshot(): SkillResult {
        Log.w(DTAG, "⚠️ SCREENSHOT_REQUESTED — LLM chose agent_display_screenshot instead of using a11y tree!")
        Log.d(LTAG, "captureScreenshot: requesting frame from service")
        val frame = getService().captureFrame()
        if (frame == null) {
            Log.w(LTAG, "captureScreenshot: captureFrame returned null (no frame rendered yet)")
            return SkillResult.Error("No frame available — the display may not have rendered content yet.")
        }
        Log.d(LTAG, "captureScreenshot: raw frame JPEG from service = ${frame.size} bytes")
        return compressAndReturn(frame, "Screenshot captured")
    }

    private fun compressAndReturn(frame: ByteArray, label: String): SkillResult {
        // Pass JPEG through directly when it's already small enough — avoids
        // the expensive decode→reencode roundtrip.
        if (frame.size <= MAX_IMAGE_BYTES) {
            Log.i(LTAG, "compressAndReturn: JPEG passthrough ${frame.size} bytes")
            val base64 = Base64.encodeToString(frame, Base64.NO_WRAP)
            return SkillResult.ImageSuccess(
                text = "$label (${frame.size} bytes). The image is attached — analyze it to understand the current display state.",
                base64 = base64,
                mediaType = "image/jpeg",
            )
        }

        // JPEG too large — request lower quality from service instead of
        // re-encoding client side.
        var quality = 60
        var compressed = frame
        while (compressed.size > MAX_IMAGE_BYTES && quality >= MIN_QUALITY) {
            compressed = getService().captureFrameWithQuality(quality) ?: break
            Log.d(LTAG, "compressAndReturn: service q=$quality → ${compressed.size} bytes")
            quality -= 10
        }

        val base64 = Base64.encodeToString(compressed, Base64.NO_WRAP)
        Log.i(LTAG, "compressAndReturn: FINAL ${compressed.size} bytes, quality=$quality")
        return SkillResult.ImageSuccess(
            text = "$label (${compressed.size} bytes). The image is attached — analyze it to understand the current display state.",
            base64 = base64,
            mediaType = "image/jpeg",
        )
    }

    /** Execute an action, wait for the UI to settle, then return the accessibility UI tree. */
    private suspend fun actionWithUiTree(
        delayMs: Long,
        description: String,
        action: () -> Unit,
    ): SkillResult {
        Log.d(LTAG, "actionWithUiTree: executing action, then waiting ${delayMs}ms for UI settle")
        Log.i(DTAG, "ACTION_WITH_TREE: $description (delay=${delayMs}ms)")
        action()
        delay(delayMs)
        Log.d(LTAG, "actionWithUiTree: delay done, fetching UI tree")
        val tree = getService().accessibilityTree ?: "{}"
        Log.i(DTAG, "ACTION_WITH_TREE: got tree (${tree.length} chars) for: $description")
        return SkillResult.Success(formatTreeResponse(description, tree))
    }

    /**
     * Format the tree response for the LLM. Detects whether the JSON is from
     * the new ScreenAnalyzer (has "screen" key) or legacy format (has "windows" key)
     * and formats accordingly.
     */
    private fun formatTreeResponse(description: String, treeJson: String): String {
        return try {
            val root = org.json.JSONObject(treeJson)

            // New smart format from ScreenAnalyzer
            if (root.has("screen")) {
                Log.i(DTAG, "FORMAT_TREE: using SMART (ScreenAnalyzer) format for: $description")
                return formatSmartTreeResponse(description, treeJson)
            }

            Log.i(DTAG, "FORMAT_TREE: using LEGACY format for: $description")
            // Legacy format
            val elements = root.optJSONArray("elements")
            val sb = StringBuilder()
            sb.append(description).append("\n\n")

            if (elements != null && elements.length() > 0) {
                sb.append("=== Interactive Elements (use viewId with click_node/set_node_text/scroll_node) ===\n")
                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    val idx = el.optInt("idx", i)
                    val cls = el.optString("cls", "")
                    val id = el.optString("id", "")
                    val text = el.optString("text", "")
                    val desc = el.optString("desc", "")
                    val flags = el.optString("flags", "")
                    val bounds = el.optString("bounds", "")

                    sb.append("[$idx] $cls")
                    if (text.isNotEmpty()) sb.append(" \"$text\"")
                    if (desc.isNotEmpty()) sb.append(" ($desc)")
                    if (flags.isNotEmpty()) sb.append(" [$flags]")
                    if (id.isNotEmpty()) sb.append(" id:$id")
                    if (bounds.isNotEmpty()) sb.append(" @$bounds")
                    sb.append("\n")
                }
            } else {
                sb.append("No interactive elements found. Use agent_display_screenshot to visually inspect the screen.\n")
            }

            sb.toString()
        } catch (e: Exception) {
            Log.w(LTAG, "formatTreeResponse: JSON parse failed, returning raw", e)
            "$description\n\nUI Tree:\n$treeJson"
        }
    }

    /**
     * Format the new ScreenAnalyzer JSON into a compact, LLM-friendly text format.
     * Each element gets one line: [id] type "label" (summary) [actions] {bounds}
     */
    private fun formatSmartTreeResponse(description: String, treeJson: String): String {
        return try {
            val root = org.json.JSONObject(treeJson)
            val screen = root.optJSONObject("screen")
            val elements = root.optJSONArray("elements")
            val scrollable = root.optBoolean("scrollable", false)

            val elementCount = elements?.length() ?: 0
            val pkg = screen?.optString("package", "") ?: ""
            val title = screen?.optString("title", "") ?: ""
            Log.i(DTAG, "SMART_TREE: screen=$title ($pkg) | elements=$elementCount | scrollable=$scrollable")

            // Log element types breakdown
            if (elements != null && elements.length() > 0) {
                val typeCounts = mutableMapOf<String, Int>()
                val viewIdCount = (0 until elements.length()).count { elements.getJSONObject(it).optString("viewId", "").isNotEmpty() }
                for (i in 0 until elements.length()) {
                    val type = elements.getJSONObject(i).optString("type", "unknown")
                    typeCounts[type] = (typeCounts[type] ?: 0) + 1
                }
                Log.i(DTAG, "SMART_TREE_TYPES: $typeCounts | withViewId=$viewIdCount/${elements.length()}")
            }

            val sb = StringBuilder()

            sb.append(description).append("\n\n")

            // Screen context
            if (screen != null) {
                val pkg = screen.optString("package", "")
                val title = screen.optString("title", "")
                if (title.isNotEmpty()) sb.append("Screen: $title ($pkg)\n")
                else if (pkg.isNotEmpty()) sb.append("Screen: $pkg\n")
            }
            if (scrollable) sb.append("(scrollable)\n")
            sb.append("\n")

            if (elements != null && elements.length() > 0) {
                sb.append("=== UI Elements (use viewId with click_node/set_node_text/scroll_node, or tap at bounds center) ===\n")
                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    val id = el.optInt("id", i)
                    val type = el.optString("type", "")
                    val label = el.optString("label", "")
                    val summary = el.optString("summary", "")
                    val hint = el.optString("hint", "")
                    val checked = if (el.has("checked")) el.optBoolean("checked") else null
                    val selected = if (el.has("selected")) el.optBoolean("selected") else null
                    val enabled = if (el.has("enabled")) el.optBoolean("enabled") else null
                    val password = if (el.has("password")) el.optBoolean("password") else null
                    val viewId = el.optString("viewId", "")
                    val actions = el.optJSONArray("actions")
                    val bounds = el.optJSONObject("bounds")

                    sb.append("[$id] $type")
                    if (label.isNotEmpty()) sb.append(" \"$label\"")
                    if (summary.isNotEmpty()) sb.append(" — $summary")
                    if (hint.isNotEmpty()) sb.append(" hint:\"$hint\"")
                    if (checked != null) sb.append(if (checked) " [checked]" else " [unchecked]")
                    if (selected == true) sb.append(" [selected]")
                    if (enabled == false) sb.append(" [disabled]")
                    if (password == true) sb.append(" [password]")

                    if (actions != null && actions.length() > 0) {
                        val actionList = (0 until actions.length()).map { actions.getString(it) }
                        sb.append(" {${actionList.joinToString(",")}}")
                    }

                    if (viewId.isNotEmpty()) sb.append(" viewId:$viewId")

                    val cx = el.optInt("center_x", -1)
                    val cy = el.optInt("center_y", -1)
                    if (cx >= 0 && cy >= 0) sb.append(" @($cx,$cy)")

                    sb.append("\n")
                }
            } else {
                sb.append("No elements found. Use agent_display_screenshot to visually inspect the screen.\n")
            }

            sb.toString()
        } catch (e: Exception) {
            Log.w(LTAG, "formatSmartTreeResponse: JSON parse failed, returning raw", e)
            "$description\n\n$treeJson"
        }
    }

    // ── Tool implementations ────────────────────────────────────────────

    // -- Display lifecycle --

    private suspend fun doCreate(params: JsonObject): SkillResult {
        val pkg = params["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: package_name")
        val svc = getService()
        svc.createAgentDisplay(DISPLAY_WIDTH, DISPLAY_HEIGHT, DISPLAY_DPI)
        displayActive = true
        val displayId = svc.displayId
        svc.launchApp(pkg)
        delay(DELAY_LAUNCH)
        val tree = svc.accessibilityTree ?: "{}"
        return SkillResult.Success(
            formatTreeResponse(
                "Virtual display created (ID: $displayId, ${DISPLAY_WIDTH}x${DISPLAY_HEIGHT} @ ${DISPLAY_DPI}dpi) and launched $pkg.",
                tree,
            )
        )
    }

    private fun doDestroy(): SkillResult {
        getService().destroyAgentDisplay()
        displayActive = false
        return SkillResult.Success("Virtual display destroyed.")
    }

    private fun doDestroyAndPromote(): SkillResult {
        getService().destroyAgentDisplayAndPromote()
        displayActive = false
        return SkillResult.Success("Virtual display destroyed and the running app has been moved to the user's main screen.")
    }

    private fun doGetInfo(): SkillResult {
        val info = getService().displayInfo
        return SkillResult.Success(info ?: "{\"error\":\"Display not created\"}")
    }

    private suspend fun doResize(params: JsonObject): SkillResult {
        val width = params["width"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: width")
        val height = params["height"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: height")
        val dpi = params["dpi"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: dpi")
        return actionWithUiTree(DELAY_LAUNCH, "Resized display to ${width}x${height} @ ${dpi}dpi.") {
            getService().resizeAgentDisplay(width, height, dpi)
        }
    }

    // -- App management --

    private suspend fun doLaunchActivity(params: JsonObject): SkillResult {
        val pkg = params["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: package_name")
        val activity = params["activity_name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: activity_name")
        return actionWithUiTree(DELAY_LAUNCH, "Launched $pkg/$activity.") {
            getService().launchActivity(pkg, activity)
        }
    }

    private suspend fun doLaunchIntent(params: JsonObject): SkillResult {
        val uri = params["uri"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: uri")
        return actionWithUiTree(DELAY_LAUNCH, "Launched intent: $uri.") {
            getService().launchIntentUri(uri)
        }
    }

    private fun doCurrentActivity(): SkillResult {
        val activity = getService().currentActivity
        return SkillResult.Success(activity ?: "null (no activity running)")
    }

    // -- Touch gestures --

    private suspend fun doTap(params: JsonObject): SkillResult {
        val x = params["x"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: x")
        val y = params["y"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: y")
        return actionWithUiTree(DELAY_TAP, "Tapped at ($x, $y).") {
            getService().tap(x, y)
        }
    }

    private suspend fun doLongPress(params: JsonObject): SkillResult {
        val x = params["x"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: x")
        val y = params["y"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: y")
        val duration = params["duration_ms"]?.jsonPrimitive?.longOrNull ?: 500L
        return actionWithUiTree(DELAY_TAP, "Long pressed at ($x, $y) for ${duration}ms.") {
            getService().longPress(x, y, duration)
        }
    }

    private suspend fun doDoubleTap(params: JsonObject): SkillResult {
        val x = params["x"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: x")
        val y = params["y"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: y")
        val interval = params["interval_ms"]?.jsonPrimitive?.longOrNull ?: 100L
        return actionWithUiTree(DELAY_TAP, "Double tapped at ($x, $y).") {
            getService().doubleTap(x, y, interval)
        }
    }

    private suspend fun doSwipe(params: JsonObject): SkillResult {
        val x1 = params["x1"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: x1")
        val y1 = params["y1"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: y1")
        val x2 = params["x2"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: x2")
        val y2 = params["y2"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: y2")
        val duration = params["duration_ms"]?.jsonPrimitive?.intOrNull ?: 300
        return actionWithUiTree(DELAY_SWIPE, "Swiped from ($x1,$y1) to ($x2,$y2) over ${duration}ms.") {
            getService().swipe(x1, y1, x2, y2, duration)
        }
    }

    private suspend fun doFling(params: JsonObject): SkillResult {
        val x1 = params["x1"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: x1")
        val y1 = params["y1"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: y1")
        val x2 = params["x2"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: x2")
        val y2 = params["y2"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: y2")
        return actionWithUiTree(DELAY_SWIPE, "Flung from ($x1,$y1) to ($x2,$y2).") {
            getService().fling(x1, y1, x2, y2)
        }
    }

    private suspend fun doDrag(params: JsonObject): SkillResult {
        val sx = params["start_x"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: start_x")
        val sy = params["start_y"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: start_y")
        val ex = params["end_x"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: end_x")
        val ey = params["end_y"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: end_y")
        val holdMs = params["hold_before_drag_ms"]?.jsonPrimitive?.longOrNull ?: 500L
        val dragMs = params["drag_duration_ms"]?.jsonPrimitive?.intOrNull ?: 500
        return actionWithUiTree(DELAY_DRAG, "Dragged from ($sx,$sy) to ($ex,$ey).") {
            getService().drag(sx, sy, ex, ey, holdMs, dragMs)
        }
    }

    private suspend fun doPinch(params: JsonObject): SkillResult {
        val cx = params["center_x"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: center_x")
        val cy = params["center_y"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: center_y")
        val startSpan = params["start_span"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: start_span")
        val endSpan = params["end_span"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: end_span")
        val duration = params["duration_ms"]?.jsonPrimitive?.intOrNull ?: 500
        val action = if (startSpan > endSpan) "Pinched in" else "Pinched out"
        return actionWithUiTree(DELAY_PINCH, "$action at ($cx,$cy).") {
            getService().pinch(cx, cy, startSpan, endSpan, duration)
        }
    }

    private suspend fun doGesture(params: JsonObject): SkillResult {
        val xArr = params["x_points"]?.jsonArray
            ?: return SkillResult.Error("Missing required parameter: x_points")
        val yArr = params["y_points"]?.jsonArray
            ?: return SkillResult.Error("Missing required parameter: y_points")
        val tArr = params["timestamps_ms"]?.jsonArray
            ?: return SkillResult.Error("Missing required parameter: timestamps_ms")
        if (xArr.size != yArr.size || xArr.size != tArr.size || xArr.size < 2) {
            return SkillResult.Error("x_points, y_points, and timestamps_ms must have the same length (min 2)")
        }
        val xPoints = FloatArray(xArr.size) { xArr[it].jsonPrimitive.floatOrNull ?: 0f }
        val yPoints = FloatArray(yArr.size) { yArr[it].jsonPrimitive.floatOrNull ?: 0f }
        val timestamps = LongArray(tArr.size) { tArr[it].jsonPrimitive.longOrNull ?: 0L }
        return actionWithUiTree(DELAY_TAP, "Gesture with ${xPoints.size} waypoints.") {
            getService().gesture(xPoints, yPoints, timestamps)
        }
    }

    // -- Key input --

    private suspend fun doPressBack(): SkillResult {
        return actionWithUiTree(DELAY_KEY, "Pressed Back.") {
            getService().pressBack()
        }
    }

    private suspend fun doPressHome(): SkillResult {
        return actionWithUiTree(DELAY_KEY, "Pressed Home.") {
            getService().pressHome()
        }
    }

    private suspend fun doPressEnter(): SkillResult {
        return actionWithUiTree(DELAY_KEY, "Pressed Enter.") {
            getService().pressEnter()
        }
    }

    private suspend fun doPressRecents(): SkillResult {
        return actionWithUiTree(DELAY_KEY, "Pressed Recents.") {
            getService().pressRecents()
        }
    }

    private suspend fun doPressKey(params: JsonObject): SkillResult {
        val keyCode = params["key_code"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: key_code")
        val metaState = params["meta_state"]?.jsonPrimitive?.intOrNull
        val holdMs = params["hold_duration_ms"]?.jsonPrimitive?.longOrNull
        return actionWithUiTree(DELAY_KEY, "Pressed key $keyCode.") {
            when {
                holdMs != null && holdMs > 0 -> getService().pressKeyWithDuration(keyCode, holdMs)
                metaState != null && metaState != 0 -> getService().pressKeyWithMeta(keyCode, metaState)
                else -> getService().pressKey(keyCode)
            }
        }
    }

    // -- Text input --

    private suspend fun doTypeText(params: JsonObject): SkillResult {
        val text = params["text"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: text")
        val delayMs = params["delay_ms"]?.jsonPrimitive?.intOrNull ?: 0
        return if (delayMs > 0) {
            val totalWait = (text.length * delayMs).toLong().coerceAtMost(5000L) + DELAY_TYPE
            actionWithUiTree(totalWait, "Typed text: \"$text\" (${delayMs}ms/char).") {
                getService().inputTextWithDelay(text, delayMs)
            }
        } else {
            actionWithUiTree(DELAY_TYPE, "Typed text: \"$text\".") {
                getService().inputText(text)
            }
        }
    }

    // -- Clipboard --

    private fun doSetClipboard(params: JsonObject): SkillResult {
        val text = params["text"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: text")
        getService().setClipboard(text)
        return SkillResult.Success("Clipboard set. Use agent_display_press_key with key_code=50, meta_state=4096 (Ctrl+V) to paste.")
    }

    private fun doGetClipboard(): SkillResult {
        val text = getService().clipboard
        return SkillResult.Success(text ?: "null (clipboard empty or non-text)")
    }

    // -- Accessibility --

    private fun doGetUiTree(): SkillResult {
        // The accessibility service now uses ScreenAnalyzer (smart analysis) automatically
        // via buildTreeForDisplay -> buildSmartTreeForDisplay, with legacy fallback
        val tree = getService().accessibilityTree ?: "{}"
        return SkillResult.Success(formatTreeResponse("Current UI state:", tree))
    }

    /**
     * Execute a node action (which now returns a JSON result string) and
     * return the updated UI tree on success, or a clear error on failure.
     */
    private suspend fun nodeActionWithTree(
        viewId: String,
        delayMs: Long,
        description: String,
        action: () -> String,
    ): SkillResult {
        Log.i(DTAG, "NODE_ACTION: $description viewId=$viewId")
        val result = action()
        val json = org.json.JSONObject(result)
        if (!json.optBoolean("ok", false)) {
            val error = json.optString("error", "Unknown error")
            Log.e(DTAG, "NODE_ACTION_FAILED: $description viewId=$viewId error=$error")
            return SkillResult.Error("$description failed: $error")
        }
        val method = json.optString("method", "")
        Log.i(DTAG, "NODE_ACTION_OK: $description viewId=$viewId method=$method")
        delay(delayMs)
        val tree = getService().accessibilityTree ?: "{}"
        Log.i(DTAG, "NODE_ACTION_TREE: got tree (${tree.length} chars) after: $description")
        val desc = if (method.isNotEmpty()) "$description (via $method)" else description
        return SkillResult.Success(formatTreeResponse(desc, tree))
    }

    private suspend fun doClickNode(params: JsonObject): SkillResult {
        val viewId = params["view_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: view_id")
        return nodeActionWithTree(viewId, DELAY_NODE_CLICK, "Clicked node: $viewId.") {
            getService().clickNode(viewId)
        }
    }

    private suspend fun doLongClickNode(params: JsonObject): SkillResult {
        val viewId = params["view_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: view_id")
        return nodeActionWithTree(viewId, DELAY_NODE_CLICK, "Long-clicked node: $viewId.") {
            getService().longClickNode(viewId)
        }
    }

    private suspend fun doSetNodeText(params: JsonObject): SkillResult {
        val viewId = params["view_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: view_id")
        val text = params["text"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: text")
        return nodeActionWithTree(viewId, DELAY_NODE_TEXT, "Set text \"$text\" on node: $viewId.") {
            getService().setNodeText(viewId, text)
        }
    }

    private suspend fun doScrollNode(params: JsonObject): SkillResult {
        val viewId = params["view_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: view_id")
        val direction = params["direction"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: direction")
        val actionResult = when (direction) {
            "forward" -> getService().scrollNodeForward(viewId)
            "backward" -> getService().scrollNodeBackward(viewId)
            else -> return SkillResult.Error("direction must be 'forward' or 'backward'")
        }
        val json = org.json.JSONObject(actionResult)
        if (!json.optBoolean("ok", false)) {
            return SkillResult.Error("Scroll $direction on $viewId failed: ${json.optString("error")}")
        }
        delay(DELAY_SWIPE)
        val tree = getService().accessibilityTree ?: "{}"
        return SkillResult.Success(formatTreeResponse("Scrolled $direction on node: $viewId.", tree))
    }

    private suspend fun doFocusNode(params: JsonObject): SkillResult {
        val viewId = params["view_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: view_id")
        return nodeActionWithTree(viewId, DELAY_TAP, "Focused node: $viewId.") {
            getService().focusNode(viewId)
        }
    }

    private fun doGetNodeInfo(params: JsonObject): SkillResult {
        val viewId = params["view_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: view_id")
        val info = getService().getNodeInfo(viewId)
        return SkillResult.Success(info ?: """{"error":"Node not found: $viewId"}""")
    }

    // ── Schema helpers ──────────────────────────────────────────────────

    private fun tool(
        name: String,
        description: String,
        props: Map<String, JsonObject>,
        required: List<String> = emptyList(),
    ) = ToolDefinition(
        name = name,
        description = description,
        inputSchema = JsonObject(buildMap {
            put("type", JsonPrimitive("object"))
            put("properties", JsonObject(props))
            if (required.isNotEmpty()) {
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }
        }),
        // Rung 4 of the execution ladder: a model in the loop, a screenshot or a tree
        // per step, and full latency every time. Everything here is the last resort,
        // which is what `routeGateCheck` enforces when a lower rung exists.
        rung = ToolRoutes.RUNG_DISPLAY,
    )

    private fun propString(description: String) = JsonObject(mapOf(
        "type" to JsonPrimitive("string"),
        "description" to JsonPrimitive(description),
    ))

    private fun propNumber(description: String) = JsonObject(mapOf(
        "type" to JsonPrimitive("number"),
        "description" to JsonPrimitive(description),
    ))

    private fun propNumberArray(description: String) = JsonObject(mapOf(
        "type" to JsonPrimitive("array"),
        "items" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
        "description" to JsonPrimitive(description),
    ))

    private fun propEnum(description: String, values: List<String>) = JsonObject(mapOf(
        "type" to JsonPrimitive("string"),
        "description" to JsonPrimitive(description),
        "enum" to JsonArray(values.map { JsonPrimitive(it) }),
    ))
}
