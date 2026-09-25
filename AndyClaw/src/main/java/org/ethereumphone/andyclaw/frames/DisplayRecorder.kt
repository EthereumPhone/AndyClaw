package org.ethereumphone.andyclaw.frames

/**
 * Which frames of the agent display a recording keeps.
 *
 * The display is read several times a second while the autopilot runs, for the live view; keeping
 * every one of those filled a recording's cap in a couple of minutes, after which it kept nothing.
 * A frame is kept when at least [minIntervalMs] has passed since the last one kept *and* it shows
 * something the last one did not — a screen that sits still is kept once, however long it sits.
 * A private app's frame is never kept; it is counted, so the gap in the replay has a reason.
 */
class DisplayRecorder(
    private val minIntervalMs: Long = 1_000L,
    private val maxFrames: Int = 600,
) {
    enum class Verdict { KEEP, SAME, TOO_SOON, PRIVATE, FULL }

    private var lastKeptMs: Long? = null
    private var lastHash: Int? = null

    var kept = 0
        private set
    var skippedPrivate = 0
        private set

    /** True once [maxFrames] stopped the recording short. */
    var truncated = false
        private set

    private var firstMs: Long? = null
    private var lastMs: Long? = null

    /** From the first frame offered to the last: how long the recording actually spans. */
    val durationMs: Long get() = (lastMs ?: 0L) - (firstMs ?: 0L)

    fun offer(frame: ByteArray, nowMs: Long, privateApp: Boolean = false): Verdict {
        if (firstMs == null) firstMs = nowMs
        lastMs = nowMs
        if (privateApp) {
            skippedPrivate++
            return Verdict.PRIVATE
        }
        if (kept >= maxFrames) {
            truncated = true
            return Verdict.FULL
        }
        lastKeptMs?.let { if (nowMs - it < minIntervalMs) return Verdict.TOO_SOON }
        val hash = frame.contentHashCode()
        if (hash == lastHash) return Verdict.SAME
        lastHash = hash
        lastKeptMs = nowMs
        kept++
        return Verdict.KEEP
    }
}
