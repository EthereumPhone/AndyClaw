package org.ethereumphone.andyclaw.autopilot.replay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.util.Locale

/**
 * Renders a [ReplayRecorder.Recording] into a short, shareable MP4: the agent display at about
 * three times real speed, a ring on each element as it is acted on, the step caption, a running
 * clock and step counter, and an end card with the totals.
 *
 * H.264 through MediaCodec's input surface; frames are drawn with a hardware canvas. The
 * encoder is configured without B-frames, so output buffers come out in input order and get
 * presentation times from their index.
 */
object ReplayVideoExporter {

    private const val WIDTH = 720
    private const val HEIGHT = 1080
    private const val FPS = 30
    private const val BITRATE = 4_000_000
    private const val SPEEDUP = 3.0
    private const val MIN_HOLD_FRAMES = 8
    private const val END_CARD_FRAMES = 60
    private const val FRAME_TOP = 140
    private const val ACCENT = 0xFF39FF88.toInt()
    private const val PLANNER = 0xFFFFC857.toInt()
    private const val DISPLAY_PX = 720f

    fun export(context: Context, rec: ReplayRecorder.Recording): File {
        val dir = File(context.cacheDir, "replays").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the newest
        val out = File(dir, "autopilot-${rec.runId}.mp4")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val drain = Drainer(codec, muxer)

        try {
            val painter = Painter(rec)
            val frames = rec.frames.sortedBy { it.atMs }
            frames.forEachIndexed { i, frame ->
                val bmp = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size) ?: return@forEachIndexed
                val nextAt = frames.getOrNull(i + 1)?.atMs ?: (frame.atMs + 600)
                val hold = maxOf(MIN_HOLD_FRAMES, ((nextAt - frame.atMs) / SPEEDUP * FPS / 1000).toInt())
                val mark = rec.marks.lastOrNull { it.step == frame.step + 1 } ?: rec.marks.lastOrNull { it.step <= frame.step }
                for (f in 0 until hold) {
                    val canvas = surface.lockHardwareCanvas()
                    painter.draw(canvas, bmp, mark, frame.atMs + ((nextAt - frame.atMs) * f / hold), f / hold.toFloat(), endCard = false)
                    surface.unlockCanvasAndPost(canvas)
                    drain.drain(endOfStream = false)
                }
                if (i == frames.lastIndex) {
                    for (f in 0 until END_CARD_FRAMES) {
                        val canvas = surface.lockHardwareCanvas()
                        painter.draw(canvas, bmp, null, rec.durationMs, 1f, endCard = true)
                        surface.unlockCanvasAndPost(canvas)
                        drain.drain(endOfStream = false)
                    }
                }
                bmp.recycle()
            }
            codec.signalEndOfInputStream()
            drain.drain(endOfStream = true)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            surface.release()
            if (drain.started) try { muxer.stop() } catch (_: Exception) {}
            muxer.release()
        }
        return out
    }

    private class Drainer(private val codec: MediaCodec, private val muxer: MediaMuxer) {
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        var started = false
        private var index = 0L

        fun drain(endOfStream: Boolean) {
            while (true) {
                val id = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                when {
                    id == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    id == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        started = true
                    }
                    id >= 0 -> {
                        val buf = codec.getOutputBuffer(id)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started && buf != null) {
                            info.presentationTimeUs = index++ * 1_000_000L / FPS
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(id, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }

    private class Painter(private val rec: ReplayRecorder.Recording) {
        private val bg = Paint().apply { color = Color.BLACK }
        private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT; textSize = 40f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 26f; typeface = Typeface.MONOSPACE
        }
        private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 64f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val frameRect = Rect(0, FRAME_TOP, WIDTH, FRAME_TOP + WIDTH)

        fun draw(c: Canvas, frame: Bitmap, mark: ReplayRecorder.Mark?, atMs: Long, progress: Float, endCard: Boolean) {
            c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), bg)
            c.drawText("● AUTOPILOT", 32f, 70f, title)
            c.drawText("ethOS · AndyClaw", 32f, 112f, small)
            c.drawBitmap(frame, null, frameRect, null)

            val stepsSoFar = rec.marks.count { it.atMs <= atMs }
            val footerY = FRAME_TOP + WIDTH + 90f
            if (endCard) {
                val overlay = Paint().apply { color = Color.argb(170, 0, 0, 0) }
                c.drawRect(RectF(frameRect), overlay)
                val done = if (rec.succeeded) "✓ DONE" else "■ STOPPED"
                title.textSize = 72f
                c.drawText(done, WIDTH / 2f - title.measureText(done) / 2, FRAME_TOP + WIDTH / 2f, title)
                title.textSize = 40f
                val line = String.format(Locale.ROOT, "%.1f s · %d steps", rec.durationMs / 1000.0, rec.steps)
                c.drawText(line, WIDTH / 2f - big.measureText(line) / 2, footerY + 20f, big)
                val help = if (rec.plannerCalls == 0) "no model help" else "model helped ${rec.plannerCalls}×"
                c.drawText(help, WIDTH / 2f - small.measureText(help) / 2, footerY + 80f, small)
                return
            }

            if (mark?.x != null && mark.y != null) {
                val scale = WIDTH / DISPLAY_PX
                val cx = mark.x * scale
                val cy = FRAME_TOP + mark.y * scale
                val color = if (mark.fromPlanner) PLANNER else ACCENT
                ring.color = color
                c.drawCircle(cx, cy, 44f, ring)
                if (progress < 0.6f) {
                    val p = progress / 0.6f
                    fill.color = color
                    fill.alpha = (150 * (1 - p)).toInt()
                    c.drawCircle(cx, cy, 16f + 60f * p, fill)
                }
            }
            val clock = String.format(Locale.ROOT, "%.1f s", atMs / 1000.0)
            c.drawText(clock, 32f, footerY, big)
            val steps = "$stepsSoFar steps"
            c.drawText(steps, WIDTH - 32f - big.measureText(steps), footerY, big)
            mark?.let { c.drawText("▸ ${it.label}", 32f, footerY + 70f, small) }
            val sub = rec.subgoals.getOrNull(minOf(rec.subgoals.lastIndex, (stepsSoFar * rec.subgoals.size) / maxOf(1, rec.steps)))
            sub?.let { c.drawText(it.take(44), 32f, footerY + 115f, small.apply { alpha = 170 }) }
            small.alpha = 255
        }
    }
}
