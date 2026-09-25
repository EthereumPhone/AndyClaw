package org.ethereumphone.andyclaw.agent

/**
 * Apps the agent itself just did something in.
 *
 * A notification such an app posts is most likely the echo of that action — the message the
 * agent sent to the user, the app it drove on the agent display — and waking the agent for it
 * would have it chase its own tail.
 */
object RecentAgentActions {

    private const val WINDOW_MS = 5 * 60_000L

    /** Tools whose effect shows up as another app's notification. */
    private val TOOL_PACKAGES = mapOf(
        "send_message_to_user" to "org.ethereumhpone.messenger",
        "send_xmtp_message" to "org.ethereumhpone.messenger",
        "gmail_send" to "com.google.android.gm",
        "gmail_reply" to "com.google.android.gm",
        "send_telegram_message" to "org.telegram.messenger",
    )

    private val lastAction = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun notePackage(packageName: String, now: Long = System.currentTimeMillis()) {
        lastAction[packageName] = now
        if (lastAction.size > 64) lastAction.entries.removeIf { now - it.value > WINDOW_MS }
    }

    fun noteTool(toolName: String, now: Long = System.currentTimeMillis()) {
        TOOL_PACKAGES[toolName]?.let { notePackage(it, now) }
    }

    fun actedOnRecently(packageName: String, now: Long = System.currentTimeMillis()): Boolean =
        lastAction[packageName]?.let { now - it < WINDOW_MS } == true
}
