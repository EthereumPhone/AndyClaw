package org.ethereumphone.andyclaw.ingest

/**
 * Whether a mail really comes from the domain it says it does.
 *
 * Anyone can write `From: Lufthansa <noreply@lufthansa.com>` and put reservation markup in the
 * body; a card built from that is a way to put a fake "gate change" on someone's home screen.
 * What a sender cannot forge is a DKIM signature by `lufthansa.com`, and the receiving server
 * has already checked it and written the verdict into `Authentication-Results`.
 *
 * Only the **topmost** `Authentication-Results` header counts, and only if the receiving
 * server wrote it (`mx.google.com` for Gmail): a sender can add any headers it likes further
 * down, a forged "dkim=pass" among them. The verdict counts when:
 *
 * - `dmarc=pass` — the server has already checked the From domain is authenticated; or
 * - `dkim=pass` with a signing domain aligned to the From domain (equal, or one a subdomain of
 *   the other: DMARC's relaxed alignment — `lufthansa.com` signs for `mail.lufthansa.com`).
 *
 * Pure, and deliberately strict: anything it cannot read counts as unauthenticated. Unsigned
 * mail still makes cards; it just can never overwrite what signed mail said.
 */
object MailAuthentication {

    /** The receiving servers whose verdicts are trusted — the mailbox this reads is Gmail. */
    private val TRUSTED_AUTHSERV_IDS = setOf("mx.google.com")

    /**
     * Free mailbox providers. Their signature proves the mail came from an account there, which
     * anyone can open in a minute: it says nothing about being an airline or a hotel, and counted
     * as proof it let a stranger's gmail.com mail rewrite or cancel a real booking's card.
     */
    private val WEBMAIL_DOMAINS = setOf(
        "gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "live.com", "msn.com",
        "yahoo.com", "ymail.com", "aol.com", "icloud.com", "me.com", "mac.com",
        "proton.me", "protonmail.com", "pm.me", "gmx.com", "gmx.de", "gmx.net", "web.de",
        "mail.com", "yandex.com", "yandex.ru", "mail.ru", "zoho.com", "fastmail.com", "tutanota.com",
    )

    /** Second-level labels that are a webmail provider under any national suffix (yahoo.co.uk). */
    private val WEBMAIL_LABELS = setOf("yahoo", "hotmail", "outlook", "live", "gmx", "yandex")

    /**
     * [headers] in the order the message carries them, top first, as (name, value) pairs.
     */
    fun isAuthenticated(headers: List<Pair<String, String>>, from: String?): Boolean {
        val fromDomain = domainOf(from) ?: return false
        if (isWebmail(fromDomain)) return false
        val results = headers.firstOrNull { it.first.equals("Authentication-Results", ignoreCase = true) }?.second
            ?: return false
        val sections = results.split(';').map { it.trim() }
        val authserv = sections.firstOrNull()?.substringBefore(' ')?.lowercase() ?: return false
        if (authserv !in TRUSTED_AUTHSERV_IDS) return false

        for (section in sections.drop(1)) {
            val method = section.substringBefore('=').trim().lowercase()
            val result = section.substringAfter('=', "").trim().substringBefore(' ').substringBefore('(').lowercase()
            if (result != "pass") continue
            when (method) {
                "dmarc" -> {
                    val headerFrom = property(section, "header.from")?.lowercase()
                    if (headerFrom == null || aligned(headerFrom, fromDomain)) return true
                }
                "dkim" -> {
                    val signer = property(section, "header.d")?.lowercase()
                        ?: property(section, "header.i")?.substringAfter('@')?.lowercase()
                    if (signer != null && aligned(signer, fromDomain)) return true
                }
            }
        }
        return false
    }

    /** `header.i=@lufthansa.com` → `@lufthansa.com`. */
    private fun property(section: String, name: String): String? {
        val at = section.indexOf("$name=", ignoreCase = true)
        if (at < 0) return null
        return section.substring(at + name.length + 1)
            .takeWhile { !it.isWhitespace() && it != ';' }
            .trim('"')
            .takeIf { it.isNotEmpty() }
    }

    private fun aligned(signer: String, fromDomain: String): Boolean {
        val s = signer.trimEnd('.')
        val f = fromDomain.trimEnd('.')
        // A bare public suffix cannot hold a DKIM key, so "com" never signs for anyone here.
        if (!s.contains('.')) return false
        return s == f || f.endsWith(".$s") || s.endsWith(".$f")
    }

    /** [domain] is a free mailbox provider's: listed, or `yahoo.co.uk`, `hotmail.fr` and the like. */
    private fun isWebmail(domain: String): Boolean {
        if (WEBMAIL_DOMAINS.any { domain == it || domain.endsWith(".$it") }) return true
        val labels = domain.split('.')
        if (labels.first() !in WEBMAIL_LABELS) return false
        return labels.size == 2 || (labels.size == 3 && labels[1] in setOf("co", "com"))
    }

    /** `Lufthansa <noreply@lufthansa.com>` → `lufthansa.com`. */
    fun domainOf(from: String?): String? {
        val raw = from?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val address = raw.substringAfterLast('<', raw).substringBefore('>').trim()
        val domain = address.substringAfterLast('@', "").trim().trimEnd('.').lowercase()
        return domain.takeIf { it.isNotEmpty() && it.contains('.') }
    }
}
