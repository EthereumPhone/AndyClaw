package org.ethereumphone.andyclaw.safety

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Who hears what a run says.
 *
 * A run whose reply goes to somebody other than the device owner — a Telegram chat that is not
 * the owner's, an XMTP auto-reply — must not read the owner's private data: whatever it reads, it
 * can repeat, and the reply is the channel. A run with no audience at all (a heartbeat set off by
 * a notification) talks to nobody, but it can still reach the web, which is why
 * [ProvenanceGate] also keeps an untrusted run that has read private data off the network.
 *
 * Published into a run's context by the runner that knows where the reply goes; absent means
 * "no reply channel".
 */
class ReplyAudience(
    /** True when the reply reaches only the device owner; false when a stranger reads it. */
    val ownerOnly: Boolean,
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<ReplyAudience> {
        val OWNER = ReplyAudience(ownerOnly = true)
        val STRANGER = ReplyAudience(ownerOnly = false)
    }
}
