package org.ethereumphone.andyclaw.led

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ethereumphone.terminalsdk.TerminalSDK

/**
 * Abstraction over the dGEN1 3×3 LED matrix via TerminalSDK.
 *
 * Provides both automatic lifecycle-driven patterns (spinner while processing,
 * context-aware completion) and a full public API for direct control by the
 * AI agent via [LedSkill].
 *
 * Gracefully no-ops on non-dGEN1 devices where the TerminalSDK LED subsystem
 * is unavailable. All animations run on a background coroutine scope with
 * thread-safe cancellation.
 *
 * Colors are output in `0xFFRRGGBB` format (with full alpha) because the
 * TerminalSDK passes `0x`-prefixed strings through to the driver unchanged,
 * and the driver expects the alpha byte.
 *
 * @param context Application context used to initialize TerminalSDK.
 * @param maxRgbProvider Lambda returning the user's configured max RGB cap (0–255).
 */
class LedMatrixController(
    context: Context,
    private val maxRgbProvider: () -> Int = { 255 },
) {
    companion object {
        private const val TAG = "LedMatrixController"
        private const val OFF = "0xFF000000"
        private const val SPINNER_FRAME_MS = 120L
        private const val COMPLETION_DISPLAY_MS = 3000L

        /** Named patterns available via the TerminalSDK LED driver. */
        val BUILTIN_PATTERNS = listOf(
            "chad", "plus", "minus", "success", "error", "warning",
            "info", "arrowup", "arrowdown", "swap", "sign",
        )
    }

    private val terminal: TerminalSDK? = try {
        val sdk = TerminalSDK(context)
        Log.i(TAG, "TerminalSDK created: isAvailable=${sdk.isAvailable}, isLedAvailable=${sdk.isLedAvailable}, isDisplayAvailable=${sdk.isDisplayAvailable}")
        if (sdk.isLedAvailable) {
            val led = sdk.led!!
            Log.i(TAG, "TerminalLED available=${led.isAvailable}, systemColor=${led.getSystemColor()}, patterns=${led.getAvailablePatterns()}")
            led.setBrightness(8)
            Log.i(TAG, "Driver brightness set to 8 (max)")
            sdk
        } else {
            Log.w(TAG, "LED subsystem not available on this device")
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "TerminalSDK init failed", e)
        null
    }

    private val led get() = terminal?.led

    val isAvailable: Boolean get() = led != null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var animationJob: Job? = null
    private var completionJob: Job? = null
    private var timedClearJob: Job? = null

    // ═══════════════════════════════════════════════════════════════════════
    //  Agent lifecycle hooks (automatic — driven by ChatViewModel etc.)
    // ═══════════════════════════════════════════════════════════════════════

    fun onPromptStart() {
        if (led == null) return
        Log.i(TAG, "onPromptStart — starting spinner animation, maxBrightness=${maxRgbProvider()}")
        cancelAll()
        animationJob = scope.launch {
            runSpinnerAnimation()
        }
    }

    fun onPromptComplete(responseText: String) {
        if (led == null) return
        cancelAll()
        val intent = LedIntent.classifyResponse(responseText)
        Log.i(TAG, "onPromptComplete — intent=$intent, maxBrightness=${maxRgbProvider()}")
        completionJob = scope.launch {
            showCompletionPattern(intent)
            delay(COMPLETION_DISPLAY_MS)
            led?.clear()
        }
    }

    fun onPromptError() {
        if (led == null) return
        Log.i(TAG, "onPromptError — showing error blink, maxBrightness=${maxRgbProvider()}")
        cancelAll()
        completionJob = scope.launch {
            showErrorBlink()
            delay(COMPLETION_DISPLAY_MS)
            led?.clear()
        }
    }

    fun onUserMessage() {
        if (led == null) return
        Log.d(TAG, "onUserMessage — clearing LEDs")
        cancelAll()
        led?.clear()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Public API for direct AI agent control (via LedSkill tools)
    // ═══════════════════════════════════════════════════════════════════════

    fun displayNamedPattern(name: String, color: String? = null): Boolean {
        val led = led ?: return false
        cancelAll()
        val capped = color?.let { capColor(it) }
        Log.i(TAG, "displayNamedPattern name=$name, inputColor=$color, cappedColor=$capped, maxBrightness=${maxRgbProvider()}")
        led.displayPattern(name, capped)
        return true
    }

    fun flashPattern(name: String, durationMs: Long = 1000L): Boolean {
        val led = led ?: return false
        cancelAll()
        Log.i(TAG, "flashPattern name=$name, durationMs=$durationMs, maxBrightness=${maxRgbProvider()}")
        when (name.lowercase()) {
            "success" -> led.flashSuccess(durationMs = durationMs)
            "error" -> led.flashError(durationMs = durationMs)
            "warning" -> led.flashWarning(durationMs = durationMs)
            "info" -> led.flashInfo(durationMs = durationMs)
            else -> return false
        }
        return true
    }

    fun setCustomPattern(pattern: Array<Array<String>>): Boolean {
        val led = led ?: return false
        cancelAll()
        val capped = Array(3) { r ->
            Array(3) { c -> capColor(pattern[r][c]) }
        }
        Log.i(TAG, "setCustomPattern maxBrightness=${maxRgbProvider()}, row0=${capped[0].joinToString()}, row1=${capped[1].joinToString()}, row2=${capped[2].joinToString()}")
        led.setCustomPattern(capped, 8)
        return true
    }

    fun setSingleLed(row: Int, col: Int, color: String): Boolean {
        val led = led ?: return false
        val capped = capColor(color)
        Log.i(TAG, "setSingleLed [$row,$col] input=$color, capped=$capped, maxBrightness=${maxRgbProvider()}")
        led.setColor(row, col, capped, 8)
        return true
    }

    /**
     * Set all 9 LEDs to the same color.
     *
     * @param color Hex color string.
     * @param durationMs If > 0, automatically clear after this many milliseconds.
     *                   The clear happens asynchronously and is cancelled by any
     *                   subsequent LED command or user message.
     * @return true if successful.
     */
    fun setAllLeds(color: String, durationMs: Long = 0L): Boolean {
        val led = led ?: return false
        cancelAll()
        val capped = capColor(color)
        Log.i(TAG, "setAllLeds input=$color, capped=$capped, durationMs=$durationMs, maxBrightness=${maxRgbProvider()}")
        led.setAllColor(capped, 8)
        if (durationMs > 0) {
            timedClearJob = scope.launch {
                delay(durationMs)
                Log.d(TAG, "setAllLeds timed clear after ${durationMs}ms")
                led.clear()
            }
        }
        return true
    }

    fun runCustomAnimation(
        frames: List<Array<Array<String>>>,
        intervalMs: Long = 150L,
        loops: Int = 1,
    ): Boolean {
        if (led == null || frames.isEmpty()) return false
        cancelAll()

        val cappedFrames = frames.map { frame ->
            Array(3) { r -> Array(3) { c -> capColor(frame[r][c]) } }
        }
        Log.i(TAG, "runCustomAnimation frames=${cappedFrames.size}, intervalMs=$intervalMs, loops=$loops, maxBrightness=${maxRgbProvider()}")
        for ((i, frame) in cappedFrames.withIndex()) {
            Log.d(TAG, "  frame[$i]: ${frame.joinToString(" | ") { it.joinToString() }}")
        }

        animationJob = scope.launch {
            var cycle = 0
            while (loops == 0 || cycle < loops) {
                for (frame in cappedFrames) {
                    led?.setCustomPattern(frame, 8)
                    delay(intervalMs)
                }
                cycle++
            }
        }
        return true
    }

    fun clear(): Boolean {
        val led = led ?: return false
        Log.d(TAG, "clear — turning off all LEDs")
        cancelAll()
        led.clear()
        return true
    }

    fun getAvailablePatterns(): List<String> = BUILTIN_PATTERNS

    fun destroy() {
        cancelAll()
        terminal?.destroy()
        scope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Internal animations (used by lifecycle hooks)
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun runSpinnerAnimation() {
        val positions = listOf(
            0 to 0, 0 to 1, 0 to 2,
            1 to 2,
            2 to 2, 2 to 1, 2 to 0,
            1 to 0,
        )
        val color = capColor("#FFFFFF")
        val trailColor = capColor("#8080FF")
        var index = 0

        while (true) {
            val pattern = Array(3) { Array(3) { OFF } }
            val curr = positions[index % positions.size]
            val prev = positions[(index - 1 + positions.size) % positions.size]
            pattern[curr.first][curr.second] = color
            pattern[prev.first][prev.second] = trailColor
            led?.setCustomPattern(pattern, 8)
            delay(SPINNER_FRAME_MS)
            index++
        }
    }

    private fun showCompletionPattern(intent: LedIntent) {
        val led = led ?: return
        when (intent) {
            LedIntent.GREETING -> showSmilePattern()
            LedIntent.SUCCESS -> led.flashSuccess()
            LedIntent.ERROR -> led.flashError()
            LedIntent.PROCESSING -> led.displayInfo()
            LedIntent.IDLE -> led.setAllColor(capColor("#00FF00"), 8)
        }
    }

    private fun showSmilePattern() {
        val c = capColor("#FFFF00")
        led?.setCustomPattern(arrayOf(
            arrayOf(c,   OFF, c),
            arrayOf(OFF, OFF, OFF),
            arrayOf(c,   c,   c),
        ), 8)
    }

    private suspend fun showErrorBlink() {
        val red = capColor("#FF0000")
        val cornerPattern = arrayOf(
            arrayOf(red, OFF, red),
            arrayOf(OFF, OFF, OFF),
            arrayOf(red, OFF, red),
        )
        repeat(3) {
            led?.setCustomPattern(cornerPattern, 8)
            delay(250)
            led?.clear()
            delay(200)
        }
        led?.setCustomPattern(cornerPattern, 8)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Color utilities
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Apply the user's max RGB cap and output in `0xFFRRGGBB` format.
     *
     * The `0xFF` alpha prefix is critical: the TerminalSDK's `normalizeColor`
     * passes `0x`-prefixed strings through to the driver unchanged. The WalletManager
     * uses `0xFFRRGGBB` format and the driver expects the alpha byte — without it
     * the LEDs appear very dim or off.
     */
    internal fun capColor(hex: String): String {
        val clean = hex.removePrefix("#").removePrefix("0x")
        val offset = if (clean.length == 8) 2 else 0
        val r = clean.substring(offset, offset + 2).toInt(16)
        val g = clean.substring(offset + 2, offset + 4).toInt(16)
        val b = clean.substring(offset + 4, offset + 6).toInt(16)

        val maxRgb = maxRgbProvider().coerceIn(0, 255)
        val cr: Int
        val cg: Int
        val cb: Int
        if (maxRgb >= 255) {
            cr = r; cg = g; cb = b
        } else {
            val scale = maxRgb / 255.0
            cr = (r * scale).toInt().coerceIn(0, 255)
            cg = (g * scale).toInt().coerceIn(0, 255)
            cb = (b * scale).toInt().coerceIn(0, 255)
        }

        val result = "0xFF%02X%02X%02X".format(cr, cg, cb)
        Log.d(TAG, "capColor $hex → $result (rgb $r,$g,$b → $cr,$cg,$cb, maxBrightness=$maxRgb)")
        return result
    }

    private fun cancelAll() {
        animationJob?.cancel()
        animationJob = null
        completionJob?.cancel()
        completionJob = null
        timedClearJob?.cancel()
        timedClearJob = null
    }
}
