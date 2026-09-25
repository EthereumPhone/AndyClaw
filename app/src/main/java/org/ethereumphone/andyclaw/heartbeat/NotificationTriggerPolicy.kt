package org.ethereumphone.andyclaw.heartbeat

/**
 * Whether a posted notification should wake the agent.
 *
 * It used to be every notification from any app, once a minute at most: ongoing downloads,
 * media controls, group summaries and the same message re-posted all counted, up to sixty paid
 * runs an hour — and the agent's own messages to the user posted a notification that woke it
 * again. Only a new, alerting notification now counts, not from an app the agent itself just
 * acted in, and no sooner than [minGapMs] after the last one that did.
 */
class NotificationTriggerPolicy(
    private val minGapMs: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What the listener knows about one posted notification. */
    data class Posted(
        val key: String,
        val packageName: String,
        val ongoing: Boolean,
        val groupSummary: Boolean,
        val onlyAlertOnce: Boolean,
        val category: String?,
        /** The agent itself did something in this app a moment ago. */
        val agentActedHere: Boolean,
    )

    private val seen = LinkedHashSet<String>()
    private var lastTriggerMs = 0L

    @Synchronized
    fun shouldTrigger(n: Posted): Boolean {
        if (n.ongoing || n.groupSummary || n.agentActedHere) return false
        if (n.category in IGNORED_CATEGORIES) return false
        // A repeat of one already seen is news only if it alerts again: a chat app re-posts one
        // notification per conversation for each new message, while a silent update
        // (onlyAlertOnce) is the same thing redrawn.
        val firstTime = seen.add(n.key)
        if (seen.size > MAX_SEEN) seen.remove(seen.first())
        if (!firstTime && n.onlyAlertOnce) return false
        val now = clock()
        if (lastTriggerMs != 0L && now - lastTriggerMs < minGapMs()) return false
        lastTriggerMs = now
        return true
    }

    companion object {
        /** Five minutes at least, whatever the backstop window says. */
        const val MIN_GAP_MS = 5 * 60_000L
        private const val MAX_SEEN = 500

        private val IGNORED_CATEGORIES = setOf(
            android.app.Notification.CATEGORY_PROGRESS,
            android.app.Notification.CATEGORY_TRANSPORT,
            android.app.Notification.CATEGORY_SERVICE,
            android.app.Notification.CATEGORY_SYSTEM,
        )
    }
}
