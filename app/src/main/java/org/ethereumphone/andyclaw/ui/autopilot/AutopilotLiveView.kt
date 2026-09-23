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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ethereumphone.andyclaw.autopilot.AgentDisplayCapabilities
import org.ethereumphone.andyclaw.autopilot.AppAutopilotDevice
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayBinder
import java.util.Locale

private val Accent = Color(0xFF39FF88)
private val Panel = Color(0xE6101010)
private const val DISPLAY_PX = AppAutopilotDevice.WIDTH.toFloat()

/**
 * The agent display, live, with what the autopilot is doing drawn over it: a focus ring that
 * springs to each element as it is acted on, the sub-goals, a step ticker, the speed, and STOP.
 *
 * The picture is a SurfaceFlinger mirror of the agent display when the OS offers one (60 fps, no
 * copies); otherwise the most recent captured frame.
 */
@Composable
fun AutopilotLiveView(
    state: AutopilotUiState?,
    fallbackFrame: Bitmap?,
    modifier: Modifier = Modifier,
    onStop: () -> Unit = { AgentDisplayCapabilities.requestStop() },
    onDismiss: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
            if (AgentDisplayCapabilities.hasV2) {
                AgentDisplayMirror(Modifier.fillMaxSize())
            } else if (fallbackFrame != null) {
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
            if (state?.phase == AutopilotUiState.Phase.THINKING) {
                Text(
                    "asking the model…",
                    color = Accent,
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
                    endLine(state),
                    color = if (state.phase == AutopilotUiState.Phase.FAILED) Color(0xFFFF6B6B) else Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                if (!state.finished) {
                    TextButton(onClick = onStop) { Text("STOP", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold) }
                } else {
                    if (onShare != null && state.phase == AutopilotUiState.Phase.DONE) {
                        TextButton(onClick = onShare) { Text("SHARE", color = Accent, fontWeight = FontWeight.Bold) }
                    }
                    if (onDismiss != null) {
                        TextButton(onClick = onDismiss) { Text("CLOSE", color = Color.White) }
                    }
                }
            }
        }
    }
}

private fun endLine(s: AutopilotUiState): String = when (s.phase) {
    AutopilotUiState.Phase.DONE -> String.format(
        Locale.ROOT, "✓ Done in %.1f s · %d steps", s.elapsedMs / 1000.0, s.steps,
    ) + if (s.plannerCalls == 0) " · no model help" else " · model helped ${s.plannerCalls}×"
    AutopilotUiState.Phase.FAILED -> "Stopped: ${s.reason ?: "unknown"}"
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
 * The agent display's own pixels, via a mirror layer reparented under a SurfaceView. The layer
 * is display-sized, so it is scaled to the view; when the view goes, the mirror is released.
 */
@Composable
private fun AgentDisplayMirror(modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    private var mirror: SurfaceControl? = null

                    override fun surfaceCreated(holder: SurfaceHolder) {
                        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                            val sc = withContext(Dispatchers.IO) {
                                try {
                                    AgentDisplayBinder.serviceOrNull()?.mirrorAgentDisplay()
                                } catch (e: Exception) {
                                    Log.d("AutopilotLiveView", "mirror unavailable: ${e.message}")
                                    null
                                }
                            } ?: return@launch
                            if (!holder.surface.isValid) {
                                sc.release()
                                return@launch
                            }
                            mirror = sc
                            apply(width, height)
                        }
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        apply(width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        mirror?.let { sc ->
                            SurfaceControl.Transaction().reparent(sc, null).apply()
                            sc.release()
                        }
                        mirror = null
                    }

                    private fun apply(width: Int, height: Int) {
                        val sc = mirror ?: return
                        val scale = minOf(width, height) / DISPLAY_PX
                        SurfaceControl.Transaction()
                            .reparent(sc, surfaceControl)
                            .setScale(sc, scale, scale)
                            .setVisibility(sc, true)
                            .apply()
                    }
                })
            }
        },
    )
}
