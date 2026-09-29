package org.ethereumphone.andyclaw.heartbeat

/**
 * Admission for XMTP messages the OS relays to the heartbeat service, which answers them one at
 * a time.
 *
 * The service used to take a `tryLock()` to drop the OS relaying one event twice, and so dropped
 * every message that arrived while another was being answered. Messages now queue behind each
 * other; this decides which may join the queue:
 * - the same sender and text again within [duplicateWindowMs] is a relayed duplicate;
 * - at most [maxQueued] messages may be running or waiting, so a flood cannot pile up runs.
 *
 * Asked before the per-sender trigger budget, so neither a duplicate nor a message the queue has
 * no room for spends one of the sender's runs. Every [Admission.ADMITTED] is paired with exactly
 * one [release].
 */
class XmtpRelayGate(
    private val maxQueued: Int = 5,
    private val duplicateWindowMs: Long = 30_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Admission { ADMITTED, DUPLICATE, QUEUE_FULL }

    private var inFlight = 0
    private val recent = LinkedHashMap<String, Long>()

    @Synchronized
    fun admit(sender: String, text: String): Admission {
        val now = clock()
        // A clock set back counts as expired rather than stranding an entry.
        recent.entries.removeAll { now - it.value > duplicateWindowMs || now < it.value }
        val key = sender.lowercase() + "\u0000" + text
        if (key in recent) return Admission.DUPLICATE
        if (inFlight >= maxQueued) return Admission.QUEUE_FULL
        recent[key] = now
        while (recent.size > MAX_REMEMBERED) recent.remove(recent.keys.first())
        inFlight++
        return Admission.ADMITTED
    }

    /** The admitted message is done, or will not run after all. */
    @Synchronized
    fun release() {
        if (inFlight > 0) inFlight--
    }

    private companion object {
        const val MAX_REMEMBERED = 64
    }
}
