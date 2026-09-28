package org.ethereumphone.andyclaw.autopilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlmAutopilotPlannerJsonTest {

    private fun first(text: String) = LlmAutopilotPlanner.firstJsonObject(text)

    @Test
    fun `the answer is read even when more follows it`() {
        assertEquals("""{"next": true}""", first("""{"next": true} {"act": "tap:3"}"""))
        assertEquals("""{"act": "tap:3"}""", first("""Sure. {"act": "tap:3"} (the {Display} row)"""))
    }

    @Test
    fun `braces inside strings do not end the object`() {
        assertEquals("""{"abort": "a } b", "say": "x \" { y"}""", first("""{"abort": "a } b", "say": "x \" { y"} trailing"""))
        assertEquals("""{"replan": [{"do": "a"}]}""", first("""{"replan": [{"do": "a"}]}"""))
    }

    @Test
    fun `no object or an unclosed one is nothing`() {
        assertNull(first("no json here"))
        assertNull(first("""{"act": "tap:3""""))
    }
}
