package org.ethereumphone.andyclaw.ledger

/**
 * What the home screen and the ledger screen say about a run, derived from the rows.
 *
 * Never stored and never exported: the row format and the hash chain are frozen, so everything
 * here is computed when the rows are read. Three questions it answers:
 * - **What set the run off** ([trigger]) — the user, a heartbeat, a reminder, a message.
 * - **What to call it** ([displayIntent]) — never the raw text of a message somebody else wrote.
 *   Rows written before background runs were labelled still hold their whole prompt; those are
 *   shown by the kind of trigger only.
 * - **What it did** ([summary], [actedOnBehalf]) — from the TOOL rows that belong to the turn.
 */
object LedgerDigest {

    // Headers the app's background prompts start with. Rows from older builds stored the whole
    // prompt as the intent, so these are how such a row still says what it was.
    const val HEADER_REMINDER = "## Reminder Fired"
    const val HEADER_CRON = "## Cron Job Fired"
    const val HEADER_XMTP = "## New incoming XMTP message"

    // What the app writes as a background run's intent (see BackgroundIntent).
    const val LABEL_HEARTBEAT = "Heartbeat tasks"
    const val LABEL_HEARTBEAT_NOTIFICATION = "Heartbeat, after a notification"
    const val LABEL_REMINDER = "Reminder"
    const val LABEL_CRON = "Scheduled task"
    const val LABEL_XMTP = "XMTP message"
    const val LABEL_TELEGRAM_OWNER = "Your Telegram message"
    const val LABEL_TELEGRAM_OTHER = "Telegram message from another chat"

    /** The session rows about the ledger itself go to. */
    const val SYSTEM_SESSION = "system:ledger"

    private val APPROVAL_PREFIXES = listOf("Approved: ", "Declined: ", "Acknowledged, not run: ")

    /** Tools that are bookkeeping, not steps the agent took. */
    private val SYNTHETIC_TOOLS = setOf("ledger", "agent_turn")

    enum class Trigger(val wire: String) {
        USER("user"),
        HEARTBEAT("heartbeat"),
        REMINDER("reminder"),
        CRON("cron"),
        XMTP("xmtp"),
        TELEGRAM("telegram"),
        APPROVAL("approval"),
        SYSTEM("system"),
    }

    data class Summary(
        val toolsRun: Int,
        val toolsBlocked: Int,
        val toolErrors: Int,
        /** Steps that ran and changed something outside the process. */
        val sideEffects: Int,
        val sideEffectTools: List<String>,
        val frames: Int,
    )

    fun trigger(e: LedgerEntry): Trigger {
        val session = e.sessionId
        val intent = e.intent.trimStart()
        return when {
            session.startsWith("system:") || e.actions.any { it.tool == "ledger" } -> Trigger.SYSTEM
            // The owner's decision, which AndyClaw writes as a USER row. An older row that holds a
            // stranger's raw message can start with the same words, and is not one.
            session.startsWith("approval:") ||
                (e.provenance != "UNTRUSTED" && APPROVAL_PREFIXES.any { intent.startsWith(it) }) -> Trigger.APPROVAL
            session.startsWith("telegram:") -> Trigger.TELEGRAM
            session.startsWith("background:") -> when {
                intent.startsWith(HEADER_REMINDER) || intent.startsWith(LABEL_REMINDER) -> Trigger.REMINDER
                intent.startsWith(HEADER_CRON) || intent.startsWith(LABEL_CRON) -> Trigger.CRON
                intent.startsWith(HEADER_XMTP) || intent.startsWith(LABEL_XMTP) -> Trigger.XMTP
                else -> Trigger.HEARTBEAT
            }
            else -> Trigger.USER
        }
    }

    /**
     * The words to show for [e]. The user's own request as they typed it, an approval as
     * AndyClaw wrote it, and for anything a message, a notification or a schedule set off: the
     * label the app wrote — or, for an older row that still holds the whole prompt, just the kind
     * of trigger. Never somebody else's text.
     */
    fun displayIntent(e: LedgerEntry): String {
        val intent = e.intent.trim()
        return when (trigger(e)) {
            Trigger.USER -> if (e.provenance == "UNTRUSTED") "A request from untrusted content" else intent
            Trigger.APPROVAL, Trigger.SYSTEM -> intent
            Trigger.HEARTBEAT -> intent.takeIf { it == LABEL_HEARTBEAT || it == LABEL_HEARTBEAT_NOTIFICATION } ?: LABEL_HEARTBEAT
            Trigger.REMINDER -> intent.takeIf { it == LABEL_REMINDER || it.startsWith("$LABEL_REMINDER: ") } ?: LABEL_REMINDER
            Trigger.CRON -> intent.takeIf { it == LABEL_CRON || it.startsWith("$LABEL_CRON: ") } ?: LABEL_CRON
            Trigger.XMTP -> intent.takeIf { it.startsWith("$LABEL_XMTP from ") } ?: LABEL_XMTP
            Trigger.TELEGRAM -> intent.takeIf { it == LABEL_TELEGRAM_OWNER || it == LABEL_TELEGRAM_OTHER } ?: "Telegram message"
        }
    }

    /**
     * The TOOL rows each TURN owns. A turn row is written as its run ends, after the run's steps,
     * so a step belongs to the first TURN after it in the same session. Steps with no turn after
     * them yet belong to a run still in progress and are left out.
     */
    fun attribute(rows: List<LedgerEntry>): Map<String, List<LedgerEntry>> {
        val out = HashMap<String, List<LedgerEntry>>()
        rows.sortedBy { it.seq }.groupBy { it.sessionId }.values.forEach { session ->
            val pending = ArrayList<LedgerEntry>()
            for (row in session) {
                if (row.kind == LedgerKind.TOOL) {
                    pending += row
                } else {
                    out[row.id] = pending.toList()
                    pending.clear()
                }
            }
        }
        return out
    }

    fun summary(turn: LedgerEntry, steps: List<LedgerEntry>, isSideEffect: (String) -> Boolean): Summary {
        val real = steps.filter { step -> step.actions.none { it.tool in SYNTHETIC_TOOLS } }
        val ran = real.filter { it.outcome != LedgerOutcome.BLOCKED }
        val sideEffectRows = ran.filter { it.outcome == LedgerOutcome.OK && it.actions.any { a -> a.ok && isSideEffect(a.tool) } }
        return Summary(
            toolsRun = ran.size,
            toolsBlocked = real.count { it.outcome == LedgerOutcome.BLOCKED },
            toolErrors = ran.count { it.outcome == LedgerOutcome.ERROR },
            sideEffects = sideEffectRows.size,
            sideEffectTools = sideEffectRows.flatMap { r -> r.actions.map { it.tool } }.filter(isSideEffect).distinct(),
            frames = turn.frames.size + steps.sumOf { it.frames.size },
        )
    }

    /**
     * Whether the home screen may say "I did this for you" about [turn]: a run on the owner's
     * behalf — never one a message from somebody else set off — that changed something, or tried
     * to and could not finish.
     */
    fun actedOnBehalf(turn: LedgerEntry, summary: Summary): Boolean {
        if (turn.kind != LedgerKind.TURN || turn.provenance == "UNTRUSTED") return false
        if (trigger(turn) !in setOf(Trigger.USER, Trigger.APPROVAL, Trigger.REMINDER, Trigger.CRON, Trigger.HEARTBEAT)) return false
        return summary.sideEffects > 0 ||
            (turn.outcome != LedgerOutcome.OK && summary.toolsRun + summary.toolsBlocked > 0)
    }
}
