package org.ethereumphone.andyclaw.heartbeat

/**
 * Configuration for the heartbeat runner.
 * Mirrors OpenClaw's HeartbeatConfig with Android-appropriate defaults.
 */
data class HeartbeatConfig(
    /** Heartbeat interval in milliseconds. Default: 30 minutes. */
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
) {
    companion object {
        const val DEFAULT_INTERVAL_MS = 30L * 60 * 1000  // 30 minutes
        const val DEFAULT_ACK_MAX_CHARS = 300
    }
}
