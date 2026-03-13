package org.ethereumphone.andyclaw.skills.builtin

import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.IAgentDisplayService
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
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

class AgentDisplaySkill : AndyClawSkill {

    companion object {
        private const val TAG = "AgentDisplaySkill"
        private const val LTAG = "AGENT_VIRTUAL_SCREEN" // verbose logging tag
        private const val DISPLAY_WIDTH = 720
        private const val DISPLAY_HEIGHT = 720
        private const val DISPLAY_DPI = 240
        /** Max compressed image size in bytes (before base64 encoding). */
        private const val MAX_IMAGE_BYTES = 200_000 // ~266 KB as base64
        private const val MIN_QUALITY = 40
        // Wait times (ms) after actions before auto-capturing screenshot
        private const val DELAY_TAP = 500L
        private const val DELAY_SWIPE = 800L
        private const val DELAY_TYPE = 300L
        private const val DELAY_KEY = 500L
        private const val DELAY_LAUNCH = 2500L
        private const val DELAY_NODE_CLICK = 500L
        private const val DELAY_NODE_TEXT = 300L
        private const val DELAY_DRAG = 500L
        private const val DELAY_PINCH = 500L
    }

    override val id = "agent_display"
    override val name = "Agent Display"

    // No tools on the OPEN tier — this is privileged-only.
    override val baseManifest = SkillManifest(
        description = "Operate a virtual Android display (${DISPLAY_WIDTH}x${DISPLAY_HEIGHT}). Privileged-only.",
        tools = emptyList(),
    )

