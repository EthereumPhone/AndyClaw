package org.ethereumphone.andyclaw.agentwallet

import java.util.concurrent.atomic.AtomicBoolean

/**
 * At most one money-moving operation at a time.
 *
 * The screen disabled its button once `sending` was set — but that flag is set inside the
 * launched coroutine, one frame after the tap, so a quick double tap (or a biometric prompt
 * that returned twice) got two sends through. The gate is taken synchronously, before anything
 * is launched.
 */
class SendGate {

    private val busy = AtomicBoolean(false)

    /** True when the caller now holds the gate and must [exit] it when done. */
    fun tryEnter(): Boolean = busy.compareAndSet(false, true)

    fun exit() {
        busy.set(false)
    }

    val isBusy: Boolean get() = busy.get()
}
