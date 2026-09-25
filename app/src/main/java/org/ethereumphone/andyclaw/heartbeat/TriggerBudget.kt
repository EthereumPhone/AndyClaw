package org.ethereumphone.andyclaw.heartbeat

/**
 * How often one outside sender may wake the agent.
 *
 * Every agent run is billed to the balance the user shares with their gas. An XMTP sender or a
 * Telegram chat that is not the owner's can write as often as it likes; without a budget, each
 * message was a paid run. A token bucket per sender: a few in a row are fine, a stream is not.
 */
class TriggerBudget(
    private val capacity: Int = 3,
    private val refillMs: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Bucket(var tokens: Double, var at: Long)

    private val buckets = LinkedHashMap<String, Bucket>()

    /** True when [key] may run the agent now, which spends one of its tokens. */
    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clock()
        val b = buckets.getOrPut(key) { Bucket(capacity.toDouble(), now) }
        b.tokens = (b.tokens + (now - b.at).toDouble() / refillMs).coerceAtMost(capacity.toDouble())
        b.at = now
        if (buckets.size > MAX_SENDERS) buckets.remove(buckets.keys.first())
        if (b.tokens < 1.0) return false
        b.tokens -= 1.0
        return true
    }

    private companion object {
        const val MAX_SENDERS = 256
    }
}
