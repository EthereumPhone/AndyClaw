package org.ethereumphone.andyclaw.autopilot.replay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import org.ethereumphone.andyclaw.autopilot.AutopilotEvent
import org.ethereumphone.andyclaw.autopilot.ScreenSnapshot
import java.io.ByteArrayOutputStream

/**
 * Keeps the frames and events of the most recent autopilot run, so it can be exported as a
 * replay video if — and only if — the user asks.
 *
 * Nothing here leaves memory on its own. Text fields and password fields are blacked out in
 * every frame before it is kept, so a shared replay does not carry what was typed. Bounded to
 * [MAX_FRAMES] small JPEGs.
 */
object ReplayRecorder {

    data class Frame(val step: Int, val atMs: Long, val jpeg: ByteArray)

    data class Mark(
        val step: Int,
        val label: String,
        val x: Int?,
        val y: Int?,
        val fromPlanner: Boolean,
        val atMs: Long,
    )

    data class Recording(
        val runId: String,
        val subgoals: List<String>,
        val frames: List<Frame>,
        val marks: List<Mark>,
        val steps: Int,
        val durationMs: Long,
        val plannerCalls: Int,
        val succeeded: Boolean,
    )

    private const val MAX_FRAMES = 80
    private const val JPEG_QUALITY = 70

    private val lock = Any()
    private var runId: String? = null
    private var startedUptime = 0L
    private var subgoals: List<String> = emptyList()
    private val frames = ArrayList<Frame>()
    private val marks = ArrayList<Mark>()
    private var finished: Recording? = null

    fun onEvent(e: AutopilotEvent) = synchronized(lock) {
        if (e.kind == AutopilotEvent.Kind.STARTED || e.runId != runId) {
            runId = e.runId
            startedUptime = SystemClock.uptimeMillis()
            frames.clear()
            marks.clear()
            finished = null
        }
        if (e.subgoals.isNotEmpty()) subgoals = e.subgoals
        when (e.kind) {
            AutopilotEvent.Kind.ACTING -> marks += Mark(
                step = e.step + 1,
                label = listOfNotNull(verb(e.action), e.target?.name?.take(28)?.let { "\"$it\"" }).joinToString(" "),
                x = e.target?.centerX,
                y = e.target?.centerY,
                fromPlanner = e.source == AutopilotEvent.Source.PLANNER,
                atMs = e.elapsedMs,
            )
            AutopilotEvent.Kind.DONE, AutopilotEvent.Kind.FAILED -> finished = Recording(
                runId = e.runId,
                subgoals = subgoals,
                frames = frames.toList(),
                marks = marks.toList(),
                steps = e.step,
                durationMs = e.elapsedMs,
                plannerCalls = e.plannerCalls,
                succeeded = e.kind == AutopilotEvent.Kind.DONE,
            )
            else -> Unit
        }
    }

    /** Adds a frame (a JPEG of the agent display), redacting [screen]'s editable fields first. */
    fun addFrame(step: Int, jpeg: ByteArray, screen: ScreenSnapshot?) {
        val redacted = redact(jpeg, screen) ?: return
        synchronized(lock) {
            if (runId == null || frames.size >= MAX_FRAMES) return
            val at = SystemClock.uptimeMillis() - startedUptime
            frames += Frame(step, at, redacted)
            // A frame captured after DONE belongs to the finished recording too.
            finished?.let { finished = it.copy(frames = frames.toList()) }
        }
    }

    fun latest(): Recording? = synchronized(lock) { finished?.takeIf { it.frames.isNotEmpty() } }

    private fun redact(jpeg: ByteArray, screen: ScreenSnapshot?): ByteArray? {
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val bmp = decoded.copy(Bitmap.Config.ARGB_8888, true)
        decoded.recycle()
        if (screen != null) {
            val scale = bmp.width / screen.width.toFloat()
            val canvas = Canvas(bmp)
            val paint = Paint().apply { color = Color.rgb(24, 24, 24) }
            for (e in screen.elements) {
                if (!(e.editable || e.password)) continue
                val l = e.left ?: continue
                val t = e.top ?: continue
                val r = e.right ?: continue
                val b = e.bottom ?: continue
                canvas.drawRect(l * scale, t * scale, r * scale, b * scale, paint)
            }
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun verb(action: String?) = when (action) {
        "tap" -> "TAP"
        "long" -> "HOLD"
        "type" -> "TYPE"
        "scroll_fwd", "scroll_back" -> "SCROLL"
        "back" -> "BACK"
        null -> null
        else -> action.uppercase()
    }
}
