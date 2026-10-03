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
    fun `a name on the old read-only list is not read-only by its name alone`() {
        // get_clipboard is on ToolAttenuation.READ_ONLY_TOOLS but no builtin tool has that name:
        // an extension that called itself get_clipboard used to pass the gate as READ.
        assertFalse("test premise", "get_clipboard" in ToolEffects.BUILTIN)
        assertTrue("test premise", "get_clipboard" in ToolAttenuation.READ_ONLY_TOOLS)
        assertEquals(ToolEffect.IRREVERSIBLE, ToolEffects.of("get_clipboard"))
        assertEquals(ToolEffect.IRREVERSIBLE, ToolEffects.of("memory_read"))
    }

    @Test
    fun `scheduling and standing instructions need approval from an untrusted run`() {
        for (tool in listOf(
            "create_cronjob", "cancel_cronjob", "create_reminder", "cancel_reminder",
            "memory_store", "refinement_create", "cli_tools_add", "cli_tools_configure",
        )) {
            assertEquals(tool, ToolEffect.IRREVERSIBLE, ToolEffects.of(tool))
        }
    }

    @Test
    fun `every clipboard read is private data`() {
        assertTrue(ToolEffects.PRIVATE_DATA_TOOLS.containsAll(ToolEffects.CLIPBOARD_READS))
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
            "create_bankr_order", "write_secure_setting", "write_global_setting",
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
        assertFalse(ToolEffects.isClassified("memory_read"))
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

    @Test
    fun `what re-aims a payment or mails an invitation is irreversible`() {
        // A new contact is a payment target as much as a rewritten one; an event with invitees
        // is mailed to them.
        for (tool in listOf("create_contact", "set_eth_address", "update_contact", "create_event", "gcal_create_event")) {
            assertEquals(tool, ToolEffect.IRREVERSIBLE, ToolEffects.of(tool))
        }
    }

    @Test
    fun `what cannot be put back is irreversible, and deleting an app's data is sensitive`() {
        // An overwritten clipboard or a dismissed notification does not come back, and a joined
        // network carries the device's traffic.
        for (tool in listOf("write_clipboard", "agent_display_set_clipboard", "dismiss_notification", "connect_wifi_network")) {
            assertEquals(tool, ToolEffect.IRREVERSIBLE, ToolEffects.of(tool))
        }
        for (tool in listOf("uninstall_app", "clear_app_data")) {
            assertEquals(tool, ToolEffect.SENSITIVE, ToolEffects.of(tool))
        }
    }

    @Test
    fun `a tool that is not a builtin counts as reading private data`() {
        assertTrue(ToolEffects.readsPrivateData("read_sms"))
        assertTrue("a custom tool cannot say what it reads", ToolEffects.readsPrivateData("sms_digest"))
        assertTrue("the home network's name and the device's addresses", ToolEffects.readsPrivateData("get_connectivity_status"))
        assertFalse(ToolEffects.readsPrivateData("get_device_info"))
    }

    @Test
    fun `every conditional-egress key names a real parameter`() {
        assertEquals(
            mapOf(
                "create_event" to setOf("participants", "calendar_id"),
                "gcal_create_event" to setOf("attendees", "calendar_id"),
            ),
            ToolEffects.CONDITIONAL_EGRESS,
        )
    }
}
