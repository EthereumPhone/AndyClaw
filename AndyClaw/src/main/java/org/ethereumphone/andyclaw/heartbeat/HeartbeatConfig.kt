package org.ethereumphone.andyclaw.heartbeat

/**
 * Configuration for the heartbeat runner.
 * Mirrors OpenClaw's HeartbeatConfig with Android-appropriate defaults.
 */
data class HeartbeatConfig(
    /** Heartbeat interval in milliseconds. Default: 1 hour. */
    val intervalMs: Long = DEFAULT_INTERVAL_MS,

    /** Prompt sent to the agent on each heartbeat tick. */
    val prompt: String = HeartbeatPrompt.DEFAULT_PROMPT,

    /** Maximum characters for a HEARTBEAT_OK acknowledgment body to still be suppressed. */
    val ackMaxChars: Int = DEFAULT_ACK_MAX_CHARS,

    /** Whether the heartbeat is enabled. */
    val enabled: Boolean = true,

    /** Start of active hours (0-23). Null means always active. */
    val activeHoursStart: Int? = null,

    /** End of active hours (0-23). Null means always active. */
    val activeHoursEnd: Int? = null,

    /** Path to the HEARTBEAT.md file. Null uses default workspace resolution. */
    val heartbeatFilePath: String? = null,

    /**
     * How long after an event-driven run a scheduled tick is redundant.
     *
     * `andyclaw-to-agent-first.md` §3: "an ambient device that thinks every 30 minutes
     * isn't ambient." Once notifications, mail and calendar drive the agent when something
     * actually happens, the clock is the **backstop** — it exists to catch what no event
     * announced, not to be the loop. A tick that lands minutes after an event-driven run
     * re-reads the same HEARTBEAT.md against the same world and bills the user for the
     * privilege.
     *
     * Zero disables the behaviour, which is the default so that nothing changes for a
     * caller that has not opted in; [org.ethereumphone.andyclaw.heartbeat.HeartbeatRunner]
     * treats it as "there is no backstop window" rather than as "suppress nothing" by
     * accident.
     */
    val backstopQuietMs: Long = 0L,
) {
    companion object {
        const val DEFAULT_INTERVAL_MS = 60L * 60 * 1000  // 1 hour
        const val DEFAULT_ACK_MAX_CHARS = 300

        /**
         * The backstop window when event-driven triggers are live.
         *
         * Ten minutes: long enough that a notification and the tick behind it are one
         * thought rather than two, short enough that the schedule still catches a quiet
         * afternoon on the shortest interval the OS allows (five minutes, clamped in
         * `AndyClawHeartbeatService`). Anything much longer and the backstop stops being one.
         */
        const val DEFAULT_BACKSTOP_QUIET_MS = 10L * 60 * 1000
    }
}
