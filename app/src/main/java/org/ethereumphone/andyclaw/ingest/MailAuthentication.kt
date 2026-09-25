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
     * [headers] in the order the message carries them, top first, as (name, value) pairs.
     */
    fun isAuthenticated(headers: List<Pair<String, String>>, from: String?): Boolean {
        val fromDomain = domainOf(from) ?: return false
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

    /** `Lufthansa <noreply@lufthansa.com>` → `lufthansa.com`. */
    fun domainOf(from: String?): String? {
        val raw = from?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val address = raw.substringAfterLast('<', raw).substringBefore('>').trim()
        val domain = address.substringAfterLast('@', "").trim().trimEnd('.').lowercase()
        return domain.takeIf { it.isNotEmpty() && it.contains('.') }
    }
}
