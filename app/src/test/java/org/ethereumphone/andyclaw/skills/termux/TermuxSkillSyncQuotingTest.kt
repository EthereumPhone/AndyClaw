package org.ethereumphone.andyclaw.skills.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TermuxSkillSyncQuotingTest {

    /** Run [script] under a real /bin/sh with `printf` swapped for an argv dump. */
    private fun sh(script: String): String? {
        if (!File("/bin/sh").canExecute()) return null
        val p = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        p.waitFor()
        return out
    }

    @Test
    fun `a file name with a quote stays one word`() {
        val evil = "/home/.andyclaw/skills/s/a'; echo INJECTED; '.sh"
        val cmd = TermuxSkillSync.writeFileCommand(evil, "aGk=")
        // Replace the side-effecting commands so only the argv shape is observed.
        val probe = cmd.replace("mkdir -p --", "printf '[%s]' ")
            .replace("| base64 -d > ", "; printf '[%s]' ")
        val out = sh(probe) ?: return
        assertFalse(out, out.contains("INJECTED\n"))
        assertTrue(out, out.contains("[$evil]"))
    }

    @Test
    fun `setup path must stay inside the skill home`() {
        assertNull(TermuxSkillSync.setupCommand("/h/s", "../../x.sh"))
        assertNull(TermuxSkillSync.setupCommand("/h/s", "/etc/x.sh"))
        assertNull(TermuxSkillSync.setupCommand("/h/s", ""))
        assertEquals(
            "cd '/h/s' && chmod +x -- 'setup'\\''x.sh' && bash -- 'setup'\\''x.sh'",
            TermuxSkillSync.setupCommand("/h/s", "setup'x.sh"),
        )
    }

    @Test
    fun `bin names are package shaped`() {
        assertTrue(TermuxSkillSync.isValidBinName("python"))
        assertTrue(TermuxSkillSync.isValidBinName("g++"))
        assertFalse(TermuxSkillSync.isValidBinName("x; rm -rf ~"))
        assertFalse(TermuxSkillSync.isValidBinName("-o"))
        assertFalse(TermuxSkillSync.isValidBinName("\$(id)"))
    }
}
