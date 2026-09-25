package org.ethereumphone.andyclaw.safety

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalSummariesTest {

    @Test
    fun `a send shows recipient and amount exactly, first`() {
        val address = "0x" + "a".repeat(40)
        val s = ApprovalSummaries.of("agent_send_native_token", buildJsonObject {
            put("note", "for lunch")
            put("amount", "0.05")
            put("recipient_address", address)
        })
        assertEquals("Send from the agent wallet", s.title)
        assertEquals(listOf("recipient_address", "amount", "note"), s.params.map { it.key })
        assertEquals(address, s.params[0].value)
        assertEquals("Recipient", s.params[0].label)
        assertTrue(s.summary.contains(address.take(20)))
    }

    @Test
    fun `long text is cut and says so, an address never is`() {
        val s = ApprovalSummaries.of("send_sms", buildJsonObject {
            put("to", "+49 " + "1".repeat(400))
            put("message", "m".repeat(1000))
        })
        val to = s.params.first { it.key == "to" }
        val message = s.params.first { it.key == "message" }
        assertFalse(to.truncated)
        assertTrue(message.truncated)
        assertEquals(1000, message.length)
        assertEquals(ApprovalSummaries.MAX_VALUE, message.value.length)
        assertTrue(ApprovalSummaries.asText(s).contains("(1000 characters)"))
    }

    @Test
    fun `an unknown tool still gets a readable title`() {
        assertEquals("Frobnicate the widget", ApprovalSummaries.of("frobnicate_the_widget", null).title)
    }

    @Test
    fun `who asked is named in the owner's words`() {
        assertEquals("An XMTP message from 0x1234…abcd",
            ApprovalSummaries.sourceLabel("xmtp", "0x1234567890abcdef1234567890abcdef1234abcd"))
        assertEquals("A scheduled task", ApprovalSummaries.sourceLabel("cron", null))
    }
}
