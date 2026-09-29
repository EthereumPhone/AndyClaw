package org.ethereumphone.andyclaw.heartbeat

/**
 * Whether an OS heartbeat tick runs the heartbeat (SET-02).
 *
 * On ethOS the scheduler is `AndyClawHeartbeatService` in system_server, which clamps the interval
 * to 5–60 minutes and ticks at most hourly — it has to, because the same tick drives ambient
 * ingestion's sweep. The app has always offered up to 24 hours, so a user who picked 24 h to save
 * money got a paid run every hour. The tick now runs the heartbeat only once the chosen interval
 * has passed since the last scheduled run; up to an hour the OS already ticks at the interval, and
 * every tick counts as before.
 *
 * Only the scheduled run is gated. Ambient ingestion's sweep, XMTP and notification-triggered runs
 * and the user's own heartbeat button do not pass through here.
 */
object HeartbeatTickGate {

    /** The longest the OS waits between ticks: it clamps the interval to an hour. */
    const val OS_MAX_TICK_MINUTES = 60

    /** A tick this close to the interval counts: OS alarms drift by a few minutes. */
    const val SLACK_MS = 10 * 60_000L

    /** When the last scheduled heartbeat was let through, as epoch millis (a string pref). */
    const val PREF_LAST_SCHEDULED_RUN_MS = "heartbeat.lastScheduledRunMs"

    fun shouldRun(nowMs: Long, lastScheduledRunMs: Long, intervalMinutes: Int): Boolean {
        if (intervalMinutes <= 0) return false
        // The OS already ticks at this interval.
        if (intervalMinutes <= OS_MAX_TICK_MINUTES) return true
        // Never ran, or the clock was set back past it: nothing to measure from.
        if (lastScheduledRunMs <= 0L || nowMs < lastScheduledRunMs) return true
        return nowMs - lastScheduledRunMs >= intervalMinutes * 60_000L - SLACK_MS
    }
}
