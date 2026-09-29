package org.ethereumphone.andyclaw.skills.builtin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SkillCreatorSkillPathsTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var aiDir: File
    private lateinit var hubDir: File
    private lateinit var skill: SkillCreatorSkill

    @Before
    fun setUp() {
        filesDir = tmp.newFolder("files")
        aiDir = File(filesDir, "ai-skills").apply { mkdirs() }
        hubDir = File(filesDir, "clawhub-skills").apply { mkdirs() }
        File(filesDir, "trigger_provenance.json").writeText("original")
        skill = SkillCreatorSkill(aiDir, hubDir, NativeSkillRegistry()) {}
    }

    private fun run(tool: String, vararg params: Pair<String, String>): SkillResult = runBlocking {
        skill.execute(tool, buildJsonObject { params.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }, Tier.OPEN)
    }

    @Test
    fun `slugs skill_create has always accepted still resolve`() {
        for (slug in listOf("a", "my-skill", "x1", "a".repeat(64))) {
            assertNotNull(slug, SkillCreatorSkill.aiSkillDir(aiDir, slug))
        }
    }

    @Test
    fun `traversal slugs never resolve`() {
        for (slug in listOf("..", ".", "", "../x", "a/b", "My-Skill", "-a", "a".repeat(65))) {
            assertNull(slug, SkillCreatorSkill.aiSkillDir(aiDir, slug))
        }
    }

    @Test
    fun `write with slug dot-dot cannot reach AndyClaw state`() {
        val r = run("skill_write_file", "slug" to "..", "file_path" to "trigger_provenance.json", "content" to "{}")
        assertTrue(r is SkillResult.Error)
        assertEquals("original", File(filesDir, "trigger_provenance.json").readText())
    }

    @Test
    fun `delete with slug dot-dot wipes nothing`() {
        val r = run("skill_delete", "slug" to "..")
        assertTrue(r is SkillResult.Error)
        assertTrue(File(filesDir, "trigger_provenance.json").isFile)
        assertTrue(aiDir.isDirectory)
    }

    @Test
    fun `read_source does not follow a traversal id`() {
        File(filesDir, "SKILL.md").writeText("private")
        assertTrue(run("skill_read_source", "skill_id" to "ai:..") is SkillResult.Error)
        assertTrue(run("skill_read_source", "skill_id" to "clawhub:..") is SkillResult.Error)
    }

    @Test
    fun `create write and delete still work for a real skill`() {
        val created = run(
            "skill_create", "name" to "Demo", "slug" to "demo",
            "description" to "d", "instructions" to "do it",
        )
        assertTrue(created.toString(), created is SkillResult.Success)
        val wrote = run("skill_write_file", "slug" to "demo", "file_path" to "scripts/run.sh", "content" to "echo")
        assertTrue(wrote.toString(), wrote is SkillResult.Success)
        assertTrue(File(aiDir, "demo/scripts/run.sh").isFile)
        assertTrue(run("skill_delete", "slug" to "demo") is SkillResult.Success)
        assertFalse(File(aiDir, "demo").exists())
    }

    @Test
    fun `file target must stay strictly inside the skill`() {
        val dir = File(aiDir, "demo").apply { mkdirs() }
        assertNotNull(SkillCreatorSkill.skillFileTarget(dir, "notes/a.md"))
        assertNull(SkillCreatorSkill.skillFileTarget(dir, "../other/a.md"))
        assertNull(SkillCreatorSkill.skillFileTarget(dir, "/etc/x"))
        assertNull(SkillCreatorSkill.skillFileTarget(dir, "SKILL.md"))
        assertNull(SkillCreatorSkill.skillFileTarget(dir, "."))
        // A symlink planted in the skill dir can't carry a write out of it.
        Files.createSymbolicLink(File(dir, "out").toPath(), filesDir.toPath())
        assertNull(SkillCreatorSkill.skillFileTarget(dir, "out/trigger_provenance.json"))
    }
}
