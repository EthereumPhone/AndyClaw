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

    @Test
    fun `camelCase ids are split before they are matched`() {
        assertEquals(ToolEffect.SENSITIVE, FlowStepEffects.of(TapStep(viewId = "com.shop:id/btnPay")))
        assertEquals(ToolEffect.SENSITIVE, FlowStepEffects.of(TapStep(viewId = "confirmPurchase")))
        assertEquals(ToolEffect.SENSITIVE, FlowStepEffects.of(TapStep(viewId = "privateKeyField")))
        assertEquals(ToolEffect.IRREVERSIBLE, FlowStepEffects.of(TapStep(viewId = "sendMessageButton")))
    }

    @Test
    fun `German words are known, umlauts and all`() {
        assertTrue("kaufen" in FlowStepEffects.tokenize("Jetzt kaufen"))
        assertTrue("loschen" in FlowStepEffects.tokenize("Löschen"))
        assertTrue("zahlungspflichtig" in FlowStepEffects.tokenize("Zahlungspflichtig bestellen"))
        assertEquals(ToolEffect.SENSITIVE, FlowStepEffects.of(TapStep(viewId = "btn_bezahlen")))
        assertEquals(ToolEffect.IRREVERSIBLE, FlowStepEffects.of(TapStep(viewId = "nachricht_senden")))
    }

    @Test
    fun `words split by punctuation are joined back`() {
        assertEquals(ToolEffect.SENSITIVE, FlowStepEffects.of(TapStep(viewId = "sign_in_button")))
        assertEquals(ToolEffect.SENSITIVE, FlowStepEffects.of(TapStep(viewId = "check-out")))
    }

    @Test
    fun `payment and sign-in read in the other languages people use`() {
        for (label in listOf("Payer 23,40 €", "Pagar ahora", "Finalizar compra", "Betalen", "Acquista ora",
            "Mot de passe oublié ? Se connecter", "Iniciar sesión", "Zapłać", "Ödeme yap", "Zaplatit")) {
            assertTrue(label, FlowStepEffects.isSensitiveText(label))
        }
        for (label in listOf("Place order", "Top up", "Verification code", "Use a passkey", "Subscribe", "Ｐａｙ")) {
            assertTrue(label, FlowStepEffects.isSensitiveText(label))
        }
        assertFalse(FlowStepEffects.isSensitiveText("Envoyer"))
        assertFalse(FlowStepEffects.isSensitiveText("Open chat"))
    }

    @Test
    fun `German compounds are caught by what they are built on`() {
        for (label in listOf("Kreditkarte hinzufügen", "Zahlungsart wählen", "Bezahlvorgang", "Einmalpasswort", "Kreditkartennummer")) {
            assertTrue(label, FlowStepEffects.isSensitiveText(label))
        }
        assertEquals(ToolEffect.SENSITIVE, FlowStepEffects.of(TapStep(viewId = "com.shop:id/passwordfield")))
        assertFalse(FlowStepEffects.isSensitiveText("Nachricht senden"))
    }

    @Test
    fun `a bank's TAN is sensitive, the name Tan is not`() {
        assertFalse(FlowStepEffects.isSensitiveText("Tan"))
        assertFalse(FlowStepEffects.isSensitiveText("Chat with Amy Tan"))
        assertFalse(FlowStepEffects.isSensitiveText("tan leather"))
        assertTrue(FlowStepEffects.isSensitiveText("TAN eingeben"))
        assertTrue(FlowStepEffects.isSensitiveText("pushTAN freigeben"))
        assertTrue(FlowStepEffects.isSensitiveText("com.bank:id/tan_input"))
        assertEquals(ToolEffect.SENSITIVE, FlowStepEffects.of(TypeStep(target = Selector(viewId = "com.bank:id/tan"), value = "1")))
    }

    @Test
    fun `a label in a script the lists cannot read says so`() {
        assertTrue(FlowStepEffects.unreadable("Оплатить"))
        assertTrue(FlowStepEffects.unreadable("立即支付"))
        assertTrue(FlowStepEffects.unreadable("Отправить SMS"))
        assertFalse(FlowStepEffects.unreadable("Payer 23,40 €"))
        assertFalse(FlowStepEffects.unreadable("Ödeme yap, şimdi"))
        assertFalse(FlowStepEffects.unreadable("Đặt hàng"))
        assertFalse(FlowStepEffects.unreadable("123 ✓"))
        assertFalse(FlowStepEffects.unreadable(null))
    }
}