    override val privilegedManifest = SkillManifest(
        description = "Operate a virtual Android display. Create it, launch apps, and interact via taps, swipes, gestures, text input, clipboard, and accessibility actions. Every action automatically returns the UI accessibility tree so you can understand the current state. Use agent_display_screenshot when you need a visual capture. Use this to perform tasks in apps on behalf of the user.",
        tools = listOf(
            // ── Display Lifecycle ───────────────────────────────────────
            tool(
                name = "agent_display_create",
                description = "Create the virtual display (${DISPLAY_WIDTH}x${DISPLAY_HEIGHT} @ ${DISPLAY_DPI}dpi). Must be called before any other agent_display tool. Returns the UI tree of the initial state.",
                props = emptyMap(),
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
                name = "agent_display_get_info",
                description = "Get display info as JSON (displayId, width, height, dpi). Useful to confirm dimensions.",
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
                name = "agent_display_launch_app",
                description = "Launch an app by package name on the virtual display. Returns the UI tree after the app starts.",
                props = mapOf(
                    "package_name" to propString("The Android package name, e.g. com.android.settings"),
                ),
                required = listOf("package_name"),
            ),
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

            // ── Screenshot ──────────────────────────────────────────────
            tool(
                name = "agent_display_screenshot",
                description = "Capture a visual screenshot of the virtual display. Returns an image you can see and analyze. Use this when you need to visually inspect the screen (e.g. to read rendered content, verify layout, or see images/icons).",
                props = emptyMap(),
            ),
            tool(
                name = "agent_display_capture_region",
                description = "Capture a cropped region of the display as a screenshot. Useful for focusing on a specific UI element.",
                props = mapOf(
                    "x" to propNumber("Left edge X coordinate"),
                    "y" to propNumber("Top edge Y coordinate"),
                    "width" to propNumber("Region width in pixels"),
                    "height" to propNumber("Region height in pixels"),
                ),
                required = listOf("x", "y", "width", "height"),
            ),

            // ── Touch Gestures ──────────────────────────────────────────
            tool(
                name = "agent_display_tap",
                description = "Tap at (x, y) coordinates on the virtual display. Quick 50ms tap. Returns the UI tree.",
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
                description = "Swipe from (x1,y1) to (x2,y2) with smooth intermediate points (~60fps). Use duration 500-1000ms for scroll (no inertia), 100-200ms for fast scroll. Returns the UI tree.",
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

            // ── Text Input ──────────────────────────────────────────────
            tool(
                name = "agent_display_type_text",
                description = "Type text instantly into the currently focused field. Returns the UI tree.",
                props = mapOf(
                    "text" to propString("The text to type"),
                ),
                required = listOf("text"),
            ),
            tool(
                name = "agent_display_type_text_slow",
                description = "Type text with a delay between each character. Useful for search fields that show suggestions per keystroke, or apps that process input character-by-character. Returns the UI tree.",
                props = mapOf(
                    "text" to propString("The text to type"),
                    "delay_ms" to propNumber("Delay between each character in ms (default 50)"),
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

            // ── Accessibility ───────────────────────────────────────────
            tool(
                name = "agent_display_get_ui_tree",
                description = "Get the accessibility tree (JSON) of the virtual display. Use this to understand the UI structure, find view IDs, and determine what elements are visible.",
                props = emptyMap(),
            ),
            tool(
                name = "agent_display_click_node",
                description = "Click a UI node by its accessibility view ID (e.g. 'com.android.settings:id/search_bar'). Returns the UI tree.",
                props = mapOf(
                    "view_id" to propString("The accessibility view ID of the node to click"),
                ),
                required = listOf("view_id"),
            ),
            tool(
                name = "agent_display_long_click_node",
                description = "Long-click a UI node by its accessibility view ID. Triggers context menus. Returns the UI tree.",
                props = mapOf(
                    "view_id" to propString("The accessibility view ID of the node to long-click"),
                ),
                required = listOf("view_id"),
            ),
            tool(
                name = "agent_display_set_node_text",
                description = "Set text on a UI node by its accessibility view ID. Returns the UI tree.",
                props = mapOf(
                    "view_id" to propString("The accessibility view ID of the text field"),
                    "text" to propString("The text to set"),
                ),
                required = listOf("view_id", "text"),
            ),
            tool(
                name = "agent_display_scroll_node",
                description = "Scroll a scrollable node forward (down/right) or backward (up/left) by its accessibility view ID. Returns the UI tree.",
                props = mapOf(
                    "view_id" to propString("The accessibility view ID of the scrollable node"),
                    "direction" to propEnum("Scroll direction", listOf("forward", "backward")),
                ),
                required = listOf("view_id", "direction"),
            ),
            tool(
                name = "agent_display_focus_node",
                description = "Set accessibility focus on a node by its view ID. Returns the UI tree.",
                props = mapOf(
                    "view_id" to propString("The accessibility view ID of the node to focus"),
                ),
                required = listOf("view_id"),
            ),
            tool(
                name = "agent_display_get_node_info",
                description = "Get detailed info about a specific accessibility node (bounds, text, contentDescription, enabled/clickable/scrollable state, etc.).",
                props = mapOf(
                    "view_id" to propString("The accessibility view ID of the node to inspect"),
                ),
                required = listOf("view_id"),
            ),
        ),
    )

    private var service: IAgentDisplayService? = null
    @Volatile private var displayActive = false

    private fun getService(): IAgentDisplayService {
        service?.let { return it }
        val svc = try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "agentdisplay") as? IBinder
            binder?.let { IAgentDisplayService.Stub.asInterface(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get AgentDisplayService", e)
            null
        } ?: throw IllegalStateException("AgentDisplayService not available")
        service = svc
        return svc
    }

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        Log.i(LTAG, "execute START tool=$tool params=$params tier=$tier")
        val startMs = System.currentTimeMillis()
        return try {
            val result = when (tool) {
                // Display lifecycle
                "agent_display_create" -> doCreate()
                "agent_display_destroy" -> doDestroy()
                "agent_display_destroy_and_promote" -> doDestroyAndPromote()
                "agent_display_get_info" -> doGetInfo()
                "agent_display_resize" -> doResize(params)
                // App management
                "agent_display_launch_app" -> doLaunchApp(params)
                "agent_display_launch_activity" -> doLaunchActivity(params)
                "agent_display_launch_intent" -> doLaunchIntent(params)
                "agent_display_current_activity" -> doCurrentActivity()
                // Screenshots
                "agent_display_screenshot" -> captureScreenshot()
                "agent_display_capture_region" -> doCaptureRegion(params)
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
                "agent_display_type_text_slow" -> doTypeTextSlow(params)
                // Clipboard
                "agent_display_set_clipboard" -> doSetClipboard(params)
                "agent_display_get_clipboard" -> doGetClipboard()
                // Accessibility
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
                is SkillResult.ImageSuccess -> Log.i(LTAG, "execute DONE tool=$tool elapsed=${elapsed}ms resultType=ImageSuccess base64Len=${result.base64.length} mediaType=${result.mediaType} textLen=${result.text.length}")
                is SkillResult.Success -> Log.i(LTAG, "execute DONE tool=$tool elapsed=${elapsed}ms resultType=Success dataLen=${result.data.length}")
                is SkillResult.Error -> Log.w(LTAG, "execute DONE tool=$tool elapsed=${elapsed}ms resultType=Error msg=${result.message}")
                is SkillResult.RequiresApproval -> Log.i(LTAG, "execute DONE tool=$tool elapsed=${elapsed}ms resultType=RequiresApproval")
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
        Log.d(LTAG, "captureScreenshot: requesting frame from service")
        val frame = getService().captureFrame()
        if (frame == null) {
            Log.w(LTAG, "captureScreenshot: captureFrame returned null (no frame rendered yet)")
            return SkillResult.Error("No frame available — the display may not have rendered content yet.")
        }
        Log.d(LTAG, "captureScreenshot: raw frame JPEG from service = ${frame.size} bytes")
        return compressAndReturn(frame, "Screenshot captured")
    }

    private fun captureRegion(x: Int, y: Int, width: Int, height: Int): SkillResult {
        Log.d(LTAG, "captureRegion: x=$x y=$y w=$width h=$height")
        val frame = getService().captureFrameRegion(x, y, width, height, 80)
        if (frame == null) {
            Log.w(LTAG, "captureRegion: returned null")
            return SkillResult.Error("No frame available for the specified region.")
        }
        return compressAndReturn(frame, "Region captured (${x},${y} ${width}x${height})")
    }

    private fun compressAndReturn(frame: ByteArray, label: String): SkillResult {
        val bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.size)
        if (bitmap == null) {
            Log.e(LTAG, "compressAndReturn: BitmapFactory.decodeByteArray returned null for ${frame.size} bytes")
            return SkillResult.Error("Failed to decode captured frame.")
        }
        Log.d(LTAG, "compressAndReturn: decoded bitmap ${bitmap.width}x${bitmap.height}, config=${bitmap.config}")

        var quality = 80
        var compressed: ByteArray
        do {
            val out = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
            compressed = out.toByteArray()
            Log.d(LTAG, "compressAndReturn: WebP q=$quality → ${compressed.size} bytes (limit=$MAX_IMAGE_BYTES)")
            if (compressed.size <= MAX_IMAGE_BYTES) break
            quality -= 10
        } while (quality >= MIN_QUALITY)
        bitmap.recycle()

        val base64 = Base64.encodeToString(compressed, Base64.NO_WRAP)
        Log.i(LTAG, "compressAndReturn: FINAL compressed=${compressed.size} bytes, base64Len=${base64.length} chars, quality=$quality")
        return SkillResult.ImageSuccess(
            text = "$label (${compressed.size} bytes). The image is attached — analyze it to understand the current display state.",
            base64 = base64,
            mediaType = "image/webp",
        )
    }

    /** Execute an action, wait for the UI to settle, then return the accessibility UI tree. */
    private suspend fun actionWithUiTree(
        delayMs: Long,
        description: String,
        action: () -> Unit,
    ): SkillResult {
        Log.d(LTAG, "actionWithUiTree: executing action, then waiting ${delayMs}ms for UI settle")
        action()
        delay(delayMs)
        Log.d(LTAG, "actionWithUiTree: delay done, fetching UI tree")
        val tree = getService().accessibilityTree ?: "{}"
        return SkillResult.Success("$description\n\nUI Tree:\n$tree")
    }

    // ── Tool implementations ────────────────────────────────────────────

    // -- Display lifecycle --

    private suspend fun doCreate(): SkillResult {
        val svc = getService()
        svc.createAgentDisplay(DISPLAY_WIDTH, DISPLAY_HEIGHT, DISPLAY_DPI)
        displayActive = true
        val displayId = svc.displayId
        delay(DELAY_LAUNCH)
        val tree = svc.accessibilityTree ?: "{}"
        return SkillResult.Success(
            "Virtual display created (ID: $displayId, ${DISPLAY_WIDTH}x${DISPLAY_HEIGHT} @ ${DISPLAY_DPI}dpi). " +
            "You can now launch apps and interact with it. Use agent_display_screenshot to capture a visual screenshot when needed.\n\nUI Tree:\n$tree"
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

    private suspend fun doLaunchApp(params: JsonObject): SkillResult {
        val pkg = params["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: package_name")
        return actionWithUiTree(DELAY_LAUNCH, "Launched $pkg.") {
            getService().launchApp(pkg)
        }
    }

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

    // -- Screenshots --

    private fun doCaptureRegion(params: JsonObject): SkillResult {
        val x = params["x"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: x")
        val y = params["y"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: y")
        val w = params["width"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: width")
        val h = params["height"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: height")
        return captureRegion(x, y, w, h)
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
        return actionWithUiTree(DELAY_TYPE, "Typed text: \"$text\".") {
            getService().inputText(text)
        }
    }

    private suspend fun doTypeTextSlow(params: JsonObject): SkillResult {
        val text = params["text"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: text")
        val delayMs = params["delay_ms"]?.jsonPrimitive?.intOrNull ?: 50
        // Total delay = text length * delayMs + settle time
        val totalWait = (text.length * delayMs).toLong().coerceAtMost(5000L) + DELAY_TYPE
        return actionWithUiTree(totalWait, "Typed text slowly: \"$text\" (${delayMs}ms/char).") {
            getService().inputTextWithDelay(text, delayMs)
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
        val tree = getService().accessibilityTree ?: "{}"
        return SkillResult.Success(tree)
    }

    private suspend fun doClickNode(params: JsonObject): SkillResult {
        val viewId = params["view_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: view_id")
        return actionWithUiTree(DELAY_NODE_CLICK, "Clicked node: $viewId.") {
            getService().clickNode(viewId)
        }
    }

    private suspend fun doLongClickNode(params: JsonObject): SkillResult {
        val viewId = params["view_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: view_id")
        return actionWithUiTree(DELAY_NODE_CLICK, "Long-clicked node: $viewId.") {
            getService().longClickNode(viewId)
        }
    }

    private suspend fun doSetNodeText(params: JsonObject): SkillResult {
        val viewId = params["view_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: view_id")
        val text = params["text"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: text")
        return actionWithUiTree(DELAY_NODE_TEXT, "Set text \"$text\" on node: $viewId.") {
            getService().setNodeText(viewId, text)
        }
    }

    private suspend fun doScrollNode(params: JsonObject): SkillResult {
        val viewId = params["view_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: view_id")
        val direction = params["direction"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: direction")
        return actionWithUiTree(DELAY_SWIPE, "Scrolled $direction on node: $viewId.") {
            when (direction) {
                "forward" -> getService().scrollNodeForward(viewId)
                "backward" -> getService().scrollNodeBackward(viewId)
                else -> throw IllegalArgumentException("direction must be 'forward' or 'backward'")
            }
        }
    }

    private suspend fun doFocusNode(params: JsonObject): SkillResult {
        val viewId = params["view_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: view_id")
        return actionWithUiTree(DELAY_TAP, "Focused node: $viewId.") {
            getService().focusNode(viewId)
        }
    }

    private fun doGetNodeInfo(params: JsonObject): SkillResult {
        val viewId = params["view_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: view_id")
        val info = getService().getNodeInfo(viewId)
        return SkillResult.Success(info ?: "{\"error\":\"Node not found: $viewId\"}")
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
