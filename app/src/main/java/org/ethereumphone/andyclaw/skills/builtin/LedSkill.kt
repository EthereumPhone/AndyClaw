package org.ethereumphone.andyclaw.skills.builtin

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.ethereumphone.andyclaw.led.LedMatrixController
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition

/**
 * Built-in skill that gives the AI agent full control over the dGEN1 3×3 LED matrix.
 *
 * Supports:
 * - Displaying built-in named patterns (success, error, chad, etc.)
 * - Flashing status patterns briefly
 * - Creating custom static 3×3 color patterns
 * - Running custom multi-frame animations
 * - Setting individual LEDs or all LEDs to a color
 * - Clearing the matrix
 *
 * All operations gracefully return an error message on non-dGEN1 devices.
 * The user's brightness cap is applied automatically by [LedMatrixController].
 */
class LedSkill(
    private val controller: LedMatrixController,
) : AndyClawSkill {

    companion object {
        private const val TAG = "LedSkill"
    }

    override val id = "led_matrix"
    override val name = "LED Matrix"

    override val baseManifest = SkillManifest(
        description = buildString {
            append("Control the 3×3 LED matrix on the back of the dGEN1 device. ")
            append("Display built-in patterns, create custom static patterns, ")
            append("run multi-frame animations, or control individual LEDs. ")
            append("The grid is 3 rows × 3 columns (indices 0–2). ")
            append("Colors are hex strings like \"#FF0000\" (red), \"#00FF00\" (green), \"#000000\" (off). ")
            append("Use vivid, full-brightness colors (e.g. \"#FF0000\" not \"#400000\") — ")
            append("brightness is controlled by the hardware automatically based on the user's preference. ")
            append("Only available on dGEN1 hardware running ethOS.")
        },
        tools = listOf(
            ToolDefinition(
                name = "led_display_pattern",
                description = buildString {
                    append("Display a built-in LED pattern by name. ")
                    append("Available patterns: chad, plus, minus, success, error, warning, info, arrowup, arrowdown, swap, sign. ")
                    append("Optionally override the color (otherwise uses system accent color).")
                },
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("pattern") {
                            put("type", "string")
                            put("description", "Pattern name (e.g. 'success', 'chad', 'error', 'info')")
                            putJsonArray("enum") {
                                for (p in LedMatrixController.BUILTIN_PATTERNS) add(JsonPrimitive(p))
                            }
                        }
                        putJsonObject("color") {
                            put("type", "string")
                            put("description", "Optional hex color override (e.g. '#FF00FF'). Uses system accent if omitted.")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("pattern")) }
                },
            ),

            ToolDefinition(
                name = "led_flash_pattern",
                description = buildString {
                    append("Flash a status pattern briefly then revert to the default chad branding pattern. ")
                    append("Available: success, error, warning, info. ")
                    append("Good for confirming an action completed.")
                },
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("pattern") {
                            put("type", "string")
                            put("description", "Status pattern to flash: success, error, warning, or info")
                            putJsonArray("enum") {
                                add(JsonPrimitive("success"))
                                add(JsonPrimitive("error"))
                                add(JsonPrimitive("warning"))
                                add(JsonPrimitive("info"))
                            }
                        }
                        putJsonObject("duration_ms") {
                            put("type", "integer")
                            put("description", "How long to show the flash in milliseconds (default 1000)")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("pattern")) }
                },
            ),

            ToolDefinition(
                name = "led_set_custom_pattern",
                description = buildString {
                    append("Set a custom static 3×3 LED pattern. ")
                    append("Provide a 3-element array where each element is a 3-element array of hex color strings. ")
                    append("Use '#000000' for off. Example: a diagonal line would be ")
                    append("[[\"#FF0000\",\"#000000\",\"#000000\"],[\"#000000\",\"#FF0000\",\"#000000\"],[\"#000000\",\"#000000\",\"#FF0000\"]]")
                },
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("pattern") {
                            put("type", "array")
                            put("description", "3×3 grid of hex color strings. Outer array = rows, inner array = columns.")
                            putJsonObject("items") {
                                put("type", "array")
                                putJsonObject("items") {
                                    put("type", "string")
                                }
                                put("minItems", 3)
                                put("maxItems", 3)
                            }
                            put("minItems", 3)
                            put("maxItems", 3)
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("pattern")) }
                },
            ),

            ToolDefinition(
                name = "led_animate",
                description = buildString {
                    append("Run a custom animation on the 3×3 LED matrix. ")
                    append("Provide an array of frames, where each frame is a 3×3 grid of hex color strings. ")
                    append("Frames are played sequentially with interval_ms delay between them. ")
                    append("Set loops to 0 for infinite looping (animation runs until the next LED command). ")
                    append("Example: a 2-frame blink would have one frame all-red and one frame all-off.")
                },
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("frames") {
                            put("type", "array")
                            put("description", "Array of frames. Each frame is a 3×3 array of hex color strings.")
                            putJsonObject("items") {
                                put("type", "array")
                                putJsonObject("items") {
                                    put("type", "array")
                                    putJsonObject("items") {
                                        put("type", "string")
                                    }
                                    put("minItems", 3)
                                    put("maxItems", 3)
                                }
                                put("minItems", 3)
                                put("maxItems", 3)
                            }
                        }
                        putJsonObject("interval_ms") {
                            put("type", "integer")
                            put("description", "Delay between frames in milliseconds (default 150)")
                        }
                        putJsonObject("loops") {
                            put("type", "integer")
                            put("description", "Number of times to play the full animation. 0 = loop forever until next LED command (default 1)")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("frames")) }
                },
            ),

            ToolDefinition(
                name = "led_set_led",
                description = "Set a single LED in the 3×3 grid to a specific color.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("row") {
                            put("type", "integer")
                            put("description", "Row index (0 = top, 2 = bottom)")
                            put("minimum", 0)
                            put("maximum", 2)
                        }
                        putJsonObject("col") {
                            put("type", "integer")
                            put("description", "Column index (0 = left, 2 = right)")
                            put("minimum", 0)
                            put("maximum", 2)
                        }
                        putJsonObject("color") {
                            put("type", "string")
                            put("description", "Hex color string (e.g. '#FF0000' for red)")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("row"))
                        add(JsonPrimitive("col"))
                        add(JsonPrimitive("color"))
                    }
                },
            ),

            ToolDefinition(
                name = "led_set_all",
                description = buildString {
                    append("Set all 9 LEDs to the same color. ")
                    append("Optionally set duration_ms to automatically clear after that many milliseconds. ")
                    append("This returns immediately — the timer runs in the background. ")
                    append("Use this for timed displays like 'show red for 10 seconds' (duration_ms=10000).")
                },
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("color") {
                            put("type", "string")
                            put("description", "Hex color string (e.g. '#0000FF' for blue, '#000000' for off)")
                        }
                        putJsonObject("duration_ms") {
                            put("type", "integer")
                            put("description", "Auto-clear after this many milliseconds. Omit or 0 for indefinite (stays on until next command).")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("color")) }
                },
            ),

            ToolDefinition(
                name = "led_clear",
                description = "Turn off all LEDs and stop any running animation.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                },
            ),

            ToolDefinition(
                name = "led_list_patterns",
                description = "List all available built-in LED pattern names that can be used with led_display_pattern and led_flash_pattern.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                },
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        if (!controller.isAvailable) {
            return SkillResult.Error(
                "LED matrix is not available. This feature requires a dGEN1 device running ethOS."
            )
        }
        Log.d(TAG, "Executing tool: $tool")
        return when (tool) {
            "led_display_pattern" -> executeDisplayPattern(params)
            "led_flash_pattern" -> executeFlashPattern(params)
            "led_set_custom_pattern" -> executeSetCustomPattern(params)
            "led_animate" -> executeAnimate(params)
            "led_set_led" -> executeSetLed(params)
            "led_set_all" -> executeSetAll(params)
            "led_clear" -> executeClear()
            "led_list_patterns" -> executeListPatterns()
            else -> SkillResult.Error("Unknown LED tool: $tool")
        }
    }

    // ── led_display_pattern ─────────────────────────────────────────────

    private fun executeDisplayPattern(params: JsonObject): SkillResult {
        val pattern = params["pattern"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: pattern")
        val color = params["color"]?.jsonPrimitive?.content

        if (pattern !in LedMatrixController.BUILTIN_PATTERNS) {
            return SkillResult.Error(
                "Unknown pattern '$pattern'. Available: ${LedMatrixController.BUILTIN_PATTERNS.joinToString()}"
            )
        }

        return if (controller.displayNamedPattern(pattern, color)) {
            SkillResult.Success("Displaying '$pattern' pattern on the LED matrix.")
        } else {
            SkillResult.Error("Failed to display pattern.")
        }
    }

    // ── led_flash_pattern ───────────────────────────────────────────────

    private fun executeFlashPattern(params: JsonObject): SkillResult {
        val pattern = params["pattern"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: pattern")
        val durationMs = params["duration_ms"]?.jsonPrimitive?.longOrNull ?: 1000L

        return if (controller.flashPattern(pattern, durationMs)) {
            SkillResult.Success("Flashing '$pattern' for ${durationMs}ms, then reverting to default.")
        } else {
            SkillResult.Error("Unknown flash pattern '$pattern'. Available: success, error, warning, info.")
        }
    }

    // ── led_set_custom_pattern ──────────────────────────────────────────

    private fun executeSetCustomPattern(params: JsonObject): SkillResult {
        val patternJson = params["pattern"] as? JsonArray
            ?: return SkillResult.Error("Missing required parameter: pattern (expected 3×3 array)")

        val grid = parseGrid(patternJson)
            ?: return SkillResult.Error("Invalid pattern: expected a 3×3 array of hex color strings.")

        return if (controller.setCustomPattern(grid)) {
            SkillResult.Success("Custom 3×3 pattern set on the LED matrix.")
        } else {
            SkillResult.Error("Failed to set custom pattern.")
        }
    }

    // ── led_animate ─────────────────────────────────────────────────────

    private fun executeAnimate(params: JsonObject): SkillResult {
        val framesJson = params["frames"] as? JsonArray
            ?: return SkillResult.Error("Missing required parameter: frames")
        val intervalMs = params["interval_ms"]?.jsonPrimitive?.longOrNull ?: 150L
        val loops = params["loops"]?.jsonPrimitive?.intOrNull ?: 1

        if (framesJson.isEmpty()) {
            return SkillResult.Error("frames array must not be empty.")
        }

        val frames = mutableListOf<Array<Array<String>>>()
        for ((i, frameElement) in framesJson.withIndex()) {
            val frameArray = frameElement as? JsonArray
                ?: return SkillResult.Error("Frame $i is not an array.")
            val grid = parseGrid(frameArray)
                ?: return SkillResult.Error("Frame $i is not a valid 3×3 color grid.")
            frames.add(grid)
        }

        return if (controller.runCustomAnimation(frames, intervalMs, loops)) {
            val loopDesc = if (loops == 0) "looping forever" else "$loops loop(s)"
            SkillResult.Success(
                "Animation started: ${frames.size} frame(s), ${intervalMs}ms interval, $loopDesc."
            )
        } else {
            SkillResult.Error("Failed to start animation.")
        }
    }

    // ── led_set_led ─────────────────────────────────────────────────────

    private fun executeSetLed(params: JsonObject): SkillResult {
        val row = params["row"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: row")
        val col = params["col"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: col")
        val color = params["color"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: color")

        if (row !in 0..2 || col !in 0..2) {
            return SkillResult.Error("row and col must be 0–2. Got row=$row, col=$col.")
        }

        return if (controller.setSingleLed(row, col, color)) {
            SkillResult.Success("LED [$row,$col] set to $color.")
        } else {
            SkillResult.Error("Failed to set LED.")
        }
    }

    // ── led_set_all ─────────────────────────────────────────────────────

    private fun executeSetAll(params: JsonObject): SkillResult {
        val color = params["color"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: color")
        val durationMs = params["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L

        return if (controller.setAllLeds(color, durationMs)) {
            if (durationMs > 0) {
                SkillResult.Success("All LEDs set to $color — will auto-clear after ${durationMs}ms.")
            } else {
                SkillResult.Success("All LEDs set to $color.")
            }
        } else {
            SkillResult.Error("Failed to set LEDs.")
        }
    }

    // ── led_clear ───────────────────────────────────────────────────────

    private fun executeClear(): SkillResult {
        return if (controller.clear()) {
            SkillResult.Success("All LEDs cleared and animations stopped.")
        } else {
            SkillResult.Error("Failed to clear LEDs.")
        }
    }

    // ── led_list_patterns ───────────────────────────────────────────────

    private fun executeListPatterns(): SkillResult {
        val patterns = controller.getAvailablePatterns()
        val sb = StringBuilder("## Available LED Patterns\n\n")
        sb.appendLine("The following built-in patterns can be used with `led_display_pattern`:\n")
        for (p in patterns) {
            val desc = when (p) {
                "chad" -> "ethOS branding logo"
                "plus" -> "+ symbol"
                "minus" -> "− symbol"
                "success" -> "green checkmark"
                "error" -> "red cross"
                "warning" -> "yellow warning"
                "info" -> "info indicator"
                "arrowup" -> "arrow pointing up (send)"
                "arrowdown" -> "arrow pointing down (receive)"
                "swap" -> "swap indicator"
                "sign" -> "signing indicator"
                else -> ""
            }
            sb.appendLine("- **$p** — $desc")
        }
        sb.appendLine()
        sb.appendLine("Flash variants (success, error, warning, info) are available via `led_flash_pattern`.")
        sb.appendLine()
        sb.appendLine("You can also create fully custom patterns with `led_set_custom_pattern` and custom animations with `led_animate`.")
        return SkillResult.Success(sb.toString().trimEnd())
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Parse a JsonArray into a 3×3 String grid, or null if the shape is wrong.
     */
    private fun parseGrid(arr: JsonArray): Array<Array<String>>? {
        if (arr.size != 3) return null
        return try {
            Array(3) { r ->
                val row = arr[r].jsonArray
                if (row.size != 3) return null
                Array(3) { c -> row[c].jsonPrimitive.content }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse 3×3 grid: ${e.message}")
            null
        }
    }
}
