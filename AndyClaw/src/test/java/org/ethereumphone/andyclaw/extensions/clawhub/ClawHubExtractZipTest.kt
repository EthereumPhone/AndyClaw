package org.ethereumphone.andyclaw.extensions.clawhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ClawHubExtractZipTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun assertRefused(block: () -> Unit) {
        try {
            block()
            fail("expected SecurityException")
        } catch (_: SecurityException) {
        }
    }

    @Test
    fun `a normal bundle extracts`() {
        val target = File(tmp.root, "foo")
        val n = ClawHubApi.extractZipStream(
            zip("SKILL.md" to "hi".toByteArray(), "scripts/run.sh" to "echo".toByteArray()),
            target,
        )
        assertEquals(2, n)
        assertTrue(File(target, "scripts/run.sh").isFile)
    }

    @Test
    fun `an entry into a sibling sharing the prefix is refused`() {
        val target = File(tmp.root, "foo").apply { mkdirs() }
        assertRefused {
            ClawHubApi.extractZipStream(zip("../foo-bar/x" to "p".toByteArray()), target)
        }
        assertFalse(File(tmp.root, "foo-bar/x").exists())
    }

    @Test
    fun `classic zip-slip is refused`() {
        val target = File(tmp.root, "foo").apply { mkdirs() }
        assertRefused {
            ClawHubApi.extractZipStream(zip("../../evil" to "p".toByteArray()), target)
        }
    }

    @Test
    fun `a bundle that inflates past the cap is refused`() {
        val target = File(tmp.root, "foo")
        assertRefused {
            ClawHubApi.extractZipStream(
                zip("SKILL.md" to ByteArray(4096)),
                target,
                maxBytes = 1024,
            )
        }
    }

    @Test
    fun `a bundle with too many entries is refused`() {
        val target = File(tmp.root, "foo")
        val entries = (1..11).map { "f$it" to byteArrayOf(1) }.toTypedArray()
        assertRefused { ClawHubApi.extractZipStream(zip(*entries), target, maxEntries = 10) }
    }
}
