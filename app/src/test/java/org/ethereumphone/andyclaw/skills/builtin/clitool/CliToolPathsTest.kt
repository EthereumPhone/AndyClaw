package org.ethereumphone.andyclaw.skills.builtin.clitool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CliToolPathsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun entry(id: String) = CliToolEntry(id = id, name = id, sourceType = "local", sourceValue = "/x")

    @Test
    fun `new ids are plain words`() {
        for (id in listOf("opensea", "gws", "my-tool", "tool_2")) assertTrue(id, CliToolRegistry.isValidNewId(id))
        for (id in listOf("", ".", "..", "../x", "a/b", "a.b", "-x", "A", "a b", "a;b")) {
            assertFalse(id, CliToolRegistry.isValidNewId(id))
        }
    }

    @Test
    fun `registry refuses to add a traversal id`() {
        val reg = CliToolRegistry(tmp.newFolder("cli-tools"))
        try {
            reg.add(entry(".."))
            throw AssertionError("add accepted ..")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `removing a legacy dot-dot entry deletes only its row`() {
        val files = tmp.newFolder("files")
        val base = File(files, "cli-tools").apply { mkdirs() }
        val keep = File(files, "pending_approvals.json").apply { writeText("[]") }
        // An entry written before ids were validated.
        File(base, "registry.json").writeText("""[{"id":"..","name":"x","sourceType":"local","sourceValue":"/x"}]""")
        val reg = CliToolRegistry(base)
        assertNull(reg.getToolDir(".."))
        reg.remove("..")
        assertTrue(keep.isFile)
        assertTrue(reg.getAll().isEmpty())
    }

    @Test
    fun `doc reads and writes stay in the tool dir`() {
        val files = tmp.newFolder("files")
        val base = File(files, "cli-tools")
        File(files, "secret.xml").writeText("secret")
        val reg = CliToolRegistry(base)
        reg.add(entry("opensea"))
        assertTrue(reg.saveSkillMd("opensea", "docs/SKILL.md", "hello"))
        assertEquals("hello", reg.getSkillMdContent("opensea", "docs/SKILL.md"))
        assertNull(reg.getSkillMdContent("opensea", "../../secret.xml"))
        assertNull(reg.getSkillMdContent("opensea", "../registry.json"))
        assertFalse(reg.saveSkillMd("opensea", "../../evil.txt", "x"))
        assertFalse(File(files, "evil.txt").exists())
    }

    @Test
    fun `legacy ids that are one safe component keep their dir`() {
        val reg = CliToolRegistry(tmp.newFolder("cli-tools"))
        assertEquals("OpenSea", reg.getToolDir("OpenSea")?.name)
        assertNull(reg.getToolDir("registry.json"))
        assertNull(reg.getToolDir("a/b"))
    }

    @Test
    fun `env preamble quotes values and drops bad keys`() {
        assertEquals("", CliToolManagerSkill.envPreamble(emptyMap()))
        assertEquals(
            "export API_KEY='a'\\''b'; ",
            CliToolManagerSkill.envPreamble(linkedMapOf("API_KEY" to "a'b", "X=1; rm -rf ~;" to "v")),
        )
        assertFalse(CliToolManagerSkill.isValidEnvKey("A B"))
        assertTrue(CliToolManagerSkill.isValidEnvKey("_OPENSEA_KEY1"))
    }

    @Test
    fun `binary names and sources`() {
        assertTrue(CliToolManagerSkill.isValidBinaryName("opensea"))
        assertTrue(CliToolManagerSkill.isValidBinaryName("g++"))
        assertFalse(CliToolManagerSkill.isValidBinaryName("x; id"))
        assertFalse(CliToolManagerSkill.isValidBinaryName("--help"))
        assertTrue(CliToolManagerSkill.isValidSourceArg("https://github.com/o/r.git"))
        assertTrue(CliToolManagerSkill.isValidSourceArg("@opensea/cli"))
        assertFalse(CliToolManagerSkill.isValidSourceArg("--upload-pack=touch /x"))
        assertFalse(CliToolManagerSkill.isValidSourceArg("a\nb"))
    }

    @Test
    fun `find output is only taken from under the temp dir`() {
        val t = "/data/data/com.termux/files/home/.cli-tool-tmp/x"
        assertEquals("docs/SKILL.md", CliToolManagerSkill.relativeFoundPath(t, "$t/docs/SKILL.md"))
        assertNull(CliToolManagerSkill.relativeFoundPath(t, "/etc/SKILL.md"))
        assertNull(CliToolManagerSkill.relativeFoundPath(t, "$t/../../SKILL.md"))
        assertNull(CliToolManagerSkill.relativeFoundPath(t, "${t}y/SKILL.md"))
    }
}
