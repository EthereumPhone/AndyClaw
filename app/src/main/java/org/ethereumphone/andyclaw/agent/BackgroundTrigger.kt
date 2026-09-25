package org.ethereumphone.andyclaw.agent

/**
 * What set a background run off, read from the header its prompt starts with.
 *
 * The prompts are built in `HeartbeatBindingService` with these headers, so the runner, the
 * pending-approval queue and the ledger can name the trigger without the prompt text itself —
 * which, for a message, is somebody else's words.
 */
enum class BackgroundTrigger(val source: String, val header: String?) {
    REMINDER("reminder", "## Reminder Fired"),
    CRON("cron", "## Cron Job Fired"),
    XMTP("xmtp", "## New incoming XMTP message"),
    HEARTBEAT("heartbeat", null),
    ;

    companion object {
        fun of(prompt: String): BackgroundTrigger {
            val head = prompt.trimStart()
            return entries.firstOrNull { it.header != null && head.startsWith(it.header) } ?: HEARTBEAT
        }
    }
}
