package org.ethereumphone.andyclaw.flows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowRecorderTest {

    private val treeA = """{"screen":{"package":"com.msg"},"elements":[{"id":0,"type":"row","viewId":"a"}]}"""
    private val treeB = """{"screen":{"package":"com.msg"},"elements":[{"id":0,"type":"row","viewId":"b"}]}"""

    private fun recorder() = FlowRecorder(clock = { 1L })

    @Test
    fun `records nothing until a session starts`() {
        val r = recorder()
        r.record("agent_display_click_node", "x", null, null, null, null, treeA, treeB, true)
        assertTrue(r.recorded.isEmpty())
    }

    @Test
    fun `a session turns node actions into steps`() {
        val r = recorder()
        r.start()
        r.record("agent_display_create", null, null, null, null, "com.msg", null, treeA, true)
        r.record("agent_display_click_node", "search_button", null, null, null, null, treeA, treeB, true)
        r.record("agent_display_set_node_text", "compose_text", "hi", null, null, null, treeB, treeB, true)

        val draft = r.draft("msg.send", ">=1,<2")!!
        assertEquals("com.msg", draft.flow.app)
        assertEquals(2, draft.flow.steps.size)
        assertEquals("search_button", (draft.flow.steps[0] as TapStep).viewId)
        assertEquals("hi", (draft.flow.steps[1] as TypeStep).value)
        assertTrue(draft.isMechanicallyComplete)
    }

    @Test
    fun `each step carries the checksum of the screen it was taken on`() {
        val r = recorder()
        r.start()
        r.record("agent_display_create", null, null, null, null, "com.msg", null, treeA, true)
        r.record("agent_display_click_node", "x", null, null, null, null, treeA, treeB, true)

        val step = r.draft("msg.send", "*")!!.flow.steps.single()
        assertEquals(NodeTreeChecksum.of(treeA), step.expectChecksum)
    }

    @Test
    fun `a coordinate tap is recorded honestly and then refused by the validator`() {
        val r = recorder()
        r.start()
        r.record("agent_display_create", null, null, null, null, "com.msg", null, treeA, true)
        r.record("agent_display_tap", null, null, 120.0, 340.0, null, treeA, treeB, true)

        val draft = r.draft("msg.send", "*")!!
        assertFalse(draft.isMechanicallyComplete)
        val tap = draft.flow.steps.single() as TapStep
        assertEquals(120.0, tap.x!!, 0.0)
        assertTrue("coordinate_selector" in FlowValidator.validate(draft.flow).errors.map { it.code })
    }

    @Test
    fun `reads are not steps`() {
        val r = recorder()
        r.start()
        r.record("agent_display_create", null, null, null, null, "com.msg", null, treeA, true)
        r.record("agent_display_look", null, null, null, null, null, treeA, treeA, true)
        r.record("agent_display_screenshot", null, null, null, null, null, treeA, treeA, true)
        assertNull("a session of nothing but reads is not a flow", r.draft("msg.x", "*")?.flow?.steps?.firstOrNull())
    }

    @Test
    fun `an unsupported action is listed rather than silently dropped`() {
        val r = recorder()
        r.start()
        r.record("agent_display_create", null, null, null, null, "com.msg", null, treeA, true)
        r.record("agent_display_click_node", "x", null, null, null, null, treeA, treeB, true)
        r.record("agent_display_press_back", null, null, null, null, null, treeB, treeA, true)

        val draft = r.draft("msg.send", "*")!!
        assertEquals(listOf("agent_display_press_back"), draft.unsupportedActions)
        assertFalse(draft.isMechanicallyComplete)
    }

    @Test
    fun `a failed action is not compiled into the flow`() {
        val r = recorder()
        r.start()
        r.record("agent_display_create", null, null, null, null, "com.msg", null, treeA, true)
        r.record("agent_display_click_node", "gone", null, null, null, null, treeA, treeA, false)
        r.record("agent_display_click_node", "there", null, null, null, null, treeA, treeB, true)

        val steps = r.draft("msg.send", "*")!!.flow.steps
        assertEquals(1, steps.size)
        assertEquals("there", (steps.single() as TapStep).viewId)
    }

    @Test
    fun `the draft is deliberately not a valid flow yet`() {
        val r = recorder()
        r.start()
        r.record("agent_display_create", null, null, null, null, "com.msg", null, treeA, true)
        r.record("agent_display_click_node", "send_button", null, null, null, null, treeA, treeB, true)

        val errors = FlowValidator.validate(r.draft("msg.send", "*")!!.flow).errors.map { it.code }
        // Nothing in a recording says what success looked like, or where the
        // irreversible boundary sits. Both are the compiler's job.
        assertTrue("no_postconditions" in errors)
        assertTrue("missing_checkpoint" in errors)
    }

    @Test
    fun `starting a session bumps the id so a caller can tell whose session it is`() {
        val r = recorder()
        val before = r.sessionId
        r.start()
        assertEquals(before + 1, r.sessionId)
        r.stop()
        r.record("agent_display_click_node", "x", null, null, null, null, treeA, treeB, true)
        assertTrue("recording must stop when the display is destroyed", r.recorded.isEmpty())
    }
}
