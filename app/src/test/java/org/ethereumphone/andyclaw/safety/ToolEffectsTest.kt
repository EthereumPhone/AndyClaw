package org.ethereumphone.andyclaw.safety

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.skills.ToolEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolEffectsTest {

    private fun toolDef(name: String, effect: ToolEffect? = null) = ToolDefinition(
        name = name,
        description = "test",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        },
        effect = effect,
    )

    @Test
    fun `an unclassified tool resolves to IRREVERSIBLE`() {
        assertEquals(ToolEffect.IRREVERSIBLE, ToolEffects.of("a_tool_nobody_classified"))
        assertEquals(ToolEffect.IRREVERSIBLE, ToolEffects.UNCLASSIFIED)
    }

    @Test
    fun `an explicit declaration beats the seed table`() {
        // get_device_info is READ in the table; a definition that says otherwise wins.
        assertEquals(ToolEffect.READ, ToolEffects.of("get_device_info"))
        assertEquals(
            ToolEffect.SENSITIVE,
            ToolEffects.of(toolDef("get_device_info", ToolEffect.SENSITIVE)),
        )
    }

    @Test
    fun `the pre-existing read-only list is honoured`() {
        // memory_read is in ToolAttenuation.READ_ONLY_TOOLS but not in the seed table.
        assertFalse("test premise", "memory_read" in ToolEffects.BUILTIN)
        assertTrue("test premise", "memory_read" in ToolAttenuation.READ_ONLY_TOOLS)
        assertEquals(ToolEffect.READ, ToolEffects.of("memory_read"))
    }

    @Test
    fun `every promptless agent-wallet tool is irreversible`() {
        for (tool in ToolEffects.BUILTIN.keys.filter { it.startsWith("agent_") && !it.startsWith("agent_display") }) {
            assertEquals(tool, ToolEffect.IRREVERSIBLE, ToolEffects.of(tool))
        }
    }

    @Test
    fun `shell, code execution and outbound messaging are irreversible`() {
        for (tool in listOf(
            "run_shell_command", "termux_run_command", "execute_code",
            "gmail_send", "gmail_reply", "send_xmtp_message", "send_sms",
        )) {
            assertEquals(tool, ToolEffect.IRREVERSIBLE, ToolEffects.of(tool))
        }
    }

    @Test
    fun `payment and auth tools are sensitive`() {
        for (tool in listOf(
            "send_native_token", "send_token", "swap_tokens",
            "propose_transaction", "propose_token_transfer",
            "create_bankr_order", "write_secure_setting",
        )) {
            assertEquals(tool, ToolEffect.SENSITIVE, ToolEffects.of(tool))
        }
    }

    @Test
    fun `display injection is irreversible while reading the display is not`() {
        for (tool in listOf(
            "agent_display_tap", "agent_display_type_text", "agent_display_click_node",
            "agent_display_set_node_text", "agent_display_press_enter",
            "agent_display_launch_intent",
        )) {
            assertEquals(tool, ToolEffect.IRREVERSIBLE, ToolEffects.of(tool))
        }
        for (tool in listOf(
            "agent_display_look", "agent_display_screenshot", "agent_display_get_ui_tree",
        )) {
            assertEquals(tool, ToolEffect.READ, ToolEffects.of(tool))
        }
    }

    @Test
    fun `isClassified reports whether anything actually named the effect`() {
        assertTrue(ToolEffects.isClassified("get_device_info"))
        assertTrue(ToolEffects.isClassified("memory_read"))
        assertTrue(ToolEffects.isClassified("brand_new", toolDef("brand_new", ToolEffect.READ)))
        assertFalse(ToolEffects.isClassified("brand_new"))
    }

    @Test
    fun `every outbound-message target key names a real parameter`() {
        // Guards against a renamed tool parameter silently turning the
        // reply-to-sender rule into "no target found -> always blocked".
        val known = mapOf(
            "send_xmtp_message" to setOf("recipient_address"),
            "send_sms" to setOf("to"),
            "gmail_send" to setOf("to"),
            "gmail_reply" to setOf("message_id", "thread_id"),
            "reply_to_notification" to setOf("key"),
            "auto_reply_sms" to emptySet(),
        )
        assertEquals(known, ToolEffects.OUTBOUND_MESSAGE_TARGETS)
    }
}
