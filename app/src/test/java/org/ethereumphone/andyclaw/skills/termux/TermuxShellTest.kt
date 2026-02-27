package org.ethereumphone.andyclaw.skills.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TermuxShellTest {

    @Test
    fun `quote escapes single quotes for POSIX shell`() {
        val quoted = TermuxShell.quote("ab'cd")
        assertEquals("'ab'\\''cd'", quoted)
    }

    @Test
    fun `validateSlug accepts mixed case and spaces for compatibility`() {
        assertEquals("weather-bot_2", TermuxShell.validateSlug("weather-bot_2"))
        assertEquals("Weather Bot V2", TermuxShell.validateSlug("Weather Bot V2"))
    }

    @Test
    fun `validateSlug rejects traversal separators and control chars`() {
        expectIllegal { TermuxShell.validateSlug("../evil") }
        expectIllegal { TermuxShell.validateSlug("bad/slug") }
        expectIllegal { TermuxShell.validateSlug("bad\\slug") }
        expectIllegal { TermuxShell.validateSlug("..") }
        expectIllegal { TermuxShell.validateSlug("bad\u0000slug") }
        expectIllegal { TermuxShell.validateSlug("bad\nslug") }
        expectIllegal { TermuxShell.validateSlug("bad\rslug") }
    }

    @Test
    fun `validateRelativePath normalises separators and allows compatible chars`() {
        val path1 = TermuxShell.validateRelativePath("scripts\\setup.sh")
        assertEquals("scripts/setup.sh", path1)

        val path2 = TermuxShell.validateRelativePath("scripts/My setup (v1).sh")
        assertEquals("scripts/My setup (v1).sh", path2)
    }

    @Test
    fun `validateRelativePath rejects absolute traversal and control chars`() {
        expectIllegal { TermuxShell.validateRelativePath("/tmp/setup.sh") }
        expectIllegal { TermuxShell.validateRelativePath("scripts/../setup.sh") }
        expectIllegal { TermuxShell.validateRelativePath("./setup.sh") }
        expectIllegal { TermuxShell.validateRelativePath("scripts/ok\u0000.sh") }
    }

    @Test
    fun `validateBinName accepts safe package names`() {
        val bin = TermuxShell.validateBinName("python3")
        assertEquals("python3", bin)
    }

    @Test
    fun `validateBinName rejects injection characters`() {
        expectIllegal { TermuxShell.validateBinName("python3;rm") }
        expectIllegal { TermuxShell.validateBinName("curl | sh") }
        expectIllegal { TermuxShell.validateBinName("bad name") }
    }

    private fun expectIllegal(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
