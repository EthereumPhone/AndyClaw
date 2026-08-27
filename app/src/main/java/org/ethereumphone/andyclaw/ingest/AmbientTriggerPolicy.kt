package org.ethereumphone.andyclaw.ingest

/** Why an ingest is being considered. */
enum class AmbientSignal {
    /** A mail app posted a notification — something arrived. */
    MAIL_NOTIFICATION,

    /** A calendar app posted a notification — something changed or is about to start. */
    CALENDAR_NOTIFICATION,

    /** The user unlocked the device. Cheap, frequent, and a good moment to be current. */
    USER_PRESENT,

    /** The network came back. Whatever was missed while offline is fetchable now. */
    CONNECTIVITY,

    /** The periodic sweep. The backstop, not the loop. */
    SCHEDULED,

    /** The user, or a test, asked directly. */
    MANUAL,
}

/**
 * When an event is worth re-reading mail and calendar for.
 *
 * `andyclaw-to-agent-first.md` §3: invert the trigger hierarchy. The notification listener
 * is already event-driven; mail and calendar join it, and the clock drops to a backstop. The
 * awkward part of that inversion is not the wiring — it is that a phone posts notifications
 * in bursts, and an ingest per notification would be a network round trip per mail on a
 * battery.
 *
 * So each signal carries its own cooldown, and the cooldowns say what the signal is worth. A
 * mail notification is a strong signal that something new exists, so it is cheap to act on.
 * An unlock is a weak one — it means the user is here, not that anything changed — so it is
 * throttled to something like a coffee break. Connectivity coming back is weaker still.
 *
 * Pure and clock-injected, because "how often does this actually fire" is exactly the kind
 * of thing that is argued about and should be asserted instead.
 */
object AmbientTriggerPolicy {

    /**
     * Mail apps whose notifications mean "look again".
     *
     * A package list is a blunt instrument and it is the right one here: the alternative is
     * reading notification *content* to decide, and notification content is the untrusted
     * channel `andyclaw-to-agent-first.md` §2 is about. Nothing about the notification is
     * read — only which app posted it.
     */
    val MAIL_PACKAGES = setOf(
        "com.google.android.gm",
        "com.google.android.apps.inbox",
        "com.microsoft.office.outlook",
        "com.fsck.k9",
        "ch.protonmail.android",
        "me.proton.android.mail",
        "com.fastmail.app",
    )

    val CALENDAR_PACKAGES = setOf(
        "com.google.android.calendar",
        "com.android.calendar",
        "com.samsung.android.calendar",
    )

    fun signalFor(packageName: String): AmbientSignal? = when (packageName) {
        in MAIL_PACKAGES -> AmbientSignal.MAIL_NOTIFICATION
        in CALENDAR_PACKAGES -> AmbientSignal.CALENDAR_NOTIFICATION
        else -> null
    }

    /** The shortest gap between two ingests triggered by the same signal. */
    fun cooldownMs(signal: AmbientSignal): Long = when (signal) {
        AmbientSignal.MAIL_NOTIFICATION -> 2 * MINUTE
        AmbientSignal.CALENDAR_NOTIFICATION -> 2 * MINUTE
        AmbientSignal.USER_PRESENT -> 15 * MINUTE
        AmbientSignal.CONNECTIVITY -> 30 * MINUTE
        AmbientSignal.SCHEDULED -> 6 * HOUR
        AmbientSignal.MANUAL -> 0L
    }

    /**
     * Whether [signal] may run an ingest now.
     *
     * [lastIngestMs] is the last ingest by **any** signal, not by this one. That is
     * deliberate: the cooldown protects the network and the battery, and it does not matter
     * which signal spent them — an unlock two seconds after a mail notification has nothing
     * new to fetch.
     */
    fun shouldIngest(signal: AmbientSignal, lastIngestMs: Long, nowMs: Long): Boolean {
        if (signal == AmbientSignal.MANUAL) return true
        if (lastIngestMs <= 0L) return true
        return nowMs - lastIngestMs >= cooldownMs(signal)
    }

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
}
