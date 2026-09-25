package org.ethereumphone.andyclaw.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only mail the domain really signed may put facts on the home screen. */
class MailAuthenticationTest {

    private val from = "Lufthansa <noreply@lufthansa.com>"

    private fun headers(vararg results: String) = results.map { "Authentication-Results" to it }

    @Test
    fun `a DKIM pass by the From domain is authentic`() {
        assertTrue(MailAuthentication.isAuthenticated(
            headers("mx.google.com; dkim=pass header.i=@lufthansa.com header.s=s1 header.b=abc; spf=pass smtp.mailfrom=x@lufthansa.com"),
            from,
        ))
    }

    @Test
    fun `a parent domain's signature aligns with a subdomain sender`() {
        assertTrue(MailAuthentication.isAuthenticated(
            headers("mx.google.com; dkim=pass header.d=lufthansa.com"),
            "noreply@mail.lufthansa.com",
        ))
    }

    @Test
    fun `a DMARC pass is enough`() {
        assertTrue(MailAuthentication.isAuthenticated(
            headers("mx.google.com; dmarc=pass (p=REJECT sp=REJECT dis=NONE) header.from=lufthansa.com"),
            from,
        ))
    }

    @Test
    fun `a signature by some other domain is not`() {
        assertFalse(MailAuthentication.isAuthenticated(
            headers("mx.google.com; dkim=pass header.i=@spammer.example; spf=pass"),
            from,
        ))
    }

    @Test
    fun `a failed signature is not`() {
        assertFalse(MailAuthentication.isAuthenticated(headers("mx.google.com; dkim=fail header.i=@lufthansa.com"), from))
    }

    @Test
    fun `a forged verdict below the server's own is ignored`() {
        // The sender can write any header it likes; only the topmost, from Gmail, counts.
        assertFalse(MailAuthentication.isAuthenticated(
            headers(
                "mx.google.com; dkim=none; spf=softfail",
                "mx.google.com; dkim=pass header.i=@lufthansa.com",
            ),
            from,
        ))
    }

    @Test
    fun `a verdict from a server we do not trust is ignored`() {
        assertFalse(MailAuthentication.isAuthenticated(headers("evil.example; dkim=pass header.i=@lufthansa.com"), from))
    }

    @Test
    fun `no verdict at all is not authentic`() {
        assertFalse(MailAuthentication.isAuthenticated(emptyList(), from))
    }

    @Test
    fun `the From domain is read out of a display name`() {
        assertEquals("lufthansa.com", MailAuthentication.domainOf(from))
        assertEquals("x.co.uk", MailAuthentication.domainOf("a@x.co.uk"))
    }
}
