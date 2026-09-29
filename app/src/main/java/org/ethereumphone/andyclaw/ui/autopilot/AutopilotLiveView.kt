package org.ethereumphone.andyclaw.ui.autopilot

import android.graphics.Bitmap
import android.util.Log
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ethereumphone.andyclaw.autopilot.AgentDisplayCapabilities
import org.ethereumphone.andyclaw.autopilot.AppAutopilotDevice
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayBinder
import java.util.Locale

private val Accent = Color(0xFF39FF88)
private val Amber = Color(0xFFFFC857)
private val Panel = Color(0xE6101010)
private const val DISPLAY_PX = AppAutopilotDevice.WIDTH.toFloat()
private const val COLLAPSE_AFTER_MS = 4_000L
private const val MIRROR_ATTEMPTS = 40
private const val MIRROR_RETRY_MS = 300L

/** Where sharing a replay stands, for the button that started it. */
enum class ReplayShareState { IDLE, WORKING, FAILED }

/**
 * The agent display, live, with what the autopilot is doing drawn over it: a focus ring that
 * springs to each element as it is acted on, the sub-goals, a step ticker, the speed, and STOP.
 *
 * The picture is a SurfaceFlinger mirror of the agent display while the display is live (60 fps,
 * no copies), taken again whenever it comes back, and the most recent captured frame whenever no
 * mirror is held — so it is never black. A few seconds after the run ends the view folds into one
 * line, so the reply underneath can be read; a tap opens it again.
 */
