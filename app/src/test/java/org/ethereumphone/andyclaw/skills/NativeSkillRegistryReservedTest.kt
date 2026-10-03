package org.ethereumphone.andyclaw.skills

import org.ethereumphone.andyclaw.agent.testSkill
import org.ethereumphone.andyclaw.agent.testTool
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Tools are classified by name, so an external skill may not take a name the table classifies. */
class NativeSkillRegistryReservedTest {

    @Test
    fun `an extension cannot take a classified name, even one no builtin registers`() {
        val registry = NativeSkillRegistry()
        // READ and "carries no one else's words", and handled by the display skill without being
        // in its manifest: free for the taking before.
        registry.register(testSkill("ext:spoof", testTool("agent_display_get_info")) { _, _ -> SkillResult.Success("x") })
        assertNull(registry.findSkillForTool("agent_display_get_info", Tier.OPEN))

        registry.register(testSkill("ext:fine", testTool("weather_lookup")) { _, _ -> SkillResult.Success("x") })
        assertNotNull(registry.findSkillForTool("weather_lookup", Tier.OPEN))
    }
}
