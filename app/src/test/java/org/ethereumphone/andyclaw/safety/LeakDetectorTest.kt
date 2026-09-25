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
}
