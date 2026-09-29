package org.ethereumphone.andyclaw.skills.builtin

/**
 * Ids for reminders and cron jobs.
 *
 * They used to be `now % Int.MAX_VALUE`, so two jobs created in the same millisecond — a
 * parallel tool batch does exactly that — got the same id, and the OS (which treats a known id
 * as "replace") silently overwrote the first, while the provenance entry of one was attached to
 * the other. The OS reads the id with `getIntExtra(…, 0)` and treats 0 as missing, so the id
 * stays a positive Int: time-based as before (readable, roughly ordered), but strictly
 * increasing within the process and never one already in use.
 */
internal object TriggerIds {
    private var last = 0

    @Synchronized
    fun next(nowMs: Long, taken: (Int) -> Boolean): Int {
        var c = (nowMs % Int.MAX_VALUE).toInt()
        if (c <= last) c = if (last == Int.MAX_VALUE) 1 else last + 1
        while (c <= 0 || taken(c)) c = if (c == Int.MAX_VALUE) 1 else c + 1
        last = c
        return c
    }
}
