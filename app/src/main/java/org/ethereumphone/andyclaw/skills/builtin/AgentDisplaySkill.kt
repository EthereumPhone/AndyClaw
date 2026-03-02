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
import kotlinx.serialization.json.jsonPrimitive
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
    }

    override val id = "agent_display"
    override val name = "Agent Display"

    // No tools on the OPEN tier — this is privileged-only.
    override val baseManifest = SkillManifest(
        description = "Operate a virtual Android display (${DISPLAY_WIDTH}x${DISPLAY_HEIGHT}). Privileged-only.",
        tools = emptyList(),
    )

    override val privilegedManifest = SkillManifest(
        description = "Operate a virtual Android display (${DISPLAY_WIDTH}x${DISPLAY_HEIGHT}). Create it, launch apps, and interact via taps, swipes, and text input. Every action automatically returns a screenshot so you always see the result. Use this to perform tasks in apps on behalf of the user.",
        tools = listOf(
            tool(
                name = "agent_display_create",
                description = "Create the virtual display (${DISPLAY_WIDTH}x${DISPLAY_HEIGHT} @ ${DISPLAY_DPI}dpi). Must be called before any other agent_display tool. Returns a screenshot of the initial state.",
                props = emptyMap(),
            ),
            tool(
                name = "agent_display_destroy",
                description = "Destroy the virtual display and release resources.",
                props = emptyMap(),
            ),
            tool(
                name = "agent_display_launch_app",
                description = "Launch an app by package name on the virtual display. Automatically returns a screenshot after the app starts.",
                props = mapOf(
                    "package_name" to propString("The Android package name, e.g. com.android.settings"),
                ),
                required = listOf("package_name"),
            ),
            tool(
                name = "agent_display_screenshot",
                description = "Capture a screenshot of the virtual display. Returns an image you can see and analyze.",
                props = emptyMap(),
            ),
            tool(
                name = "agent_display_tap",
                description = "Tap at (x, y) coordinates on the virtual display (${DISPLAY_WIDTH}x${DISPLAY_HEIGHT}). Automatically returns a screenshot after the tap.",
                props = mapOf(
                    "x" to propNumber("X coordinate (0-$DISPLAY_WIDTH)"),
                    "y" to propNumber("Y coordinate (0-$DISPLAY_HEIGHT)"),
                ),
                required = listOf("x", "y"),
            ),
            tool(
                name = "agent_display_swipe",
                description = "Swipe from (x1,y1) to (x2,y2) on the virtual display. Automatically returns a screenshot after the swipe. Use duration 500-1000ms for scroll (no inertia), 100-200ms for fling (with momentum).",
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
                name = "agent_display_type_text",
                description = "Type text into the currently focused field on the virtual display. Automatically returns a screenshot after typing.",
                props = mapOf(
                    "text" to propString("The text to type"),
                ),
                required = listOf("text"),
            ),
            tool(
                name = "agent_display_press_back",
                description = "Press the Back button on the virtual display. Automatically returns a screenshot.",
                props = emptyMap(),
            ),
            tool(
                name = "agent_display_press_home",
                description = "Press the Home button on the virtual display. Automatically returns a screenshot.",
                props = emptyMap(),
            ),
            tool(
                name = "agent_display_get_ui_tree",
                description = "Get the accessibility tree (JSON) of the virtual display. Use this to understand the UI structure, find view IDs, and determine what elements are visible.",
                props = emptyMap(),
            ),
            tool(
                name = "agent_display_click_node",
                description = "Click a UI node by its accessibility view ID (e.g. 'com.android.settings:id/search_bar'). Automatically returns a screenshot.",
                props = mapOf(
                    "view_id" to propString("The accessibility view ID of the node to click"),
                ),
                required = listOf("view_id"),
            ),
            tool(
                name = "agent_display_set_node_text",
                description = "Set text on a UI node by its accessibility view ID. Automatically returns a screenshot.",
                props = mapOf(
                    "view_id" to propString("The accessibility view ID of the text field"),
                    "text" to propString("The text to set"),
                ),
                required = listOf("view_id", "text"),
            ),
        ),
    )

    private var service: IAgentDisplayService? = null

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
                "agent_display_create" -> doCreate()
                "agent_display_destroy" -> doDestroy()
                "agent_display_launch_app" -> doLaunchApp(params)
                "agent_display_screenshot" -> captureScreenshot()
                "agent_display_tap" -> doTap(params)
                "agent_display_swipe" -> doSwipe(params)
                "agent_display_type_text" -> doTypeText(params)
                "agent_display_press_back" -> doPressBack()
                "agent_display_press_home" -> doPressHome()
                "agent_display_get_ui_tree" -> doGetUiTree()
                "agent_display_click_node" -> doClickNode(params)
                "agent_display_set_node_text" -> doSetNodeText(params)
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

    // ── Screenshot capture ──────────────────────────────────────────────

    private fun captureScreenshot(): SkillResult {
        Log.d(LTAG, "captureScreenshot: requesting frame from service")
        val frame = getService().captureFrame()
        if (frame == null) {
            Log.w(LTAG, "captureScreenshot: captureFrame returned null (no frame rendered yet)")
            return SkillResult.Error("No frame available — the display may not have rendered content yet.")
        }
        Log.d(LTAG, "captureScreenshot: raw frame JPEG from service = ${frame.size} bytes")

        val bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.size)
        if (bitmap == null) {
            Log.e(LTAG, "captureScreenshot: BitmapFactory.decodeByteArray returned null for ${frame.size} bytes")
            return SkillResult.Error("Failed to decode captured frame.")
        }
        Log.d(LTAG, "captureScreenshot: decoded bitmap ${bitmap.width}x${bitmap.height}, config=${bitmap.config}")

        var quality = 80
        var compressed: ByteArray
        do {
            val out = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
            compressed = out.toByteArray()
            Log.d(LTAG, "captureScreenshot: WebP q=$quality → ${compressed.size} bytes (limit=$MAX_IMAGE_BYTES)")
            if (compressed.size <= MAX_IMAGE_BYTES) break
            quality -= 10
        } while (quality >= MIN_QUALITY)
        bitmap.recycle()

        val base64 = Base64.encodeToString(compressed, Base64.NO_WRAP)
        Log.i(LTAG, "captureScreenshot: FINAL compressed=${compressed.size} bytes, base64Len=${base64.length} chars, quality=$quality")
        return SkillResult.ImageSuccess(
            text = "Screenshot captured (${compressed.size} bytes). The image is attached — analyze it to understand the current display state.",
            base64 = base64,
            mediaType = "image/webp",
        )
    }

    /** Execute an action, wait for the UI to settle, then auto-capture a screenshot. */
    private suspend fun actionWithScreenshot(
        delayMs: Long,
        description: String,
        action: () -> Unit,
    ): SkillResult {
        Log.d(LTAG, "actionWithScreenshot: executing action, then waiting ${delayMs}ms before screenshot")
        action()
        Log.d(LTAG, "actionWithScreenshot: action done, delaying ${delayMs}ms for UI settle")
        delay(delayMs)
        Log.d(LTAG, "actionWithScreenshot: delay done, capturing screenshot")
        val screenshot = captureScreenshot()
        return when (screenshot) {
            is SkillResult.ImageSuccess -> {
                Log.d(LTAG, "actionWithScreenshot: screenshot captured, base64Len=${screenshot.base64.length}")
                SkillResult.ImageSuccess(
                    text = "$description ${screenshot.text}",
                    base64 = screenshot.base64,
                    mediaType = screenshot.mediaType,
                )
            }
            else -> {
                Log.w(LTAG, "actionWithScreenshot: screenshot failed, returning text-only result")
                SkillResult.Success("$description (auto-screenshot unavailable)")
            }
        }
    }

    // ── Tool implementations ────────────────────────────────────────────

    private suspend fun doCreate(): SkillResult {
        val svc = getService()
        svc.createAgentDisplay(DISPLAY_WIDTH, DISPLAY_HEIGHT, DISPLAY_DPI)
        val displayId = svc.displayId
        delay(DELAY_LAUNCH)
        val screenshot = captureScreenshot()
        return when (screenshot) {
            is SkillResult.ImageSuccess -> SkillResult.ImageSuccess(
                text = "Virtual display created (ID: $displayId, ${DISPLAY_WIDTH}x${DISPLAY_HEIGHT} @ ${DISPLAY_DPI}dpi). ${screenshot.text}",
                base64 = screenshot.base64,
                mediaType = screenshot.mediaType,
            )
            else -> SkillResult.Success("Virtual display created (ID: $displayId, ${DISPLAY_WIDTH}x${DISPLAY_HEIGHT} @ ${DISPLAY_DPI}dpi). You can now launch apps and interact with it.")
        }
    }

    private fun doDestroy(): SkillResult {
        getService().destroyAgentDisplay()
        return SkillResult.Success("Virtual display destroyed.")
    }

    private suspend fun doLaunchApp(params: JsonObject): SkillResult {
        val pkg = params["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: package_name")
        return actionWithScreenshot(DELAY_LAUNCH, "Launched $pkg.") {
            getService().launchApp(pkg)
        }
    }

    private suspend fun doTap(params: JsonObject): SkillResult {
        val x = params["x"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: x")
        val y = params["y"]?.jsonPrimitive?.floatOrNull
            ?: return SkillResult.Error("Missing required parameter: y")
        return actionWithScreenshot(DELAY_TAP, "Tapped at ($x, $y).") {
            getService().tap(x, y)
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
        return actionWithScreenshot(DELAY_SWIPE, "Swiped from ($x1,$y1) to ($x2,$y2) over ${duration}ms.") {
            getService().swipe(x1, y1, x2, y2, duration)
        }
    }

    private suspend fun doTypeText(params: JsonObject): SkillResult {
        val text = params["text"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: text")
        return actionWithScreenshot(DELAY_TYPE, "Typed text: \"$text\".") {
            getService().inputText(text)
        }
    }

    private suspend fun doPressBack(): SkillResult {
        return actionWithScreenshot(DELAY_KEY, "Pressed Back.") {
            getService().pressBack()
        }
    }

    private suspend fun doPressHome(): SkillResult {
        return actionWithScreenshot(DELAY_KEY, "Pressed Home.") {
            getService().pressHome()
        }
    }

    private fun doGetUiTree(): SkillResult {
        val tree = getService().accessibilityTree ?: "{}"
        return SkillResult.Success(tree)
    }

    private suspend fun doClickNode(params: JsonObject): SkillResult {
        val viewId = params["view_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: view_id")
        return actionWithScreenshot(DELAY_NODE_CLICK, "Clicked node: $viewId.") {
            getService().clickNode(viewId)
        }
    }

    private suspend fun doSetNodeText(params: JsonObject): SkillResult {
        val viewId = params["view_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: view_id")
        val text = params["text"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: text")
        return actionWithScreenshot(DELAY_NODE_TEXT, "Set text \"$text\" on node: $viewId.") {
            getService().setNodeText(viewId, text)
        }
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
}
