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
            append("Control the dGEN1 3×3 LED matrix (indices 0–2). ")
            append("Colors are hex strings (e.g. \"#FF0000\"). ")
            append("Use full-brightness colors — hardware auto-adjusts brightness. ")
            append("Only available on dGEN1 running ethOS.")
        },
        tools = listOf(
            ToolDefinition(
                name = "led_display_pattern",
                description = "Display a built-in LED pattern by name, optionally overriding the color.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("pattern") {
                            put("type", "string")
                            putJsonArray("enum") {
                                for (p in LedMatrixController.BUILTIN_PATTERNS) add(JsonPrimitive(p))
                            }
                        }
                        putJsonObject("color") {
                            put("type", "string")
                            put("description", "Hex color override (e.g. '#FF00FF'), uses system accent if omitted")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("pattern")) }
                },
            ),

            ToolDefinition(
                name = "led_flash_pattern",
                description = "Flash a status pattern briefly then revert to default chad pattern.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("pattern") {
                            put("type", "string")
                            putJsonArray("enum") {
                                add(JsonPrimitive("success"))
                                add(JsonPrimitive("error"))
                                add(JsonPrimitive("warning"))
                                add(JsonPrimitive("info"))
                            }
                        }
                        putJsonObject("duration_ms") {
                            put("type", "integer")
                            put("description", "Flash duration in ms (default 1000)")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("pattern")) }
                },
            ),

            ToolDefinition(
                name = "led_set_custom_pattern",
                description = "Set a custom static 3×3 LED pattern from a grid of hex color strings ('#000000' = off).",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("pattern") {
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
                    putJsonArray("required") { add(JsonPrimitive("pattern")) }
                },
            ),

            ToolDefinition(
                name = "led_animate",
                description = "Run a custom animation from an array of 3×3 hex color frames. Set loops=0 for infinite.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("frames") {
                            put("type", "array")
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
                            put("description", "Delay between frames in ms (default 150)")
                        }
                        putJsonObject("loops") {
                            put("type", "integer")
                            put("description", "Loop count; 0 = infinite (default 1)")
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
                            put("description", "Row index (0=top, 2=bottom)")
                            put("minimum", 0)
                            put("maximum", 2)
                        }
                        putJsonObject("col") {
                            put("type", "integer")
                            put("description", "Column index (0=left, 2=right)")
                            put("minimum", 0)
                            put("maximum", 2)
                        }
                        putJsonObject("color") {
                            put("type", "string")
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
                description = "Set all 9 LEDs to one color, optionally auto-clearing after duration_ms.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("color") {
                            put("type", "string")
                        }
                        putJsonObject("duration_ms") {
                            put("type", "integer")
                            put("description", "Auto-clear after this many ms (0 or omit = indefinite)")
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
                description = "List all available built-in LED pattern names.",
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
