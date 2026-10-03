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
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayLease
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
 * Colors are output in `#RRGGBB` format. The SDK's `normalizeColor` converts
 * this to the `0xRRGGBB` format expected by the hardware driver. Brightness is
 * controlled via the SDK's hardware brightness parameter (0–8), mapped from the
 * user's max RGB preference (0–255) by [hwBrightness].
 *
 * @param context Application context used to initialize TerminalSDK.
 * @param maxRgbProvider Lambda returning the user's configured max RGB preference (0–255),
 *                       mapped to hardware brightness levels 0–8.
 */
class LedMatrixController(
    context: Context,
    private val maxRgbProvider: () -> Int = { 255 },
) {
    companion object {
        private const val TAG = "LedMatrixController"
        private const val OFF = "#000000"
        private const val SPINNER_FRAME_MS = 120L
        private const val COMPLETION_DISPLAY_MS = 3000L
        private const val TERMINAL_COMPLETION_MS = 10_000L
        private const val RESUME_DELAY_MS = 1000L

        /** Named patterns available via the TerminalSDK LED driver. */
        val BUILTIN_PATTERNS = listOf(
            "chad", "plus", "minus", "success", "error", "warning",
            "info", "arrowup", "arrowdown", "swap", "sign",
        )

        private val THINKING_EMOTICONS = listOf(
            "〈ᇂ_ᇂ |||〉",
            "{ @'ꈊ'@ }",
            "( ≖_≖)",
        )

        private val COMPLETION_EMOTICONS = listOf(
            "(ง •̀_•́)ง",
            "\\(^ω^)/",
            "\\( ﾟヮﾟ)/",
            "(ﾉ^_^)ﾉ",
        )

        private val ERROR_EMOTICONS = listOf(
            "(╥﹏╥)",
            "(；一_一)",
            "(╯°□°）╯︵ ┻━┻",
            "(o_O)",
            "(￣□￣；)",
        )
    }

    private val terminal: TerminalSDK? = try {
        val sdk = TerminalSDK(context)
        Log.i(TAG, "TerminalSDK created: isAvailable=${sdk.isAvailable}, isLedAvailable=${sdk.isLedAvailable}, isDisplayAvailable=${sdk.isDisplayAvailable}")
        if (sdk.isLedAvailable) {
            val led = sdk.led!!
            Log.i(TAG, "TerminalLED available=${led.isAvailable}, systemColor=${led.getSystemColor()}, patterns=${led.getAvailablePatterns()}")
            val hw = hwBrightness()
            led.setBrightness(hw)
            Log.i(TAG, "Driver brightness set to $hw (from maxRgb=${maxRgbProvider()})")
        }
        if (sdk.isLedAvailable || sdk.isDisplayAvailable) sdk else {
            Log.w(TAG, "Neither LED nor display subsystem available on this device")
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "TerminalSDK init failed: ${e.javaClass.simpleName}: ${e.message}", e)
        null
    }

    init {
        Log.i(TAG, "LedMatrixController init complete: terminal=${terminal != null}, " +
            "led=${terminal?.led != null}, isLedAvailable=${terminal?.isLedAvailable}, " +
            "isDisplayAvailable=${terminal?.isDisplayAvailable}")
    }

    private val led get() = terminal?.led

    val isAvailable: Boolean get() = led != null

    val isDisplayAvailable: Boolean get() = terminal?.isDisplayAvailable == true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var animationJob: Job? = null
    private var completionJob: Job? = null
    private var timedClearJob: Job? = null
    // Written from the lease listener's thread too (yieldTerminal).
    @Volatile private var terminalFlushJob: Job? = null

    /**
     * Whether one of our emoticons may be on the terminal screen. It is drawn as a PERSISTENT
     * frame, which holds the status-bar slot the agent HUD draws in and arms STOP from — and turns
     * the panel on — for as long as it stays. So it is taken down as soon as a run takes the agent
     * display ([yieldTerminal]), and never drawn while one holds it.
     */
    @Volatile
    private var emoticonShown = false

    /**
     * Set to `true` when the AI agent explicitly controls the LEDs via [LedSkill].
     * While active, [onPromptComplete] and [onPromptError] will skip the automatic
     * completion pattern so the agent's pattern/animation persists until the next
     * user message or prompt start.
     */
    @Volatile
    private var aiControlled = false

    /** Maps the user's 0–255 max RGB preference to the SDK's 0–8 hardware brightness range. */
    private fun hwBrightness(): Int {
        val maxRgb = maxRgbProvider().coerceIn(0, 255)
        return ((maxRgb * 8.0) / 255.0 + 0.5).toInt().coerceIn(0, 8)
    }

    /** Syncs the global hardware brightness before built-in pattern/flash operations. */
    private fun syncBrightness() {
        led?.setBrightness(hwBrightness())
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Agent lifecycle hooks (automatic — driven by ChatViewModel etc.)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * [terminal] false for a run nobody is looking at — the heartbeat, a reminder, a cron job, an
     * XMTP or Telegram message: its emoticons took the terminal screen for the whole run, so the
     * agent HUD never showed and STOP was not armed, and a 3 AM heartbeat lit the panel. Such a run
     * drives the LEDs only, and leaves the terminal screen to the chat that may be using it.
     */
    fun onPromptStart(terminal: Boolean = true) {
        if (led == null && !isDisplayAvailable) return
        aiControlled = false
        Log.i(TAG, "onPromptStart — starting spinner, hwBrightness=${hwBrightness()}, maxRgb=${maxRgbProvider()}")
        if (terminal) cancelAll() else cancelLedJobs()
        if (led != null) {
            syncBrightness()
            animationJob = scope.launch {
                runSpinnerAnimation()
            }
        }
        if (terminal && isDisplayAvailable) {
            terminalFlushJob = scope.launch {
                drawEmoticon(THINKING_EMOTICONS.random())
            }
        }
    }

    fun onPromptComplete(responseText: String, terminal: Boolean = true) {
        if (led == null && !isDisplayAvailable) return
        if (aiControlled) {
            Log.i(TAG, "onPromptComplete — skipping LED completion pattern (AI-controlled)")
        } else {
            if (terminal) cancelAll() else cancelLedJobs()
            val intent = LedIntent.classifyResponse(responseText)
            Log.i(TAG, "onPromptComplete — intent=$intent, hwBrightness=${hwBrightness()}, maxRgb=${maxRgbProvider()}")
            completionJob = scope.launch {
                if (led != null) {
                    syncBrightness()
                    showCompletionPattern(intent)
                }
                delay(COMPLETION_DISPLAY_MS)
                if (led != null) showChadPattern()
            }
        }
        // Show completion emoticon on terminal, then flush it. Drawn and flushed by one job: as two,
        // the draw cancelled the flush whenever it started after it, and the frame stayed.
        if (terminal && isDisplayAvailable) {
            terminalFlushJob?.cancel()
            terminalFlushJob = scope.launch {
                drawEmoticon(COMPLETION_EMOTICONS.random())
                if (emoticonShown) {
                    delay(TERMINAL_COMPLETION_MS)
                    flushTerminalDisplay()
                }
            }
        }
    }

    fun onPromptError(terminal: Boolean = true) {
        if (led == null && !isDisplayAvailable) return
        if (aiControlled) {
            Log.i(TAG, "onPromptError — skipping LED error pattern (AI-controlled)")
        } else {
            Log.i(TAG, "onPromptError — showing error blink, hwBrightness=${hwBrightness()}, maxRgb=${maxRgbProvider()}")
            if (terminal) cancelAll() else cancelLedJobs()
            completionJob = scope.launch {
                if (led != null) {
                    syncBrightness()
                    showErrorBlink()
                }
                delay(COMPLETION_DISPLAY_MS)
                if (led != null) showChadPattern()
            }
        }
        // Show error emoticon on terminal, then flush it (one job, as in onPromptComplete).
        if (terminal && isDisplayAvailable) {
            terminalFlushJob?.cancel()
            terminalFlushJob = scope.launch {
                drawEmoticon(ERROR_EMOTICONS.random())
                if (emoticonShown) {
                    delay(TERMINAL_COMPLETION_MS)
                    flushTerminalDisplay()
                }
            }
        }
    }

    /**
     * The run was cancelled — the user's Cancel, a newer prompt, a background run's time cap — so
     * neither [onPromptComplete] nor [onPromptError] comes: stop the spinner, which otherwise
     * looped forever (binder calls to the LED HAL eight times a second), and with [terminal] take
     * our emoticon off the terminal screen.
     */
    fun onPromptCancelled(terminal: Boolean = true) {
        if (led == null && !isDisplayAvailable) return
        Log.i(TAG, "onPromptCancelled — stopping the spinner")
        if (!aiControlled) {
            cancelLedJobs()
            led?.clear()
        }
        if (terminal && isDisplayAvailable) {
            terminalFlushJob?.cancel()
            terminalFlushJob = scope.launch {
                if (emoticonShown) flushTerminalDisplay()
            }
        }
    }

    /**
     * A run took the agent display: its HUD and STOP need the status-bar slot an emoticon of ours
     * holds, so the emoticon goes now rather than when the chat's turn ends.
     */
    fun yieldTerminal() {
        if (!emoticonShown) return
        terminalFlushJob?.cancel()
        terminalFlushJob = scope.launch { flushTerminalDisplay() }
    }

    fun onUserMessage() {
        aiControlled = false
        if (led == null && !isDisplayAvailable) return
        Log.d(TAG, "onUserMessage — clearing LEDs")
        // LEDs only: a pending flush of the last turn's emoticon still runs, and the next prompt
        // replaces it anyway.
        cancelLedJobs()
        led?.clear()
    }

    /**
     * Show the Chad face on resume / app open.
     *
     * Waits [RESUME_DELAY_MS] before drawing so the LED driver has time to
     * be released by other apps (e.g. after screen-off or task switch).
     */
    fun showOpeningPattern(color: String? = null) {
        if (led == null) return
        cancelLedJobs()
        completionJob = scope.launch {
            delay(RESUME_DELAY_MS)
            syncBrightness()
            val c = normalizeColor(color ?: led?.getSystemColor() ?: "#FFFFFF")
            val hw = hwBrightness()
            led?.setCustomPattern(arrayOf(
                arrayOf(c,   OFF, c),
                arrayOf(OFF, c,   OFF),
                arrayOf(c,   c,   c),
            ), hw)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Public API for direct AI agent control (via LedSkill tools)
    // ═══════════════════════════════════════════════════════════════════════

    fun displayNamedPattern(name: String, color: String? = null): Boolean {
        val led = led ?: return false
        cancelLedJobs()
        aiControlled = true
        syncBrightness()
        val normalized = color?.let { normalizeColor(it) }
        Log.i(TAG, "displayNamedPattern name=$name, color=$normalized, hwBrightness=${hwBrightness()}")
        led.displayPattern(name, normalized)
        return true
    }

    fun flashPattern(name: String, durationMs: Long = 1000L): Boolean {
        val led = led ?: return false
        cancelLedJobs()
        aiControlled = true
        syncBrightness()
        Log.i(TAG, "flashPattern name=$name, durationMs=$durationMs, hwBrightness=${hwBrightness()}")
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
        cancelLedJobs()
        aiControlled = true
        val hw = hwBrightness()
        val normalized = Array(3) { r ->
            Array(3) { c -> normalizeColor(pattern[r][c]) }
        }
        Log.i(TAG, "setCustomPattern hwBrightness=$hw, row0=${normalized[0].joinToString()}, row1=${normalized[1].joinToString()}, row2=${normalized[2].joinToString()}")
        led.setCustomPattern(normalized, hw)
        return true
    }

    fun setSingleLed(row: Int, col: Int, color: String): Boolean {
        val led = led ?: return false
        aiControlled = true
        val hw = hwBrightness()
        val normalized = normalizeColor(color)
        Log.i(TAG, "setSingleLed [$row,$col] color=$normalized, hwBrightness=$hw")
        led.setColor(row, col, normalized, hw)
        return true
    }

    /**
     * Set all 9 LEDs to the same colour.
     *
     * @param color      Hex colour string (e.g. `"#FF0000"`).
     * @param durationMs If > 0, automatically clear after this many milliseconds.
     *                   The clear happens asynchronously and is cancelled by any
     *                   subsequent LED command or user message.
     * @return true if successful.
     */
    fun setAllLeds(color: String, durationMs: Long = 0L): Boolean {
        val led = led ?: return false
        cancelLedJobs()
        aiControlled = true
        val hw = hwBrightness()
        val normalized = normalizeColor(color)
        Log.i(TAG, "setAllLeds color=$normalized, durationMs=$durationMs, hwBrightness=$hw")
        led.setAllColor(normalized, hw)
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
        cancelLedJobs()
        aiControlled = true

        val normalizedFrames = frames.map { frame ->
            Array(3) { r -> Array(3) { c -> normalizeColor(frame[r][c]) } }
        }
        Log.i(TAG, "runCustomAnimation frames=${normalizedFrames.size}, intervalMs=$intervalMs, loops=$loops, hwBrightness=${hwBrightness()}")
        for ((i, frame) in normalizedFrames.withIndex()) {
            Log.d(TAG, "  frame[$i]: ${frame.joinToString(" | ") { it.joinToString() }}")
        }

        animationJob = scope.launch {
            var cycle = 0
            val hw = hwBrightness()
            while (loops == 0 || cycle < loops) {
                for (frame in normalizedFrames) {
                    led?.setCustomPattern(frame, hw)
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
        cancelLedJobs()
        led.clear()
        return true
    }

    fun getAvailablePatterns(): List<String> = BUILTIN_PATTERNS

    // ═══════════════════════════════════════════════════════════════════════
    //  Terminal display text (mini back-screen on dGEN1)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Display text (typically an emoticon) on the dGEN1 terminal status bar.
     *
     * The display is 428×142 pixels. Short emoticons and symbols render best.
     * Uses the TerminalSDK `showText` method which renders white text on black.
     * The emoticon persists on screen until replaced by another call or flushed.
     *
     * @return true if the text was displayed successfully.
     */
    suspend fun setTerminalText(text: String): Boolean {
        val sdk = terminal ?: return false
        if (!sdk.isDisplayAvailable) return false
        terminalFlushJob?.cancel()
        terminalFlushJob = null
        val resolved = Emoticons.resolve(text)
        return try {
            emoticonShown = true
            sdk.showText(resolved)
            Log.i(TAG, "setTerminalText: '$text' → '$resolved'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "setTerminalText failed", e)
            false
        }
    }

    /**
     * A tool's emoticon (a web search, a fetch) in place of the one the run is showing. Only in
     * place of one: a headless or home-screen run shows none, and a tool must not take the
     * terminal screen for itself — nothing would ever flush it.
     */
    suspend fun showToolEmoticon(text: String) {
        if (!emoticonShown) return
        drawEmoticon(text)
    }

    /**
     * Clear the terminal display and restore the default status bar.
     */
    suspend fun clearTerminalText(): Boolean {
        terminalFlushJob?.cancel()
        terminalFlushJob = null
        return flushTerminalDisplay()
    }

    /**
     * Show an emoticon on the terminal without auto-flush; the caller flushes it. Not while a run
     * holds the agent display: the slot is its HUD's then. True when it was drawn.
     */
    private suspend fun drawEmoticon(text: String): Boolean {
        val sdk = terminal ?: return false
        if (!sdk.isDisplayAvailable || AgentDisplayLease.isHeld()) return false
        try {
            // Set first: a draw cut short by a cancel may still have reached the panel.
            emoticonShown = true
            sdk.showText(text)
            Log.i(TAG, "showTerminalEmoticon: '$text'")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "showTerminalEmoticon failed", e)
            return false
        }
        // A run took the display while this was being drawn: the slot is its now.
        if (AgentDisplayLease.isHeld()) {
            flushTerminalDisplay()
            return false
        }
        return true
    }

    /**
     * Restore the default status bar by calling resume(ID_STATUSBAR) and destroyTouchHandler().
     */
    private suspend fun flushTerminalDisplay(): Boolean {
        val display = terminal?.display ?: return false
        return try {
            display.resume(display.ID_STATUSBAR)
            display.destroyTouchHandler()
            emoticonShown = false
            Log.d(TAG, "flushTerminalDisplay: restored status bar")
            true
        } catch (e: Exception) {
            Log.e(TAG, "flushTerminalDisplay failed", e)
            false
        }
    }

    fun destroy() {
        cancelAll()
        terminal?.destroy()
        scope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Internal animations (used by lifecycle hooks)
    // ═══════════════════════════════════════════════════════════════════════

    /** Display the static Chad face on the LED matrix (eyes, nose, mouth). */
    private fun showChadPattern() {
        val led = led ?: return
        val hw = hwBrightness()
        val c = normalizeColor(led.getSystemColor() ?: "#FFFFFF")
        Log.d(TAG, "showChadPattern — color=$c, hwBrightness=$hw")
        led.setCustomPattern(arrayOf(
            arrayOf(c,   OFF, c),
            arrayOf(OFF, c,   OFF),
            arrayOf(c,   c,   c),
        ), hw)
    }

    private suspend fun runSpinnerAnimation() {
        val positions = listOf(
            0 to 0, 0 to 1, 0 to 2,
            1 to 2,
            2 to 2, 2 to 1, 2 to 0,
            1 to 0,
        )
        val color = "#FFFFFF"
        val trailColor = "#8080FF"
        var index = 0

        while (true) {
            val hw = hwBrightness()
            val pattern = Array(3) { Array(3) { OFF } }
            val curr = positions[index % positions.size]
            val prev = positions[(index - 1 + positions.size) % positions.size]
            pattern[curr.first][curr.second] = color
            pattern[prev.first][prev.second] = trailColor
            led?.setCustomPattern(pattern, hw)
            delay(SPINNER_FRAME_MS)
            index++
        }
    }

    private fun showCompletionPattern(intent: LedIntent) {
        val led = led ?: return
        val hw = hwBrightness()
        when (intent) {
            LedIntent.GREETING -> showSmilePattern()
            LedIntent.SUCCESS -> led.flashSuccess()
            LedIntent.ERROR -> led.flashError()
            LedIntent.PROCESSING -> led.displayInfo()
            LedIntent.IDLE -> led.setAllColor("#00FF00", hw)
        }
    }

    private fun showSmilePattern() {
        val c = "#FFFF00"
        val hw = hwBrightness()
        led?.setCustomPattern(arrayOf(
            arrayOf(c,   OFF, c),
            arrayOf(OFF, OFF, OFF),
            arrayOf(c,   c,   c),
        ), hw)
    }

    private suspend fun showErrorBlink() {
        val red = "#FF0000"
        val hw = hwBrightness()
        val cornerPattern = arrayOf(
            arrayOf(red, OFF, red),
            arrayOf(OFF, OFF, OFF),
            arrayOf(red, OFF, red),
        )
        repeat(3) {
            led?.setCustomPattern(cornerPattern, hw)
            delay(250)
            led?.clear()
            delay(200)
        }
        led?.setCustomPattern(cornerPattern, hw)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Color utilities
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Normalises a hex colour to `#RRGGBB` format for the TerminalSDK.
     *
     * The SDK's own `normalizeColor` converts `#RRGGBB` → `0xRRGGBB` (stripping
     * alpha from `#AARRGGBB`). We output `#RRGGBB` so the SDK handles the final
     * conversion to the driver-expected `0xRRGGBB` format.
     *
     * Brightness is handled separately via the SDK's hardware brightness parameter
     * (0–8), mapped from the user's max RGB preference by [hwBrightness].
     */
    internal fun normalizeColor(hex: String): String {
        val clean = hex.removePrefix("#").removePrefix("0x")
        val offset = if (clean.length == 8) 2 else 0
        val r = clean.substring(offset, offset + 2).toInt(16)
        val g = clean.substring(offset + 2, offset + 4).toInt(16)
        val b = clean.substring(offset + 4, offset + 6).toInt(16)
        return "#%02X%02X%02X".format(r, g, b)
    }

    private fun cancelAll() {
        cancelLedJobs()
        terminalFlushJob?.cancel()
        terminalFlushJob = null
    }

    /** The LED animations only; the terminal screen's emoticon and its pending flush stay. */
    private fun cancelLedJobs() {
        animationJob?.cancel()
        animationJob = null
        completionJob?.cancel()
        completionJob = null
        timedClearJob?.cancel()
        timedClearJob = null
    }
}
