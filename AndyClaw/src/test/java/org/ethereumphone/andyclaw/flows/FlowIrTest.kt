package org.ethereumphone.andyclaw.flows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The IR is `agent-os-design.md` §3's schema. These tests pin it to the design doc's own
 * example, key for key — if the shape drifts, a flow written by an older build stops
 * loading, which on 6,000 devices is the expensive kind of mistake.
 */
class FlowIrTest {

    /** Verbatim from `agent-os-design.md` §3, transcribed from YAML to JSON. */
    private val designDocExample = """
        {
          "flow": "signal.send_to_existing_thread",
          "version": 3,
          "app": "org.thoughtcrime.securesms",
          "app_version_range": ">=7.2,<8.0",
          "params": ["contact_name", "body"],
          "preconditions": [ {"node_exists": {"view_id": "conversation_list"}} ],
          "steps": [
            {"tap": {"view_id": "search_button"}},
            {"type": {"target": {"view_id": "search_input"}, "value": "{{contact_name}}"}},
            {"wait_for": {"view_id": "search_result_item", "timeout_ms": 1500}},
            {"tap": {"view_id": "search_result_item", "index": 0}},
            {"assert": {"node_text_contains": "{{contact_name}}", "view_id": "toolbar_title"}},
            {"type": {"target": {"view_id": "compose_text"}, "value": "{{body}}"}},
            {"checkpoint": "send"},
            {"tap": {"view_id": "send_button"}}
          ],
          "postconditions": [ {"node_exists": {"view_id": "conversation_item_sent"}} ]
        }
    """.trimIndent()

    @Test
    fun `the design doc example parses field for field`() {
        val flow = FlowCodec.parse(designDocExample)

        assertEquals("signal.send_to_existing_thread", flow.flow)
        assertEquals(3, flow.version)
        assertEquals("org.thoughtcrime.securesms", flow.app)
        assertEquals(">=7.2,<8.0", flow.appVersionRange)
        assertEquals(listOf("contact_name", "body"), flow.params)
        assertEquals(8, flow.steps.size)

        assertEquals("conversation_list", (flow.preconditions.single() as NodeExists).viewId)
        assertEquals("conversation_item_sent", (flow.postconditions.single() as NodeExists).viewId)

        assertEquals("search_button", (flow.steps[0] as TapStep).viewId)
        assertEquals("search_input", (flow.steps[1] as TypeStep).target.viewId)
        assertEquals("{{contact_name}}", (flow.steps[1] as TypeStep).value)
        assertEquals(1500L, (flow.steps[2] as WaitForStep).timeoutMs)
        assertEquals(0, (flow.steps[3] as TapStep).index)
        assertEquals("toolbar_title", (flow.steps[4] as AssertStep).viewId)
        assertEquals("send", (flow.steps[6] as CheckpointStep).name)
        assertEquals("send_button", (flow.steps[7] as TapStep).viewId)
    }

    @Test
    fun `round trips through the canonical form`() {
        val flow = FlowCodec.parse(designDocExample)
        val again = FlowCodec.parse(FlowCodec.canonicalJson(flow))
        assertEquals(flow, again)
    }

    @Test
    fun `the content address ignores formatting and key order`() {
        val reordered = """
            {
              "version": 3,
              "flow": "signal.send_to_existing_thread",
              "app_version_range": ">=7.2,<8.0",
              "app": "org.thoughtcrime.securesms",
              "steps": [ {"tap": {"view_id": "search_button"}} ],
              "params": ["contact_name", "body"],
              "postconditions": [ {"node_exists": {"view_id": "conversation_item_sent"}} ],
              "preconditions": [ {"node_exists": {"view_id": "conversation_list"}} ]
            }
        """.trimIndent()
        val compact =
            """{"flow":"signal.send_to_existing_thread","version":3,"app":"org.thoughtcrime.securesms",""" +
                """"app_version_range":">=7.2,<8.0","params":["contact_name","body"],""" +
                """"preconditions":[{"node_exists":{"view_id":"conversation_list"}}],""" +
                """"steps":[{"tap":{"view_id":"search_button"}}],""" +
                """"postconditions":[{"node_exists":{"view_id":"conversation_item_sent"}}]}"""

        assertEquals(
            FlowCodec.contentHash(FlowCodec.parse(reordered)),
            FlowCodec.contentHash(FlowCodec.parse(compact)),
        )
    }

    @Test
    fun `checkpoint accepts the bare string the design doc writes`() {
        val step = FlowCodec.parse(
            """{"flow":"a.b","version":1,"app":"a.b","app_version_range":"*","steps":[{"checkpoint":"send"}]}"""
        ).steps.single()
        assertEquals(CheckpointStep("send"), step)
    }

    @Test
    fun `an unknown opcode is refused rather than skipped`() {
        val flow = FlowCodec.parseOrNull(
            """{"flow":"a.b","version":1,"app":"a.b","app_version_range":"*","steps":[{"teleport":{}}]}"""
        )
        assertNull("an opcode this build cannot execute must not load", flow)
    }

    @Test
    fun `a step carrying two opcodes is refused`() {
        assertNull(
            FlowCodec.parseOrNull(
                """{"flow":"a.b","version":1,"app":"a.b","app_version_range":"*",""" +
                    """"steps":[{"tap":{"view_id":"x"},"checkpoint":"send"}]}"""
            )
        )
    }

    @Test
    fun `unknown keys from a newer build do not break parsing`() {
        val flow = FlowCodec.parseOrNull(
            """{"flow":"a.b","version":1,"app":"a.b","app_version_range":"*",""" +
                """"budget_ms":5000,"steps":[{"tap":{"view_id":"x","haptics":true}}]}"""
        )
        assertNotNull(flow)
        assertEquals("x", (flow!!.steps.single() as TapStep).viewId)
    }

    @Test
    fun `tool name is derived from the flow id`() {
        assertEquals(
            "flow_signal_send_to_existing_thread",
            FlowCodec.parse(designDocExample).toolName,
        )
    }

    @Test
    fun `coordinates survive a round trip so the validator can refuse them`() {
        val flow = FlowCodec.parse(
            """{"flow":"a.b","version":1,"app":"a.b","app_version_range":"*",""" +
                """"steps":[{"tap":{"x":120.0,"y":340.0}}]}"""
        )
        val tap = flow.steps.single() as TapStep
        assertEquals(120.0, tap.x!!, 0.0)
        assertTrue(FlowCodec.canonicalJson(flow).contains("\"x\":120.0"))
    }
}
