package org.ethereumphone.andyclaw.skills.builtin

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** What the agent's file tools may touch in AndyClaw's own sandbox. */
class FileSystemProtectionTest {

    private fun err(rel: String, write: Boolean, clean: Boolean = false) =
        FileSystemSkill.protectionError(rel, write, clean)

    @Test
    fun `the gateway identity is neither readable nor writable`() {
        assertNotNull(err("openclaw/identity/device.json", write = false))
        assertNotNull(err("openclaw/identity/device.json", write = true, clean = true))
    }

    @Test
    fun `code and model dirs are readable but not writable`() {
        for (dir in listOf("custom-tools/x.json", "ai-skills/a/SKILL.md", "skills/s/SKILL.md",
                "clawhub-skills/c/SKILL.md", "cli-tools/t.json", "models/m.gguf")) {
            assertNull(dir, err(dir, write = false))
            assertNotNull(dir, err(dir, write = true, clean = true))
        }
    }

    @Test
    fun `standing instructions are writable only from a clean owner run`() {
        for (f in listOf("HEARTBEAT.md", "soul.md", "user_story.md", "HEARTBEAT.md.tmp")) {
            assertNull(f, err(f, write = false))
            assertNotNull(f, err(f, write = true, clean = false))
            assertNull(f, err(f, write = true, clean = true))
        }
    }

    @Test
    fun `ordinary files and the heartbeat journal stay open`() {
        assertNull(err("heartbeat_journal.md", write = true))
        assertNull(err("notes/todo.txt", write = true))
        assertNull(err("skillset.txt", write = true))
    }
}
