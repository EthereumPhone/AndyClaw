package org.ethereumphone.andyclaw.heartbeat

import android.util.Log
import java.io.File

object HeartbeatInstructions {

    private const val TAG = "HeartbeatInstructions"

    /** Seeded into HEARTBEAT.md — only contains a header so isContentEffectivelyEmpty() returns true
     *  until the user adds their own periodic tasks. */
    const val CONTENT = """# Periodic Tasks

"""

    /**
     * The task list a fresh device starts with.
     *
     * Out of the box the proactive agent used to do nothing at all, for two
     * independent reasons: the heartbeat interval defaults to `-1` (off), and
     * [CONTENT] seeds HEARTBEAT.md with a bare header that
     * `HeartbeatPrompt.isContentEffectivelyEmpty` reads as "nothing to do" — so even
     * turning the interval on left the agent with an empty list.
     *
     * Onboarding writes this instead. Deliberately short: everything here is re-sent
     * on every heartbeat, and `user_funds.usd_balance` is shared with sponsored gas,
     * so a chatty task list costs the user transactions.
     *
     * Read-only tasks only. Nothing here spends, sends, or installs.
     */
    const val STARTER_CONTENT = """# Periodic Tasks

Everything in this list runs on every heartbeat, so keep it short.

- Check notifications for anything time-critical — a delivery, a flight change, a
  payment request — and tell me about it once. Do not repeat something you already
  reported.
- Check the device for a battery below 15% or free storage below 2 GB.
- If nothing needs my attention, reply HEARTBEAT_OK and do nothing else.

## Notes

- Add your own tasks as list items. They run on the schedule set in
  Settings -> Heartbeat.
- Delete a task to stop it. An empty list means the heartbeat does nothing at all.
"""

    /**
     * Put the starter task list in place unless the user has written their own.
     *
     * Safe to call repeatedly and safe to call after the OS has already seeded the
     * placeholder: it only writes when the file is missing or still effectively
     * empty, so a task list the user edited is never overwritten.
     *
     * @return true when the file was written.
     */
    fun seedStarterTasks(file: File): Boolean {
        return try {
            val existing = if (file.exists()) file.readText() else null
            if (existing != null && !HeartbeatPrompt.isContentEffectivelyEmpty(existing)) {
                Log.i(TAG, "HEARTBEAT.md already has tasks — leaving it alone")
                return false
            }
            file.writeText(STARTER_CONTENT)
            Log.i(TAG, "Seeded HEARTBEAT.md with the starter task list")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not seed HEARTBEAT.md: ${e.message}")
            false
        }
    }

    /** Previous proactive instructions kept for reference — not seeded into HEARTBEAT.md. */
    @Suppress("unused")
    const val LEGACY_PROACTIVE_CONTENT = """# Heartbeat Instructions

You are running as a background heartbeat on the user's dGEN1 phone.
Every hour, you wake up and decide if there's something useful to do.

## Available Tools

You have access to all tools listed in your tool definitions. Use them proactively.
Your tools include device controls (WiFi, Bluetooth, mobile data, airplane mode, DND, audio, etc.),
file operations, contacts, SMS, notifications, calendar, phone, wallet, and more.

Use `heartbeat_journal.md` as your persistent memory between heartbeats.

## Action-First Rule

**ALWAYS prefer taking action over sending alerts.**
If something needs to happen and you have a tool for it — DO IT, don't just tell the user about it.
All tool approvals are automatically granted in heartbeat mode.

Examples:
- Bluetooth should be on → call `toggle_bluetooth` with `enabled: true` — do NOT just notify the user
- DND should be enabled → call `set_dnd_mode` — do NOT just notify the user
- WiFi should be off → call `toggle_wifi` with `enabled: false` — do NOT just notify the user

Only alert the user (via `send_message_to_user`) when:
- You cannot fix the issue yourself (no tool available)
- The situation requires a human decision
- You have genuinely useful information to share (price alert, new SMS, etc.)

## Steps

1. Read `heartbeat_journal.md` to see what you've done recently (use read_file)
2. Gather fresh info the user might care about:
   - Check portfolio with `get_owned_tokens` — note any significant price changes
   - Check `get_device_info` for battery/storage warnings
   - Check `read_sms` for any unread messages
   - Check `list_notifications` for anything important
   - Check `get_connectivity_status` for WiFi/Bluetooth/mobile data state
3. Decide: does anything need to be DONE or TOLD to the user?
4. If something needs doing and you have a tool for it — **do it immediately**
5. If you have useful info the user needs to know — send via `send_message_to_user`
6. Write a short log entry to `heartbeat_journal.md` noting what you checked, what you found, and what actions you took
7. If nothing needs attention, reply with just: HEARTBEAT_OK

## Rules
- **Act first, alert second** — if you can fix it, fix it
- Do NOT repeat a **successful** action you already logged in the journal within the last 24 hours
- **ALWAYS retry failed actions** — previous failures may have been due to temporary issues (permissions, connectivity, etc.) that are now resolved
- Keep XMTP messages short and useful — no fluff
- Only message the user if you have genuine value to share or cannot resolve something yourself
- If the journal doesn't exist yet, create it with your first entry
- Be resourceful — use multiple tools to build a full picture before deciding what to do"""
}
