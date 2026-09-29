package org.ethereumphone.andyclaw.extensions.clawhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SafePathsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `real ClawHub slugs are accepted`() {
        for (slug in listOf("gog", "self-improving-agent", "calendar-skill", "a", "x1", "my_skill", "v2.0", "Github")) {
            assertTrue(slug, SafePaths.isValidClawHubSlug(slug))
        }
    }

    @Test
    fun `traversal and separator slugs are refused`() {
        for (slug in listOf("", ".", "..", ".clawhub", "../x", "a/b", "a\\b", "-rf", "_x", "a b", "a\u0000", "x".repeat(129))) {
            assertFalse(slug, SafePaths.isValidClawHubSlug(slug))
        }
    }

    @Test
    fun `childOf refuses dot names and separators`() {
        val parent = tmp.newFolder("skills")
        assertNull(SafePaths.childOf(parent, ".."))
        assertNull(SafePaths.childOf(parent, "."))
        assertNull(SafePaths.childOf(parent, ""))
        assertNull(SafePaths.childOf(parent, "a/b"))
        assertNull(SafePaths.childOf(parent, "../skills"))
        assertEquals(File(parent.canonicalFile, "ok"), SafePaths.childOf(parent, "ok"))
    }

    @Test
    fun `childOf refuses a symlink that leaves the parent`() {
        val parent = tmp.newFolder("skills")
        val outside = tmp.newFolder("outside")
        Files.createSymbolicLink(File(parent, "link").toPath(), outside.toPath())
        assertNull(SafePaths.childOf(parent, "link"))
    }

    @Test
    fun `resolveInside requires strictly inside, with a separator`() {
        val root = tmp.newFolder("foo")
        tmp.newFolder("foo-bar")
        assertNotNull(SafePaths.resolveInside(root, "a/b.txt"))
        assertNull(SafePaths.resolveInside(root, "../foo-bar/x"))
        assertNull(SafePaths.resolveInside(root, "../../etc/passwd"))
        assertNull(SafePaths.resolveInside(root, "."))
        assertNull(SafePaths.resolveInside(root, ""))
        assertNull(SafePaths.resolveInside(root, "a/../.."))
        assertNotNull(SafePaths.resolveInside(root, "a/../b"))
    }

    @Test
    fun `shellQuote survives single quotes`() {
        assertEquals("'abc'", SafePaths.shellQuote("abc"))
        assertEquals("'a'\\''b'", SafePaths.shellQuote("a'b"))
        assertEquals("''", SafePaths.shellQuote(""))
    }

    @Test
    fun `shellQuote round-trips through a real shell`() {
        val sh = File("/bin/sh")
        if (!sh.canExecute()) return
        val nasty = "x'; echo injected; echo '\$HOME `id` \"q\" \\n"
        val p = ProcessBuilder("/bin/sh", "-c", "printf '%s' ${SafePaths.shellQuote(nasty)}")
            .redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        p.waitFor()
        assertEquals(nasty, out)
    }
}
