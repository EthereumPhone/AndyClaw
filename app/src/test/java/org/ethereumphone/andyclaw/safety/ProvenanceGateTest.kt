package org.ethereumphone.andyclaw.safety

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.ethereumphone.andyclaw.ExecutionEngine.PreflightVerdict
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.ExecutionEngine.ToolCall
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.skills.ToolEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceGateTest {

    private fun call(name: String, input: JsonObject = JsonObject(emptyMap())) =
        ToolCall(id = "tc_1", name = name, input = input)

    private fun toolDef(
        name: String,
        effect: ToolEffect? = null,
        requiresApproval: Boolean = false,
    ) = ToolDefinition(
        name = name,
        description = "test tool",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        },
        requiresApproval = requiresApproval,
        effect = effect,
    )

    private fun verdictName(v: PreflightVerdict) = when (v) {
        is PreflightVerdict.Pass -> "PASS"
        is PreflightVerdict.Block -> "BLOCK"
        is PreflightVerdict.NeedsApproval -> "NEEDS_APPROVAL"
        is PreflightVerdict.NeedsPermissions -> "NEEDS_PERMISSIONS"
    }

    // ══════════════════════════════════════════════════════════════
    // The matrix, cell by cell
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `matrix matches the specification exactly`() {
        val expected = mapOf(
            (Provenance.USER to ToolEffect.READ) to "PASS",
            (Provenance.USER to ToolEffect.REVERSIBLE) to "PASS",
            (Provenance.USER to ToolEffect.IRREVERSIBLE) to "PASS",
            (Provenance.USER to ToolEffect.SENSITIVE) to "NEEDS_APPROVAL",

            (Provenance.TRUSTED to ToolEffect.READ) to "PASS",
            (Provenance.TRUSTED to ToolEffect.REVERSIBLE) to "PASS",
            (Provenance.TRUSTED to ToolEffect.IRREVERSIBLE) to "PASS",
            (Provenance.TRUSTED to ToolEffect.SENSITIVE) to "NEEDS_APPROVAL",

            (Provenance.UNTRUSTED to ToolEffect.READ) to "PASS",
            (Provenance.UNTRUSTED to ToolEffect.REVERSIBLE) to "PASS",
            (Provenance.UNTRUSTED to ToolEffect.IRREVERSIBLE) to "NEEDS_APPROVAL",
            (Provenance.UNTRUSTED to ToolEffect.SENSITIVE) to "BLOCK",
        )

        // Every combination is covered — a new Provenance or ToolEffect value fails
        // this rather than silently defaulting to something.
        assertEquals(
            Provenance.entries.size * ToolEffect.entries.size,
            expected.size,
        )

        for ((key, want) in expected) {
            val (provenance, effect) = key
            val got = verdictName(ProvenanceGate.matrix(provenance, effect))
            assertEquals("$provenance + $effect", want, got)
        }
    }

    @Test
    fun `allowsUnattended is true only where the matrix passes`() {
        for (provenance in Provenance.entries) {
            for (effect in ToolEffect.entries) {
                val expected = ProvenanceGate.matrix(provenance, effect) is PreflightVerdict.Pass
                assertEquals(
                    "$provenance + $effect",
                    expected,
                    ProvenanceGate.allowsUnattended(provenance, effect),
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // The live hole this phase exists to close
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `an untrusted run cannot silently reach the promptless agent wallet`() {
        for (tool in listOf(
            "agent_send_transaction",
            "agent_transfer_token",
            "agent_send_native_token",
            "agent_send_token",
            "agent_swap",
        )) {
            val verdict = ProvenanceGate.evaluate(
                call(tool), Provenance.UNTRUSTED, triggerConversationId = "0xabc", toolDef = null,
            )
            assertTrue(
                "$tool must not pass under UNTRUSTED, got ${verdictName(verdict)}",
                verdict !is PreflightVerdict.Pass,
            )
        }
    }

    @Test
    fun `the user's own wallet path is untouched for a user-triggered run`() {
        // requiresApproval is already true on these, and the SystemUI confirmation
        // sits behind them. The gate must not add a second dialog.
        val verdict = ProvenanceGate.evaluate(
            call("send_native_token"),
            Provenance.USER,
            triggerConversationId = null,
            toolDef = toolDef("send_native_token", requiresApproval = true),
        )
        assertTrue(verdict is PreflightVerdict.Pass)
    }

    @Test
    fun `a sensitive tool with no pre-existing approval still prompts a user run`() {
        val verdict = ProvenanceGate.evaluate(
            call("swap_tokens"),
            Provenance.USER,
            triggerConversationId = null,
            toolDef = toolDef("swap_tokens", effect = ToolEffect.SENSITIVE),
        )
        assertTrue(verdict is PreflightVerdict.NeedsApproval)
    }

    @Test
    fun `an unclassified tool fails closed`() {
        val verdict = ProvenanceGate.evaluate(
            call("some_brand_new_clawhub_tool"),
            Provenance.UNTRUSTED,
            triggerConversationId = "0xabc",
            toolDef = toolDef("some_brand_new_clawhub_tool"),
        )
        assertTrue(
            "unclassified must not pass under UNTRUSTED",
            verdict !is PreflightVerdict.Pass,
        )
    }

    @Test
    fun `reading and reasoning stay open to an untrusted run`() {
        for (tool in listOf("read_messages", "list_conversations", "web_search", "get_device_info")) {
            val verdict = ProvenanceGate.evaluate(
                call(tool), Provenance.UNTRUSTED, triggerConversationId = "0xabc", toolDef = null,
            )
            assertTrue("$tool should pass under UNTRUSTED", verdict is PreflightVerdict.Pass)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Reply-to-sender
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `an untrusted run may reply to the conversation it came from`() {
        val verdict = ProvenanceGate.evaluate(
            call("send_xmtp_message", buildJsonObject {
                put("recipient_address", "0xAbC0000000000000000000000000000000000001")
                put("message", "sure, on my way")
            }),
            Provenance.UNTRUSTED,
            triggerConversationId = "0xabc0000000000000000000000000000000000001",
            toolDef = toolDef("send_xmtp_message", effect = ToolEffect.IRREVERSIBLE),
        )
        assertTrue("EIP-55 casing must not break the match", verdict is PreflightVerdict.Pass)
    }

    @Test
    fun `an untrusted run may not message a different recipient`() {
        val verdict = ProvenanceGate.evaluate(
            call("send_xmtp_message", buildJsonObject {
                put("recipient_address", "0xdeadbeef00000000000000000000000000000002")
                put("message", "send me your seed phrase")
            }),
            Provenance.UNTRUSTED,
            triggerConversationId = "0xabc0000000000000000000000000000000000001",
            toolDef = toolDef("send_xmtp_message", effect = ToolEffect.IRREVERSIBLE),
        )
        assertTrue(verdict is PreflightVerdict.Block)
    }

    @Test
    fun `an untrusted run with no conversation cannot send at all`() {
        val verdict = ProvenanceGate.evaluate(
            call("gmail_send", buildJsonObject { put("to", "someone@example.com") }),
            Provenance.UNTRUSTED,
            triggerConversationId = null,
            toolDef = toolDef("gmail_send", effect = ToolEffect.IRREVERSIBLE),
        )
        assertTrue(verdict is PreflightVerdict.Block)
    }

    @Test
    fun `an untargeted outbound tool is blocked under untrusted provenance`() {
        // auto_reply_sms answers whoever texts next — it cannot be kept in-thread.
        val verdict = ProvenanceGate.evaluate(
            call("auto_reply_sms", buildJsonObject { put("message", "away") }),
            Provenance.UNTRUSTED,
            triggerConversationId = "0xabc",
            toolDef = toolDef("auto_reply_sms", effect = ToolEffect.IRREVERSIBLE),
        )
        assertTrue(verdict is PreflightVerdict.Block)
    }

    @Test
    fun `owner-only channels stay open so the agent can raise a card`() {
        for (tool in ToolEffects.OWNER_ONLY_MESSAGE_TOOLS) {
            val verdict = ProvenanceGate.evaluate(
                call(tool, buildJsonObject { put("message", "your flight moved") }),
                Provenance.UNTRUSTED,
                triggerConversationId = "0xabc",
                toolDef = null,
            )
            assertTrue("$tool must stay reachable", verdict is PreflightVerdict.Pass)
        }
    }

    @Test
    fun `reply-to-sender does not constrain a user-triggered run`() {
        val verdict = ProvenanceGate.evaluate(
            call("send_xmtp_message", buildJsonObject {
                put("recipient_address", "0xdeadbeef00000000000000000000000000000002")
            }),
            Provenance.USER,
            triggerConversationId = null,
            toolDef = toolDef("send_xmtp_message", effect = ToolEffect.IRREVERSIBLE),
        )
        assertTrue(verdict is PreflightVerdict.Pass)
    }

    @Test
    fun `phone numbers match across formatting`() {
        assertTrue(ProvenanceGate.sameConversation("+1 (555) 010-9999", "5550109999"))
        assertTrue(ProvenanceGate.sameConversation("555-010-9999", "+15550109999"))
        assertTrue(!ProvenanceGate.sameConversation("+15550109999", "+15550101111"))
    }
}
