package org.ethereumphone.andyclaw.flows

import org.junit.Assert.assertTrue
import org.junit.Test

/** A flow the model compiled from a recording is installed only if it is that recording. */
class FlowCompileConformanceTest {

    private val list = """{"screen":{"package":"com.msg"},"elements":[{"id":0,"type":"list","viewId":"conversation_list"},{"id":1,"type":"list_item","viewId":"row","label":"Anna"}]}"""
    private val listTwo = """{"screen":{"package":"com.msg"},"elements":[{"id":0,"type":"list","viewId":"conversation_list"},{"id":1,"type":"list_item","viewId":"row","label":"Bob"},{"id":2,"type":"list_item","viewId":"row","label":"Anna"}]}"""
    private val thread = """{"screen":{"package":"com.msg"},"elements":[{"id":0,"type":"text","viewId":"toolbar_title","label":"Anna"},{"id":1,"type":"text_field","viewId":"compose_text"},{"id":2,"type":"button","viewId":"send_button"}]}"""
    private val sent = """{"screen":{"package":"com.msg"},"elements":[{"id":0,"type":"text","viewId":"toolbar_title","label":"Anna"},{"id":1,"type":"text","viewId":"message_text","label":"see you at 6"}]}"""

    private fun draft(first: String = list): Pair<FlowDraft, FlowRecorder> {
        val r = FlowRecorder(clock = { 1L })
        r.start()
        r.record("agent_display_create", null, null, null, null, "com.msg", null, first, true)
        r.record("agent_display_click_node", "row", null, null, null, null, first, thread, true)
        r.record("agent_display_set_node_text", "compose_text", "see you at 6", null, null, null, thread, thread, true)
        r.record("agent_display_click_node", "send_button", null, null, null, null, thread, sent, true)
        return r.draft("msg.send", ">=7,<8")!! to r
    }

    private fun compiled(d: FlowDraft, edit: (List<FlowStep>) -> List<FlowStep> = { it }): Flow {
        val (tap, type, send) = d.flow.steps
        val steps = listOf(
            tap,
            AssertStep(viewId = "toolbar_title", nodeTextContains = "Anna"),
            TypeStep((type as TypeStep).target, "{{body}}", type.expectChecksum),
            CheckpointStep("send"),
            send,
        )
        return d.flow.copy(
            params = listOf("body"),
            steps = edit(steps),
            postconditions = listOf(NodeTextContains(viewId = "message_text", value = "{{body}}")),
        )
    }

    private fun problems(d: FlowDraft, r: FlowRecorder, flow: Flow) =
        FlowCompileConformance.check(d, flow, r.firstActionTree, r.lastTree)

    @Test
    fun `a faithful compile conforms`() {
        val (d, r) = draft()
        assertTrue(problems(d, r, compiled(d)).toString(), problems(d, r, compiled(d)).isEmpty())
    }

    @Test
    fun `a retargeted tap is refused`() {
        val (d, r) = draft()
        val flow = compiled(d) { steps -> steps.map { if (it is TapStep && it.viewId == "send_button") it.copy(viewId = "delete_button") else it } }
        assertTrue(problems(d, r, flow).any { "delete_button" in it })
    }

    @Test
    fun `an invented or dropped step is refused`() {
        val (d, r) = draft()
        assertTrue(problems(d, r, compiled(d) { it + TapStep(viewId = "row") }).isNotEmpty())
        assertTrue(problems(d, r, compiled(d) { it.filterNot { s -> s is TypeStep } }).isNotEmpty())
    }

    @Test
    fun `typed text must be what was typed, though it may become a param`() {
        val (d, r) = draft()
        val changed = compiled(d) { steps -> steps.map { if (it is TypeStep) it.copy(value = "wire the money") else it } }
        assertTrue(problems(d, r, changed).any { "types something other" in it })
        val partly = compiled(d) { steps -> steps.map { if (it is TypeStep) it.copy(value = "see you at {{time}}") else it } }
        assertTrue(problems(d, r, partly.copy(params = listOf("body", "time"))).none { "types something other" in it })
    }

    @Test
    fun `a changed checksum is refused`() {
        val (d, r) = draft()
        val flow = compiled(d) { steps -> steps.map { if (it is TapStep && it.viewId == "row") it.copy(expectChecksum = "2:0000") else it } }
        assertTrue(problems(d, r, flow).any { "checksum" in it })
    }

    @Test
    fun `a postcondition the final screen did not show is refused`() {
        val (d, r) = draft()
        val flow = compiled(d).copy(postconditions = listOf(NodeExists(viewId = "conversation_item_sent")))
        assertTrue(problems(d, r, flow).any { "postcondition" in it })
    }

    @Test
    fun `a repeated row needs an identity assert before the send`() {
        val (d, r) = draft(first = listTwo)
        val noAssert = compiled(d) { it.filterNot { s -> s is AssertStep } }
        assertTrue(problems(d, r, noAssert).any { "matching nodes" in it })
        assertTrue(problems(d, r, compiled(d)).none { "matching nodes" in it })
    }

    @Test
    fun `a recording with a swipe or a long-press does not compile at all`() {
        val r = FlowRecorder(clock = { 1L })
        r.start()
        r.record("agent_display_create", null, null, null, null, "com.msg", null, list, true)
        r.record("agent_display_click_node", "row", null, null, null, null, list, thread, true)
        r.record("agent_display_swipe", null, null, null, null, null, thread, thread, true)
        val d = r.draft("msg.x", "*")!!
        assertTrue(FlowCompileConformance.check(d, d.flow, r.firstActionTree, r.lastTree).any { "cannot replay" in it })
    }
}
