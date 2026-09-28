package org.ethereumphone.andyclaw.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatGuardTest {

    private fun tap(x: Int, id: String) =
        ContentBlock.ToolUseBlock(id, "agent_display_tap", JsonObject(mapOf("x" to JsonPrimitive(x), "y" to JsonPrimitive(581))))

    private fun result(id: String, screen: String) = ContentBlock.ToolResult(id, "Tapped.\n$screen")

    private fun RepeatGuard.iteration(n: Int, x: Int = 360, screen: String = "Aqua") =
        observe(listOf(tap(x, "t$n")), listOf(result("t$n", screen)))

    @Test
    fun `the same tap with the same screen warns at three and stops at six`() {
        val guard = RepeatGuard()
        val verdicts = (1..6).map { guard.iteration(it) }
        assertEquals(
            listOf(RepeatGuard.Verdict.OK, RepeatGuard.Verdict.OK, RepeatGuard.Verdict.WARN,
                RepeatGuard.Verdict.WARN, RepeatGuard.Verdict.WARN, RepeatGuard.Verdict.STOP),
            verdicts,
        )
    }

    @Test
    fun `call ids do not count, arguments and results do`() {
        val guard = RepeatGuard()
        guard.iteration(1); guard.iteration(2)
        assertEquals("another argument starts over", RepeatGuard.Verdict.OK, guard.iteration(3, x = 486))
        assertEquals(1, guard.streak)
        guard.iteration(4, x = 486)
        assertEquals("a screen that moved starts over", RepeatGuard.Verdict.OK, guard.iteration(5, x = 486, screen = "Ochre"))
        assertEquals(1, guard.streak)
    }
}
