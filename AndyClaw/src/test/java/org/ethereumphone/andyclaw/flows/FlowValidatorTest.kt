package org.ethereumphone.andyclaw.flows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate. `agent-os-design.md` §3's three rules plus §6's hard stop, each with the
 * flow that must be refused and the flow that must not be.
 */
class FlowValidatorTest {

    private fun flow(
        id: String = "signal.send_to_existing_thread",
        params: List<String> = listOf("contact_name", "body"),
        pre: List<Condition> = listOf(NodeExists(viewId = "conversation_list")),
        post: List<Condition> = listOf(NodeExists(viewId = "conversation_item_sent")),
        steps: List<FlowStep> = designDocSteps(),
        range: String = ">=7.2,<8.0",
    ) = Flow(
        flow = id,
        version = 3,
        app = "org.thoughtcrime.securesms",
        appVersionRange = range,
        params = params,
        preconditions = pre,
        steps = steps,
        postconditions = post,
    )

    private fun designDocSteps(): List<FlowStep> = listOf(
        TapStep(viewId = "search_button"),
        TypeStep(target = Selector(viewId = "search_input"), value = "{{contact_name}}"),
        WaitForStep(viewId = "search_result_item", timeoutMs = 1500),
        TapStep(viewId = "search_result_item", index = 0),
        AssertStep(viewId = "toolbar_title", nodeTextContains = "{{contact_name}}"),
        TypeStep(target = Selector(viewId = "compose_text"), value = "{{body}}"),
        CheckpointStep("send"),
        TapStep(viewId = "send_button"),
    )

    private fun codes(flow: Flow) = FlowValidator.validate(flow).errors.map { it.code }

    @Test
    fun `the design doc example compiles`() {
        val validation = FlowValidator.validate(flow())
        assertTrue("expected valid, got ${validation.errors}", validation.isValid)
    }

    // ── Rule 1: view_id first, never text, never coordinates ──────────

    @Test
    fun `a coordinate tap is refused`() {
        val steps = designDocSteps().toMutableList().also { it[0] = TapStep(x = 210.0, y = 640.0) }
        assertTrue("coordinate_selector" in codes(flow(steps = steps)))
    }

    @Test
    fun `a text selector is refused`() {
        val steps = designDocSteps().toMutableList().also { it[0] = TapStep(text = "Search") }
        val found = codes(flow(steps = steps))
        assertTrue("text_selector" in found)
        assertTrue("missing_view_id" in found)
    }

    @Test
    fun `typing into a coordinate is refused too`() {
        val steps = designDocSteps().toMutableList()
        steps[1] = TypeStep(target = Selector(x = 10.0, y = 20.0), value = "{{contact_name}}")
        assertTrue("coordinate_selector" in codes(flow(steps = steps)))
    }

    // ── Rule 2: pre- and postconditions ───────────────────────────────

    @Test
    fun `a flow with no preconditions is refused`() {
        assertTrue("no_preconditions" in codes(flow(pre = emptyList())))
    }

    @Test
    fun `a flow that cannot assert it worked is refused`() {
        assertTrue("no_postconditions" in codes(flow(post = emptyList())))
    }

    @Test
    fun `a condition without a view_id is refused`() {
        assertTrue("condition_without_view_id" in codes(flow(post = listOf(NodeExists()))))
    }

    // ── Rule 3: a checkpoint before anything irreversible ─────────────

    @Test
    fun `dropping the checkpoint before the send makes the flow uncompilable`() {
        val steps = designDocSteps().filterNot { it is CheckpointStep }
        val errors = FlowValidator.validate(flow(steps = steps)).errors
        assertTrue("missing_checkpoint" in errors.map { it.code })
        // and it points at the send, not at the navigation taps before it
        assertEquals(steps.lastIndex, errors.first { it.code == "missing_checkpoint" }.stepIndex)
    }

    @Test
    fun `a checkpoint after the irreversible step does not count`() {
        val steps = listOf(
            TapStep(viewId = "compose_button"),
            TapStep(viewId = "send_button"),
            CheckpointStep("send"),
        )
        assertTrue("missing_checkpoint" in codes(flow(steps = steps)))
    }

    @Test
    fun `navigation taps need no checkpoint`() {
        val steps = listOf(
            TapStep(viewId = "search_button"),
            TapStep(viewId = "conversation_row"),
            AssertStep(viewId = "toolbar_title", nodeTextContains = "x"),
        )
        assertTrue(FlowValidator.validate(flow(params = emptyList(), steps = steps)).isValid)
    }

    @Test
    fun `a step cannot declare its way past the checkpoint rule`() {
        val steps = listOf(
            // Claiming the send button is reversible must not work: the declaration can
            // only ever raise the classification.
            TapStep(viewId = "send_button", effect = "reversible"),
        )
        assertTrue("missing_checkpoint" in codes(flow(params = emptyList(), steps = steps)))
    }

    @Test
    fun `a step may declare itself irreversible when the name does not say so`() {
        val steps = listOf(TapStep(viewId = "fab_primary", effect = "irreversible"))
        assertTrue("missing_checkpoint" in codes(flow(params = emptyList(), steps = steps)))
    }

    // ── §6's hard stop: payment and authentication ────────────────────

    @Test
    fun `a payment step makes the whole flow uncompilable`() {
        val steps = listOf(CheckpointStep("pay"), TapStep(viewId = "com.shop.app:id/pay_now"))
        assertTrue("sensitive_step" in codes(flow(params = emptyList(), steps = steps)))
    }

    @Test
    fun `typing a password is refused even behind a checkpoint`() {
        val steps = listOf(
            CheckpointStep("auth"),
            TypeStep(target = Selector(viewId = "com.bank.app:id/password_field"), value = "x"),
        )
        assertTrue("sensitive_step" in codes(flow(params = emptyList(), steps = steps)))
    }

    // ── Housekeeping ──────────────────────────────────────────────────

    @Test
    fun `a placeholder that is not a declared param is refused`() {
        val steps = listOf(
            TypeStep(target = Selector(viewId = "compose_text"), value = "{{secret}}"),
        )
        assertTrue("undeclared_param" in codes(flow(params = listOf("body"), steps = steps)))
    }

    @Test
    fun `an unparseable version range is refused`() {
        assertTrue("bad_version_range" in codes(flow(range = "latest")))
    }

    @Test
    fun `a wait with no timeout bound is refused`() {
        val steps = listOf(WaitForStep(viewId = "x", timeoutMs = 120_000))
        assertTrue("bad_timeout" in codes(flow(params = emptyList(), steps = steps)))
    }

    @Test
    fun `an empty flow is refused`() {
        assertTrue("no_steps" in codes(flow(params = emptyList(), steps = emptyList())))
    }

    @Test
    fun `an assert that checks nothing is refused`() {
        val steps = listOf(AssertStep(viewId = "toolbar_title"))
        assertTrue("empty_assert" in codes(flow(params = emptyList(), steps = steps)))
    }

    @Test
    fun `a flow id that is not a stable identifier is refused`() {
        assertTrue("bad_flow_id" in codes(flow(id = "Signal Send!")))
    }
}
