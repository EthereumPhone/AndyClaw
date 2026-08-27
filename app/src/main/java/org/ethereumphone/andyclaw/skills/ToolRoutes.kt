package org.ethereumphone.andyclaw.skills

/**
 * The execution ladder, seeded for the tools that were written before it existed.
 *
 * `agent-os-design.md` §3: "Every intent resolves down this ladder. **Never** skip a rung
 * to reach a lower one." Rungs 0, 2 and 4 already exist in this app as ordinary skills;
 * what was missing was any statement of *which* rung a tool sits on and which app it is a
 * route into. A tool can declare that itself ([ToolDefinition.rung] /
 * [ToolDefinition.targetPackages]) — compiled flows do — and this table supplies it for
 * the built-ins, the same way `safety/ToolEffects` seeds effects for tools that predate
 * `ToolEffect`.
 *
 * Only tools that are genuinely *another way of doing the same thing* belong here. Most
 * tools are not on the ladder at all, and `null` says so.
 */
object ToolRoutes {

    /** Rung 4: driving an app's UI with a model in the loop. The last resort. */
    const val RUNG_DISPLAY = 4

    private data class Route(val rung: Int, val packages: List<String>)

    private infix fun String.at(route: Route) = this to route
    private fun rung(rung: Int, vararg packages: String) = Route(rung, packages.toList())

    /**
     * Rung 0 is a real backend API — milliseconds, no UI, no drift. Rung 2 is a
     * notification `RemoteInput`: free reliability, but only for a thread that already
     * has a live notification, which is why it names no package.
     */
    private val SEED: Map<String, Route> = mapOf(
        // ── Rung 0 — native APIs ──────────────────────────────────────
        // Note the package spelling: the messenger is `ethereumhpone` (transposed
        // "hp") while the agent is `ethereumphone`. Both are load-bearing.
        "send_xmtp_message" at rung(0, "org.ethereumhpone.messenger"),
        "send_message_to_user" at rung(0, "org.ethereumhpone.messenger"),
        "read_messages" at rung(0, "org.ethereumhpone.messenger"),
        "list_conversations" at rung(0, "org.ethereumhpone.messenger"),

        "send_sms" at rung(0, "com.google.android.apps.messaging", "com.android.messaging", "com.android.mms"),
        "read_sms" at rung(0, "com.google.android.apps.messaging", "com.android.messaging", "com.android.mms"),

        "gmail_send" at rung(0, "com.google.android.gm"),
        "gmail_reply" at rung(0, "com.google.android.gm"),
        "gmail_search" at rung(0, "com.google.android.gm"),
        "gmail_read" at rung(0, "com.google.android.gm"),
        "gmail_list_messages" at rung(0, "com.google.android.gm"),

        "gcal_list_events" at rung(0, "com.google.android.calendar"),
        "gcal_create_event" at rung(0, "com.google.android.calendar"),
        "list_calendar_events" at rung(0, "com.android.calendar", "com.google.android.calendar"),
        "create_calendar_event" at rung(0, "com.android.calendar", "com.google.android.calendar"),

        "send_telegram_message" at rung(0, "org.telegram.messenger"),

        "search_contacts" at rung(0, "com.android.contacts", "com.google.android.contacts"),
        "get_contact" at rung(0, "com.android.contacts", "com.google.android.contacts"),

        "make_call" at rung(0, "com.android.dialer", "com.google.android.dialer"),
        "call_number" at rung(0, "com.android.dialer", "com.google.android.dialer"),

        // ── Rung 2 — notification RemoteInput ─────────────────────────
        // Names no package on purpose: it is a route into whatever app happens to have
        // a live notification, which nothing here can know ahead of time. It is on the
        // ladder so the model is told what it is, not so the gate can act on it.
        "reply_to_notification" at rung(2),
    )

    fun rungOf(toolName: String): Int? = SEED[toolName]?.rung

    fun targetsOf(toolName: String): List<String> = SEED[toolName]?.packages ?: emptyList()

    /** Every seeded tool that is a route into [packageName], best rung first. */
    fun routesInto(packageName: String): List<String> =
        SEED.entries
            .filter { packageName in it.value.packages }
            .sortedBy { it.value.rung }
            .map { it.key }
}
