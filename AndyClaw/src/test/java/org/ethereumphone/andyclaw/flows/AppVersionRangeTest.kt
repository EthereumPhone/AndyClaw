package org.ethereumphone.andyclaw.flows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionRangeTest {

    @Test
    fun `the design doc range behaves as written`() {
        val range = ">=7.2,<8.0"
        assertTrue(AppVersionRange.contains(range, "7.2"))
        assertTrue(AppVersionRange.contains(range, "7.2.1"))
        assertTrue(AppVersionRange.contains(range, "7.9.12"))
        assertFalse(AppVersionRange.contains(range, "7.1.9"))
        assertFalse(AppVersionRange.contains(range, "8.0"))
        assertFalse(AppVersionRange.contains(range, "8.0.1"))
    }

    @Test
    fun `a build suffix does not disqualify a version`() {
        // Android versionNames are 7.2.1, 7.2.1-beta, "7.2.1 (4471)" and worse.
        assertTrue(AppVersionRange.contains(">=7.2,<8", "7.2.1-beta"))
        assertTrue(AppVersionRange.contains(">=7.2,<8", "7.2.1 (4471)"))
    }

    @Test
    fun `star matches anything installed but not a missing app`() {
        assertTrue(AppVersionRange.contains("*", "1.0"))
        assertFalse(AppVersionRange.contains("*", null))
    }

    @Test
    fun `an unparseable range matches nothing`() {
        assertFalse(AppVersionRange.isParseable("latest"))
        assertFalse(AppVersionRange.contains("latest", "7.2"))
    }

    @Test
    fun `component comparison is numeric not lexical`() {
        assertTrue(AppVersionRange.compare("7.10", "7.9") > 0)
        assertTrue(AppVersionRange.compare("7.2", "7.2.0") == 0)
    }

    @Test
    fun `a suggested range only ever uses the leading version number`() {
        assertEquals(">=7.2.1,<8", AppVersionRange.suggestedFor("7.2.1"))
        assertEquals(">=2024.07.14.123456789,<2025", AppVersionRange.suggestedFor("2024.07.14.123456789.Release"))
        assertEquals(">=7.2.1,<8", AppVersionRange.suggestedFor("7.2.1 (4471)"))
        assertEquals("*", AppVersionRange.suggestedFor("beta"))
        assertEquals(null, AppVersionRange.suggestedFor(null))
        for (v in listOf("7.2.1", "2024.07.14.123456789.Release", "1.0-beta3", "beta")) {
            val range = AppVersionRange.suggestedFor(v)!!
            assertTrue("$range must parse", AppVersionRange.isParseable(range))
            assertTrue("$v must satisfy its own range $range", AppVersionRange.contains(range, v))
        }
    }
}
