package org.ethereumphone.andyclaw.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeakDetectorTest {

    private val detector = LeakDetector()

    @Test
    fun `patterns whose prefix has a capital letter are checked too`() {
        for ((name, secret) in listOf(
            "aws_access_key" to "AKIAABCDEFGHIJKLMNOP",
            "pem_private_key" to "-----BEGIN RSA PRIVATE KEY-----",
            "google_api_key" to "AIza" + "a".repeat(35),
            "bearer_token" to "Bearer " + "x".repeat(24),
        )) {
            val scan = detector.scan("here: $secret")
            assertTrue("$name was not found", scan.matches.any { it.patternName == name })
        }
    }

    @Test
    fun `a lower-case prefix still works, and plain text is left alone`() {
        assertTrue(detector.scan("sk-" + "a".repeat(30)).shouldBlock)
        assertFalse(detector.scan("meet me at 5 by the SKATE park").shouldBlock)
        assertEquals(0, detector.scan("nothing to see here").matches.size)
    }

    // ── Wallet key material ─────────────────────────────────────────

    private val hex64 = "4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318"

    @Test
    fun `a private key is key material with or without 0x`() {
        assertTrue(LeakDetector.holdsKeyMaterial("my key is 0x$hex64 thanks"))
        assertTrue(LeakDetector.holdsKeyMaterial("""{"message":"$hex64"}"""))
        assertTrue(LeakDetector.holdsKeyMaterial("""{"message":"key:\n0x$hex64"}"""))
    }

    @Test
    fun `an address, calldata and a signature are not a key`() {
        assertFalse(LeakDetector.holdsKeyMaterial("send to 0xdeadbeef00000000000000000000000000000002"))
        assertFalse(LeakDetector.holdsKeyMaterial("0xa9059cbb" + "0".repeat(24) + "deadbeef".repeat(5) + "1".repeat(64)))
        assertFalse(LeakDetector.holdsKeyMaterial("0x" + "ab".repeat(65)))
    }

    @Test
    fun `a recovery phrase is key material however it is pasted`() {
        val twelve = "abandon ability able about above absent absorb abstract absurd abuse access accident"
        assertTrue(LeakDetector.containsRecoveryPhrase(twelve))
        assertTrue(LeakDetector.containsRecoveryPhrase("seed: ${twelve.uppercase()} ok"))
        assertTrue(LeakDetector.containsRecoveryPhrase(twelve.split(" ").mapIndexed { i, w -> "${i + 1}. $w" }.joinToString("\n")))
        assertTrue(LeakDetector.holdsKeyMaterial("""{"message":"${twelve.replace(" ", "\\n")}"}"""))
        // Eleven words is not a phrase.
        assertFalse(LeakDetector.containsRecoveryPhrase(twelve.substringBeforeLast(" ")))
    }

    @Test
    fun `ordinary sentences are not a recovery phrase`() {
        for (text in listOf(
            "hey mate could you grab some milk bread eggs cheese butter apples from the store",
            "Please send the report to the whole team before the meeting tomorrow morning at nine",
            "remind me to call mom about the birthday dinner plans for next weekend at the lake house",
            "Great news, the package arrived early and everything inside looks perfect, thanks again for your help",
        )) {
            assertFalse(text, LeakDetector.containsRecoveryPhrase(text))
        }
    }

    @Test
    fun `key material is blanked out and the words around it stay`() {
        val twelve = "abandon ability able about above absent absorb abstract absurd abuse access accident"
        assertEquals("import [REDACTED] ok?", LeakDetector.redactKeyMaterial("import $twelve ok?"))
        assertEquals("import [REDACTED] ok", LeakDetector.redactKeyMaterial("import 0x$hex64 ok"))
        assertEquals(
            "a [REDACTED] b [REDACTED]",
            LeakDetector.redactKeyMaterial("a ${twelve.replace(" ", "\\n")} b $hex64"),
        )
        val ordinary = "send 0.1 ETH to 0xdeadbeef00000000000000000000000000000002 and call mom"
        assertEquals(ordinary, LeakDetector.redactKeyMaterial(ordinary))
        // Eleven words is not a phrase, so nothing goes.
        val eleven = twelve.substringBeforeLast(" ")
        assertEquals(eleven, LeakDetector.redactKeyMaterial(eleven))
    }

    @Test
    fun `the embedded wordlist is the canonical BIP-39 English list`() {
        val words = Bip39English.ORDERED
        assertEquals(2048, words.size)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((words.joinToString("\n") + "\n").toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda", digest)
    }
}
