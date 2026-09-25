package org.ethereumphone.andyclaw.safety

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What an approval card says: exactly what will run, in words, and every parameter as it will be
 * used.
 *
 * The user is approving a call, not a description of it, so nothing is paraphrased away: a
 * recipient and an amount are shown as they will be sent, never redacted or shortened past the
 * point where they can be checked. Only long free text (a message body, a script) is cut, and
 * the card says it was.
 */
object ApprovalSummaries {

    data class Param(
        val key: String,
        val label: String,
        val value: String,
        val truncated: Boolean,
        val length: Int,
    )

    data class Summary(
        val title: String,
        val summary: String,
        val params: List<Param>,
    )

    const val MAX_VALUE = 300

    fun of(toolName: String, input: JsonObject?, maxValue: Int = MAX_VALUE): Summary {
        val params = input.orEmpty().entries
            .sortedBy { (k, _) -> KEY_ORDER.indexOf(k).let { if (it < 0) Int.MAX_VALUE else it } }
            .map { (k, v) -> param(k, v, maxValue) }
        val title = TITLES[toolName] ?: humanize(toolName)
        val lead = params.firstOrNull { it.key in LEAD_KEYS }
        val summary = if (lead != null) "$title — ${lead.label.lowercase()}: ${lead.value.take(80)}" else title
        return Summary(title = title, summary = summary, params = params)
    }

    /** Text for a dialog that has no room for a table: the title, then one line per parameter. */
    fun asText(summary: Summary): String = buildString {
        append(summary.title)
        for (p in summary.params) {
            append("\n").append(p.label).append(": ").append(p.value)
            if (p.truncated) append(" … (${p.length} characters)")
        }
    }

    /** Who asked, in words the owner recognises. */
    fun sourceLabel(source: String, conversationId: String?): String = when (source) {
        "telegram" -> if (conversationId != null) "A Telegram chat ($conversationId)" else "A Telegram chat"
        "xmtp" -> if (conversationId != null) "An XMTP message from ${shortAddress(conversationId)}" else "An XMTP message"
        "cron" -> "A scheduled task"
        "reminder" -> "A reminder"
        "notification" -> "A notification"
        "heartbeat" -> "A background check"
        else -> "A background task"
    }

    /**
     * Whether an owner can judge the call from the card at all. A transaction with calldata is
     * hex nobody can read; approving one is signing blind, so such a card can only be declined.
     */
    fun reviewable(toolName: String, input: String?): Boolean {
        if (toolName != "agent_send_transaction") return true
        val data = runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(input.orEmpty()) as? JsonObject)?.get("data")
        }.getOrNull() ?: return true
        val hex = (data as? JsonPrimitive)?.content?.trim().orEmpty()
        return hex.isEmpty() || hex == "0x" || hex == "0x0"
    }

    private fun param(key: String, value: JsonElement, maxValue: Int = MAX_VALUE): Param {
        val text = when (value) {
            is JsonNull -> "—"
            is JsonPrimitive -> value.content
            else -> PendingApprovalStore.canonical(value)
        }
        // Addresses and amounts are what the user checks; they are short and are never cut.
        val keep = key in NEVER_CUT
        val shown = if (keep || text.length <= maxValue) text else text.take(maxValue)
        return Param(key, LABELS[key] ?: humanize(key), shown, truncated = shown.length < text.length, length = text.length)
    }

    private fun shortAddress(a: String): String =
        if (a.startsWith("0x") && a.length > 12) "${a.take(6)}…${a.takeLast(4)}" else a

    private fun humanize(name: String): String =
        name.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

    private val TITLES = mapOf(
        "agent_send_native_token" to "Send from the agent wallet",
        "agent_send_token" to "Send a token from the agent wallet",
        "agent_transfer_token" to "Send a token from the agent wallet",
        "agent_send_transaction" to "Send a transaction from the agent wallet",
        "agent_swap" to "Swap tokens in the agent wallet",
        "send_xmtp_message" to "Send an XMTP message",
        "send_sms" to "Send a text message",
        "auto_reply_sms" to "Auto-reply to texts",
        "gmail_send" to "Send an email",
        "gmail_reply" to "Reply to an email",
        "reply_to_notification" to "Reply to a notification",
        "make_call" to "Place a phone call",
        "create_cronjob" to "Schedule a recurring task",
        "cancel_cronjob" to "Cancel a recurring task",
        "create_reminder" to "Set a reminder",
        "cancel_reminder" to "Cancel a reminder",
        "memory_store" to "Remember something",
        "memory_delete" to "Forget a memory",
        "run_shell_command" to "Run a shell command",
        "termux_run_command" to "Run a Termux command",
        "execute_code" to "Run code",
        "write_file" to "Write a file",
        "drive_upload" to "Upload to Google Drive",
        "sheets_append" to "Add a row to a spreadsheet",
        "install_app" to "Install an app",
        "uninstall_app" to "Uninstall an app",
        "clear_app_data" to "Clear an app's data",
        "clawhub_install" to "Install a skill",
        "create_custom_tool" to "Create a custom tool",
        "write_secure_setting" to "Change a secure setting",
        "reboot_device" to "Restart the phone",
        "delete_event" to "Delete a calendar event",
        "set_eth_address" to "Change a contact's ETH address",
        "update_soul" to "Rewrite the agent's standing instructions",
        "agent_display_autopilot" to "Do a task in an app",
    )

    /** The parameter that says the most about a call, for the one-line summary. */
    private val LEAD_KEYS = setOf("to", "recipient_address", "recipient", "phone_number", "command", "package_name", "label", "reason")

    private val NEVER_CUT = setOf(
        "to", "recipient", "recipient_address", "address", "phone_number", "amount", "value",
        "token_address", "token", "chain_id", "chainId", "interval_minutes", "time",
    )

    private val KEY_ORDER = listOf(
        "to", "recipient", "recipient_address", "phone_number", "amount", "value", "token", "token_address",
        "chain_id", "chainId", "package_name", "goal", "subject", "message", "body", "text", "command", "code",
        "reason", "label", "interval_minutes", "time",
    )

    private val LABELS = mapOf(
        "to" to "To",
        "recipient" to "Recipient",
        "recipient_address" to "Recipient",
        "phone_number" to "Phone number",
        "amount" to "Amount",
        "value" to "Amount",
        "token" to "Token",
        "token_address" to "Token",
        "chain_id" to "Network",
        "chainId" to "Network",
        "package_name" to "App",
        "goal" to "Goal",
        "subject" to "Subject",
        "message" to "Message",
        "body" to "Message",
        "text" to "Text",
        "command" to "Command",
        "code" to "Code",
        "reason" to "Task",
        "label" to "Label",
        "interval_minutes" to "Every (minutes)",
        "time" to "When",
        "content" to "Content",
        "slug" to "Skill",
    )
}
