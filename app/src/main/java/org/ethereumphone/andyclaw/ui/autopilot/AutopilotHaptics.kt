package org.ethereumphone.andyclaw.ui.autopilot

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import org.ethereumphone.andyclaw.autopilot.AutopilotEvent

/**
 * The autopilot, felt: a light tick per action, a firmer one per finished sub-goal, a double
 * pulse when it has to ask the model, and a rising pattern when it is done. Ticks are capped so
 * a fast run feels quick rather than buzzing.
 */
class AutopilotHaptics(context: Context) {

    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)
    private var lastTickUptime = 0L

    fun on(event: AutopilotEvent) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = when (event.kind) {
            AutopilotEvent.Kind.ACTING -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastTickUptime < MIN_TICK_INTERVAL_MS) return
                lastTickUptime = now
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.3f)
                    .compose()
            }
            AutopilotEvent.Kind.SUBGOAL_DONE -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            AutopilotEvent.Kind.ESCALATED -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            AutopilotEvent.Kind.DONE -> VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.6f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                .compose()
            // A stop or a failure is felt too — one firm knock — but a hand-over is not an end.
            AutopilotEvent.Kind.FAILED -> if (event.outcome == "handoff") return
            else VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            else -> return
        }
        try {
            v.vibrate(effect)
        } catch (_: RuntimeException) {
            // A primitive this vibrator does not support is not worth failing a run over.
        }
    }

    private companion object {
        /** At most ~8 ticks a second. */
        const val MIN_TICK_INTERVAL_MS = 120L
    }
}
