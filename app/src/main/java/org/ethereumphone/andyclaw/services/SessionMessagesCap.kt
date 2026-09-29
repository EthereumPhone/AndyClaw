package org.ethereumphone.andyclaw.services

/**
 * Bounds what `ILauncherService.getSessionMessages` returns.
 *
 * The whole session went back as one string. A binder transaction is capped at 1 MB for the whole
 * process, a Parcel string costs two bytes a character, and a long session with a few big TOOL
 * rows (a page dump, an accessibility tree) went over it: the call failed and the launcher showed
 * an empty conversation. So: the newest [maxRows] rows, each cut to its own cap, within a total
 * character budget — contiguous from the newest, so what is shown has no holes.
 *
 * The shape stays the launcher's: a JSON array of `{role, content, timestamp}`. What is new is
 * additive and optional — `truncated: true` on a row whose content was cut, and
 * `omittedBefore: n` on the oldest row returned when older rows were left out. An older launcher
 * ignores both.
 */
object SessionMessagesCap {
    const val MAX_ROWS = 300
    const val MAX_CHARS_PER_ROW = 16_000
    /** Tool rows are not even shown by the launcher's restored view; they only need a trace. */
    const val MAX_CHARS_PER_TOOL_ROW = 2_000
    /**
     * ~240 KB as UTF-16, and under 500 KB even if JSON escaping doubled every character (a tool
     * row full of quotes): the rest of the process's binder buffer is left for everyone else.
     */
    const val MAX_TOTAL_CHARS = 120_000

    data class Row(val role: String, val content: String, val timestamp: Long)

    /**
     * The roles the launcher renders: what was said. Tool results it discards, and a compaction
     * summary or a system row it showed as the agent speaking (CHAT-12); left out, the budget
     * goes to the conversation instead. Nothing but the launcher reads this reply.
     */
    val LAUNCHER_ROLES = setOf("user", "assistant")

    /** [cap] over the rows the launcher shows. `omittedBefore` then counts only those. */
    fun capForLauncher(rows: List<Row>): List<Out> = cap(rows.filter { it.role in LAUNCHER_ROLES })

    data class Out(
        val role: String,
        val content: String,
        val timestamp: Long,
        val truncated: Boolean,
        /** Set on the first row returned: how many older rows were left out. */
        val omittedBefore: Int,
    )

    fun cap(
        rows: List<Row>,
        maxRows: Int = MAX_ROWS,
        maxCharsPerRow: Int = MAX_CHARS_PER_ROW,
        maxCharsPerToolRow: Int = MAX_CHARS_PER_TOOL_ROW,
        maxTotalChars: Int = MAX_TOTAL_CHARS,
    ): List<Out> {
        val kept = ArrayList<Out>()
        var total = 0
        for (i in rows.indices.reversed()) {
            if (kept.size >= maxRows) break
            val r = rows[i]
            val cap = if (r.role == "tool") maxCharsPerToolRow else maxCharsPerRow
            val cut = r.content.length > cap
            val content = if (cut) r.content.take(cap) else r.content
            if (total + content.length > maxTotalChars) break
            total += content.length
            kept += Out(r.role, content, r.timestamp, cut, 0)
        }
        kept.reverse()
        val omitted = rows.size - kept.size
        if (omitted > 0 && kept.isNotEmpty()) kept[0] = kept[0].copy(omittedBefore = omitted)
        return kept
    }
}
