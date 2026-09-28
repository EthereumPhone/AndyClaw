package org.ethereumphone.andyclaw.autopilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `every object is found, past an unclosed brace`() {
        // The reply seen on device: a malformed object, then the model correcting itself.
        val reply = """I should scroll.  {"scroll_fwd:7": "scroll_fwd:7"}  Wait, the correct format:  {"act": "scroll_fwd:7"}"""
        assertEquals(listOf("""{"scroll_fwd:7": "scroll_fwd:7"}""", """{"act": "scroll_fwd:7"}"""),
            LlmAutopilotPlanner.jsonObjects(reply))
        assertEquals(listOf("""{"next": true}"""), LlmAutopilotPlanner.jsonObjects("a { b " + """{"next": true}"""))
    }

    @Test
    fun `an option named without act is still that option`() {
        val never = object : org.ethereumphone.andyclaw.llm.LlmClient {
            override suspend fun sendMessage(request: org.ethereumphone.andyclaw.llm.MessagesRequest) = error("not called")
            override suspend fun streamMessage(request: org.ethereumphone.andyclaw.llm.MessagesRequest,
                callback: org.ethereumphone.andyclaw.llm.StreamingCallback) = error("not called")
        }
        val planner = LlmAutopilotPlanner(client = never, modelId = "m")
        val screen = ScreenSnapshot(packageName = "com.app", elements = emptyList())
        val plan = AutopilotPlan(packageName = "com.app", goal = "g", steps = listOf(PlanStep("s")))
        val ctx = PlannerContext(plan, 0, screen, emptyList(), "low_confidence",
            mapOf("tap:3" to "Tap [3]", "reveal:1001" to "Scroll to bring [1001] \"Display\" into view", "back" to "Back"))
        assertEquals(PlannerDecision.Act("reveal:1001"), planner.parse("""{"reveal": 1001}""", ctx))
        assertEquals(PlannerDecision.Act("tap:3"), planner.parse("""{"tap": 3}""", ctx))
        assertEquals(PlannerDecision.Act("back"), planner.parse("""{"back": true}""", ctx))
        assertEquals(PlannerDecision.Unusable("planner_unrecognised"), planner.parse("""{"reveal": 7}""", ctx))
    }

    @Test
    fun `no object or an unclosed one is nothing`() {
        assertNull(first("no json here"))
        assertNull(first("""{"act": "tap:3""""))
    }

    @Test
    fun `the planner sees what a row says under its title`() {
        // agentbench build_number_deep: "Build number" was on screen with its value as the
        // summary, and the planner, shown only the title, scrolled on to find the value.
        val never = object : org.ethereumphone.andyclaw.llm.LlmClient {
            override suspend fun sendMessage(request: org.ethereumphone.andyclaw.llm.MessagesRequest) = error("not called")
            override suspend fun streamMessage(request: org.ethereumphone.andyclaw.llm.MessagesRequest,
                callback: org.ethereumphone.andyclaw.llm.StreamingCallback) = error("not called")
        }
        val screen = ScreenSnapshot(packageName = "com.android.settings", elements = listOf(
            ScreenElement(1, "menu_item", label = "Build number", summary = "AE3A.240806.043", actions = listOf("click")),
            ScreenElement(2, "text_field", label = "PIN", summary = "1234", password = true, actions = listOf("set_text")),
        ))
        val plan = AutopilotPlan(packageName = "com.android.settings", goal = "g", steps = listOf(PlanStep("s")))
        val prompt = LlmAutopilotPlanner(never, "m").prompt(PlannerContext(plan, 0, screen, emptyList(), "low_confidence", mapOf("tap:1" to "Tap [1]")))
        assertTrue(prompt.contains("\"Build number\" — \"AE3A.240806.043\""))
        assertTrue("a password field's text never goes out", !prompt.contains("1234"))
    }
}
