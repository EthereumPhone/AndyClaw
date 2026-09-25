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
    /**
     * All outside senders together. A new Telegram chat or a fresh XMTP address costs nothing to
     * make, so a budget per sender alone was a budget per identity the attacker cared to mint.
     */
    private val totalCapacity: Int = 10,
    private val totalRefillMs: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Bucket(var tokens: Double, var at: Long)

    private val buckets = LinkedHashMap<String, Bucket>()
    private val total = Bucket(totalCapacity.toDouble(), clock())

    /** True when [key] may run the agent now, which spends one of its tokens and one of everyone's. */
    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clock()
        val b = buckets.getOrPut(key) { Bucket(capacity.toDouble(), now) }
        refill(b, now, capacity, refillMs)
        refill(total, now, totalCapacity, totalRefillMs)
        if (buckets.size > MAX_SENDERS) buckets.remove(buckets.keys.first())
        if (b.tokens < 1.0 || total.tokens < 1.0) return false
        b.tokens -= 1.0
        total.tokens -= 1.0
        return true
    }

    private fun refill(b: Bucket, now: Long, cap: Int, every: Long) {
        // A clock set back must not strand the bucket empty until it catches up.
        val elapsed = (now - b.at).coerceAtLeast(0L)
        b.tokens = (b.tokens + elapsed.toDouble() / every).coerceAtMost(cap.toDouble())
        b.at = now
    }

    private companion object {
        const val MAX_SENDERS = 256
    }
}
