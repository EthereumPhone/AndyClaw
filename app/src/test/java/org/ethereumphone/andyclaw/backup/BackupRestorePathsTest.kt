package org.ethereumphone.andyclaw.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupRestorePathsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `entries inside the skill dir restore`() {
        val dir = tmp.newFolder("ai-skills")
        assertEquals(File(dir, "demo/SKILL.md").canonicalFile, BackupManager.restoreTarget(dir, "demo/SKILL.md")?.canonicalFile)
        assertEquals(
            File(dir, ".clawhub/lock.json").canonicalFile,
            BackupManager.restoreTarget(dir, ".clawhub/lock.json")?.canonicalFile,
        )
    }

    @Test
    fun `zip-slip entries are skipped`() {
        val dir = tmp.newFolder("ai-skills")
        tmp.newFolder("ai-skills-evil")
        assertNull(BackupManager.restoreTarget(dir, "../trigger_provenance.json"))
        assertNull(BackupManager.restoreTarget(dir, "demo/../../pending_approvals.json"))
        assertNull(BackupManager.restoreTarget(dir, "../ai-skills-evil/x"))
        assertNull(BackupManager.restoreTarget(dir, ""))
        assertNull(BackupManager.restoreTarget(dir, "demo/"))
    }
}
