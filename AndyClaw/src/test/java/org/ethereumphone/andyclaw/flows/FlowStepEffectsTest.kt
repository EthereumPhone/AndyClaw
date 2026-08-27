package org.ethereumphone.andyclaw.flows

import org.ethereumphone.andyclaw.skills.ToolEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowStepEffectsTest {

    @Test
    fun `looking at the screen changes nothing`() {
        assertEquals(ToolEffect.READ, FlowStepEffects.of(WaitForStep(viewId = "x")))
        assertEquals(ToolEffect.READ, FlowStepEffects.of(AssertStep(viewId = "x", nodeTextContains = "y")))
        assertEquals(ToolEffect.READ, FlowStepEffects.of(CheckpointStep("send")))
    }

    @Test
    fun `typing into a field is reversible until something commits it`() {
        assertEquals(
            ToolEffect.REVERSIBLE,
            FlowStepEffects.of(TypeStep(target = Selector(viewId = "compose_text"), value = "hi")),
        )
    }

    @Test
    fun `a commit verb in the view id is irreversible`() {
        assertEquals(ToolEffect.IRREVERSIBLE, FlowStepEffects.of(TapStep(viewId = "send_button")))
        assertEquals(ToolEffect.IRREVERSIBLE, FlowStepEffects.of(TapStep(viewId = "com.x:id/btn_delete")))
        assertEquals(ToolEffect.IRREVERSIBLE, FlowStepEffects.of(TapStep(viewId = "post_fab")))
    }

    @Test
    fun `tokens match whole words so resend is not send`() {
        assertEquals(ToolEffect.REVERSIBLE, FlowStepEffects.of(TapStep(viewId = "resend_hint")))
        assertEquals(ToolEffect.REVERSIBLE, FlowStepEffects.of(TapStep(viewId = "com.sendbird.chat:id/row")))
    }

    @Test
    fun `payment and authentication are sensitive`() {
        assertEquals(ToolEffect.SENSITIVE, FlowStepEffects.of(TapStep(viewId = "com.shop:id/pay_now")))
        assertEquals(ToolEffect.SENSITIVE, FlowStepEffects.of(TapStep(viewId = "checkout_button")))
        assertEquals(
            ToolEffect.SENSITIVE,
            FlowStepEffects.of(TypeStep(target = Selector(viewId = "pin_entry"), value = "1")),
        )
    }

    @Test
    fun `a declaration raises but never lowers`() {
        assertEquals(
            ToolEffect.IRREVERSIBLE,
            FlowStepEffects.of(TapStep(viewId = "send_button", effect = "read")),
        )
        assertEquals(
            ToolEffect.IRREVERSIBLE,
            FlowStepEffects.of(TapStep(viewId = "fab", effect = "irreversible")),
        )
    }

    @Test
    fun `an unrecognised declaration is ignored, not obeyed`() {
        assertEquals(ToolEffect.REVERSIBLE, FlowStepEffects.of(TapStep(viewId = "fab", effect = "harmless")))
    }

    @Test
    fun `the tool a flow becomes is blunt on purpose`() {
        // No verb table at the tool level: anything that actuates needs the user's
        // approval before it replays, whatever the step classification says.
        val navigationOnly = Flow(
            flow = "a.b", version = 1, app = "a.b", appVersionRange = "*",
            steps = listOf(TapStep(viewId = "settings_row")),
        )
        assertEquals(ToolEffect.IRREVERSIBLE, FlowToolEffect.of(navigationOnly))
        assertTrue(FlowToolEffect.requiresApproval(navigationOnly))

        val readOnly = Flow(
            flow = "a.b", version = 1, app = "a.b", appVersionRange = "*",
            steps = listOf(WaitForStep(viewId = "x"), AssertStep(viewId = "x", nodeTextContains = "y")),
        )
        assertEquals(ToolEffect.READ, FlowToolEffect.of(readOnly))
        assertFalse(FlowToolEffect.requiresApproval(readOnly))
    }
}