@Composable
fun AutopilotLiveView(
    state: AutopilotUiState?,
    fallbackFrame: Bitmap?,
    modifier: Modifier = Modifier,
    onStop: () -> Unit = { AgentDisplayCapabilities.requestStop() },
    onDismiss: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    shareState: ReplayShareState = ReplayShareState.IDLE,
    /** Who the autopilot hands over to: the assistant's name. */
    agentName: String = "the assistant",
) {
    // Asked once: the answer is a binder call and does not change while the view is up.
    val mirrorable = remember { AgentDisplayCapabilities.hasV2 }
    var expanded by remember(state?.runId) { mutableStateOf(true) }
    LaunchedEffect(state?.runId, state?.finishedAtMs) {
        if (state?.finishedAtMs != null) {
            delay(COLLAPSE_AFTER_MS)
            expanded = false
        } else {
            expanded = true
        }
    }
    if (state != null && state.finished && !expanded) {
        CollapsedBar(state, onExpand = { expanded = true }, onDismiss = onDismiss, onShare = onShare, shareState = shareState, modifier = modifier)
        return
    }
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
            var mirrorHeld by remember { mutableStateOf(false) }
            if (mirrorable) {
                AgentDisplayMirror(Modifier.fillMaxSize(), onMirror = { mirrorHeld = it })
            }
            if (!mirrorHeld && fallbackFrame != null) {
                Image(
                    bitmap = fallbackFrame.asImageBitmap(),
                    contentDescription = "Agent display",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            if (state != null) FocusRing(state)
            if (state != null) {
                Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { SpeedBadge(state) }
            }
            val overlay = when (state?.phase) {
                AutopilotUiState.Phase.THINKING -> "asking the model…"
                AutopilotUiState.Phase.HANDOFF -> "Handing over to $agentName…"
                else -> null
            }
            if (overlay != null) {
                Text(
                    overlay,
                    color = if (state?.phase == AutopilotUiState.Phase.HANDOFF) Amber else Accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                        .background(Panel, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        if (state != null) {
            SubgoalRail(state)
            Ticker(state)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    endLine(state, agentName),
                    color = endColor(state),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                if (!state.finished) {
                    TextButton(onClick = onStop) { Text("STOP", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold) }
                } else {
                    if (onShare != null && state.phase == AutopilotUiState.Phase.DONE) ShareButton(onShare, shareState)
                    if (onDismiss != null) {
                        TextButton(onClick = onDismiss) { Text("CLOSE", color = Color.White) }
                    }
                }
            }
        }
    }
}

/** The run, finished, in one line: what happened, and the two things left to do with it. */
@Composable
private fun CollapsedBar(
    state: AutopilotUiState,
    onExpand: () -> Unit,
    onDismiss: (() -> Unit)?,
    onShare: (() -> Unit)?,
    shareState: ReplayShareState,
    modifier: Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .clickable(onClick = onExpand)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            endLine(state, null),
            color = endColor(state),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (onShare != null && state.phase == AutopilotUiState.Phase.DONE) ShareButton(onShare, shareState)
        if (onDismiss != null) {
            TextButton(onClick = onDismiss) { Text("✕", color = Color.White) }
        }
    }
}

@Composable
private fun ShareButton(onShare: () -> Unit, shareState: ReplayShareState) {
    TextButton(onClick = onShare, enabled = shareState != ReplayShareState.WORKING) {
        Text(
            when (shareState) {
                ReplayShareState.IDLE -> "SHARE"
                ReplayShareState.WORKING -> "MAKING VIDEO…"
                ReplayShareState.FAILED -> "RETRY SHARE"
            },
            color = if (shareState == ReplayShareState.FAILED) Amber else Accent,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun endColor(s: AutopilotUiState): Color = when (s.phase) {
    AutopilotUiState.Phase.FAILED -> Color(0xFFFF6B6B)
    AutopilotUiState.Phase.STOPPED, AutopilotUiState.Phase.ENDED -> Color.White.copy(alpha = 0.7f)
    AutopilotUiState.Phase.HANDOFF -> Amber
    else -> Color.White
}

private fun endLine(s: AutopilotUiState, agentName: String?): String = when (s.phase) {
    AutopilotUiState.Phase.DONE -> String.format(
        Locale.ROOT, "✓ Done in %.1f s · %d steps", s.elapsedMs / 1000.0, s.steps,
    ) + if (s.plannerCalls == 0) " · no model help" else " · model helped ${s.plannerCalls}×"
    AutopilotUiState.Phase.HANDOFF -> s.message ?: "Handing over to ${agentName ?: "the assistant"}"
    AutopilotUiState.Phase.STOPPED -> "■ ${s.message ?: "Stopped"}"
    AutopilotUiState.Phase.ENDED -> "» ${s.message ?: "Handed over"}"
    AutopilotUiState.Phase.FAILED -> "✕ ${s.message ?: "Couldn't finish this"}"
    else -> String.format(Locale.ROOT, "%.1f s", s.elapsedMs / 1000.0)
}

@Composable
private fun SpeedBadge(s: AutopilotUiState) {
    Column(
        Modifier.background(Panel, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            String.format(Locale.ROOT, "%.1f steps/s", s.stepsPerSecond),
            color = Accent, fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Bold,
        )
        val breakdown = listOfNotNull(
            s.lastJevMs?.let { "jev ${it}ms" },
            s.lastStepMs?.let { "step ${it}ms" },
        ).joinToString(" · ")
        if (breakdown.isNotEmpty()) {
            Text(breakdown, color = Color.White.copy(alpha = 0.8f), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}

/** A ring that springs from one target to the next, with a ripple on each action. */
@Composable
private fun FocusRing(s: AutopilotUiState) {
    val x = remember { Animatable(DISPLAY_PX / 2) }
    val y = remember { Animatable(DISPLAY_PX / 2) }
    val ripple = remember { Animatable(0f) }
    LaunchedEffect(s.actionSeq) {
        val tx = s.targetX?.toFloat() ?: return@LaunchedEffect
        val ty = s.targetY?.toFloat() ?: return@LaunchedEffect
        val springSpec = spring<Float>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
        launch { x.animateTo(tx, springSpec) }
        launch { y.animateTo(ty, springSpec) }
        ripple.snapTo(0f)
        ripple.animateTo(1f, tween(450))
    }
    if (s.targetX == null) return
    Canvas(Modifier.fillMaxSize()) {
        val scale = size.width / DISPLAY_PX
        val center = Offset(x.value * scale, y.value * scale)
        val color = if (s.source == org.ethereumphone.andyclaw.autopilot.AutopilotEvent.Source.PLANNER) Color(0xFFFFC857) else Accent
        drawCircle(color, radius = 26.dp.toPx(), center = center, style = Stroke(width = 3.dp.toPx()))
        if (ripple.value < 1f) {
            drawCircle(
                color.copy(alpha = 0.6f * (1f - ripple.value)),
                radius = (10 + 40 * ripple.value).dp.toPx(),
                center = center,
            )
        }
    }
}

@Composable
private fun SubgoalRail(s: AutopilotUiState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        s.subgoals.forEachIndexed { i, g ->
            val (mark, color) = when {
                i < s.subgoalIndex || s.phase == AutopilotUiState.Phase.DONE -> "✓" to Accent
                i == s.subgoalIndex && !s.finished -> "●" to Color.White
                else -> "○" to Color.White.copy(alpha = 0.5f)
            }
            Text("$mark ${g.take(48)}", color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Ticker(s: AutopilotUiState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        s.ticker.take(4).forEachIndexed { i, line ->
            AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(6.dp).clip(CircleShape)
                            .background(if (line.fromPlanner) Color(0xFFFFC857) else Accent),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "#${line.step} ${line.text}" +
                            (line.ms?.let { "  ${it}ms" } ?: "") +
                            (line.confidence?.let { String.format(Locale.ROOT, "  %.2f", it) } ?: ""),
                        color = Color.White.copy(alpha = if (i == 0) 1f else 0.55f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

/**
 * The agent display's own pixels, via a mirror layer reparented under a SurfaceView.
 *
 * Asked for whenever the display is (or may be) live, and asked again until the OS hands one
 * over — the view usually appears before the run has created the display, and the old code asked
 * once, got nothing, and stayed black. Released when the display is parked or released, and when
 * the view goes. [onMirror] says whether a mirror is showing, so the caller can show the last
 * captured frame instead of black when none is.
 */
@Composable
private fun AgentDisplayMirror(modifier: Modifier, onMirror: (Boolean) -> Unit) {
    val displayState by AgentDisplayCapabilities.displayState.collectAsState()
    val controller = remember { MirrorController(onMirror) }
    DisposableEffect(Unit) { onDispose { controller.release() } }
    LaunchedEffect(displayState, controller.surfaceReady) {
        val gone = displayState == AgentDisplayCapabilities.STATE_PARKED || displayState == AgentDisplayCapabilities.STATE_RELEASED
        if (gone || !controller.surfaceReady) {
            controller.release()
            return@LaunchedEffect
        }
        repeat(MIRROR_ATTEMPTS) {
            if (controller.acquire()) return@LaunchedEffect
            delay(MIRROR_RETRY_MS)
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { context -> SurfaceView(context).also { controller.bind(it) } },
    )
}

/** Holds at most one mirror of the agent display under one SurfaceView. Main thread only. */
private class MirrorController(private val onMirror: (Boolean) -> Unit) {
    private var view: SurfaceView? = null
    private var mirror: SurfaceControl? = null
    var surfaceReady by mutableStateOf(false)
        private set

    fun bind(v: SurfaceView) {
        view = v
        v.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                place(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                release()
            }
        })
    }

    /** True when a mirror is showing now. */
    suspend fun acquire(): Boolean {
        if (mirror != null) return true
        val v = view ?: return false
        val caller = currentCoroutineContext()[Job]
        // The OS creates the mirror layer whether or not this coroutine is still wanted, and a
        // cancelled withContext throws on return and drops what it got: a mirror layer nobody
        // holds and nobody releases. So the call and the decision what to do with its result run
        // under NonCancellable (on this thread — only the binder call moves to IO, and it resumes
        // into the non-cancellable block), and an unwanted mirror is released right there.
        return withContext(NonCancellable) {
            val sc = withContext(Dispatchers.IO) {
                try {
                    AgentDisplayBinder.serviceOrNull()?.mirrorAgentDisplay()
                } catch (e: Exception) {
                    Log.d("AutopilotLiveView", "mirror unavailable: ${e.message}")
                    null
                }
            } ?: return@withContext false
            // Cancelled meanwhile (the display was parked, the surface went, the view left), or
            // another acquire already holds one: this one is not shown.
            if (caller?.isActive == false || mirror != null || !v.holder.surface.isValid) {
                discard(sc)
                return@withContext mirror != null
            }
            mirror = sc
            place(v.width, v.height)
            onMirror(true)
            true
        }
    }

    private fun discard(sc: SurfaceControl) {
        try {
            SurfaceControl.Transaction().reparent(sc, null).apply()
        } catch (_: Exception) {
        }
        try { sc.release() } catch (_: Exception) {}
    }

    fun release() {
        mirror?.let { sc ->
            try {
                SurfaceControl.Transaction().reparent(sc, null).apply()
            } catch (_: Exception) {
            }
            sc.release()
            onMirror(false)
        }
        mirror = null
    }

    private fun place(width: Int, height: Int) {
        val sc = mirror ?: return
        val v = view ?: return
        val scale = minOf(width, height) / DISPLAY_PX
        SurfaceControl.Transaction()
            .reparent(sc, v.surfaceControl)
            .setScale(sc, scale, scale)
            .setVisibility(sc, true)
            .apply()
    }
}
