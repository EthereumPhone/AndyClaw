package org.ethereumphone.andyclaw.agent

import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.ledger.LedgerDigest

/**
 * What a background run is recorded as in the ledger: a label, never the prompt.
 *
 * The prompt of a background run is built around somebody else's words — an XMTP body, a
 * Telegram message, the label a stranger gave a reminder — and the ledger is exported and shown on
 * the home screen as "I did this for you". So the ledger gets the kind of trigger and, where the
 * owner wrote it, the name they gave it.
 */
object BackgroundIntent {

    fun label(trigger: BackgroundTrigger, provenance: Provenance, prompt: String, conversationId: String?): String {
        // A reminder's or a job's label is the owner's words only if the owner made it.
        val ownLabel = field(prompt, "Label")?.takeIf { provenance != Provenance.UNTRUSTED }
        return when (trigger) {
            BackgroundTrigger.REMINDER -> ownLabel?.let { "${LedgerDigest.LABEL_REMINDER}: $it" } ?: LedgerDigest.LABEL_REMINDER
            BackgroundTrigger.CRON -> ownLabel?.let { "${LedgerDigest.LABEL_CRON}: $it" } ?: LedgerDigest.LABEL_CRON
            BackgroundTrigger.XMTP -> "${LedgerDigest.LABEL_XMTP} from ${shortAddress(conversationId)}"
            BackgroundTrigger.HEARTBEAT ->
                if (provenance == Provenance.UNTRUSTED) LedgerDigest.LABEL_HEARTBEAT_NOTIFICATION else LedgerDigest.LABEL_HEARTBEAT
        }
    }

    fun telegram(ownerChat: Boolean): String =
        if (ownerChat) LedgerDigest.LABEL_TELEGRAM_OWNER else LedgerDigest.LABEL_TELEGRAM_OTHER

    private fun field(prompt: String, name: String): String? =
        Regex("^- ${Regex.escape(name)}: (.+)$", RegexOption.MULTILINE).find(prompt)
            ?.groupValues?.get(1)?.trim()?.take(MAX_LABEL)?.ifBlank { null }

    private fun shortAddress(a: String?): String = when {
        a == null -> "someone"
        a.startsWith("0x") && a.length > 12 -> "${a.take(6)}…${a.takeLast(4)}"
        else -> a.take(MAX_LABEL)
    }

    private const val MAX_LABEL = 60
}
