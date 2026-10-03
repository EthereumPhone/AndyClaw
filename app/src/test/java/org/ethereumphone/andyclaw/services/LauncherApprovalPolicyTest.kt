package org.ethereumphone.andyclaw.services

import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.safety.ApprovalSummaries
import org.ethereumphone.andyclaw.safety.ToolEffects
import org.ethereumphone.andyclaw.services.LauncherApprovalPolicy.Decision
import org.ethereumphone.andyclaw.skills.ToolEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** IPC-01: a home-screen turn no longer approves everything it is asked. */
class LauncherApprovalPolicyTest {

    private fun decide(
        effect: ToolEffect,
        tainted: Boolean = false,
        yolo: Boolean = false,
        provenance: Provenance = Provenance.USER,
        lockscreen: Boolean = false,
        tool: String = "some_tool",
    ) = LauncherApprovalPolicy.decide(tool, effect, provenance, tainted, yolo, lockscreen)

    /** The real classification, as the service looks it up. */
    private fun decide(tool: String, tainted: Boolean = false, provenance: Provenance = Provenance.USER, lockscreen: Boolean = false) =
        decide(ToolEffects.of(tool, null), tainted = tainted, provenance = provenance, lockscreen = lockscreen, tool = tool)

    @Test
    fun `a sensitive tool is always queued`() {
        assertEquals(ToolEffect.SENSITIVE, ToolEffects.of("write_secure_setting", null))
        assertEquals(Decision.QUEUE, decide("write_secure_setting"))
        assertEquals(Decision.QUEUE, decide("create_custom_tool"))
        assertEquals(Decision.QUEUE, decide(ToolEffect.SENSITIVE, tainted = true))
    }

    /** The owner's decision: the terminal-screen confirmation is the approval of these. */
    @Test
    fun `the user's own wallet runs for a plain request, straight to the terminal screen`() {
        assertEquals(ToolEffect.SENSITIVE, ToolEffects.of("send_native_token", null))
        for (tool in ToolEffects.USER_WALLET_TOOLS) {
            assertEquals(tool, Decision.RUN, decide(tool))
        }
    }

    @Test
    fun `the user's wallet still waits once the run has read someone else's words, or is not the user's`() {
        assertEquals(Decision.QUEUE, decide("send_native_token", tainted = true))
        assertEquals(Decision.QUEUE, decide("propose_transaction", tainted = true))
        assertEquals(Decision.QUEUE, decide("send_native_token", provenance = Provenance.TRUSTED))
        assertEquals(Decision.QUEUE, decide("send_native_token", lockscreen = true))
    }

    @Test
    fun `the agent's own sub-account keeps the rule it had`() {
        assertEquals(ToolEffect.IRREVERSIBLE, ToolEffects.of("agent_send_native_token", null))
        assertFalse(ToolEffects.USER_WALLET_TOOLS.any { it.startsWith("agent_") })
        assertEquals(Decision.RUN, decide("agent_send_native_token"))
        assertEquals(Decision.QUEUE, decide("agent_send_native_token", tainted = true))
        // Were an agent tool ever SENSITIVE, it would wait: the exception is the user's wallet only.
        assertEquals(Decision.QUEUE, decide(ToolEffect.SENSITIVE, tool = "agent_send_native_token"))
    }

    @Test
    fun `a plain request of the user's own still runs`() {
        // requiresApproval tools (connect_wifi_network, termux_run_command, ...) reach here as IRREVERSIBLE.
        assertEquals(Decision.RUN, decide(ToolEffect.IRREVERSIBLE))
        assertEquals(Decision.RUN, decide(ToolEffect.REVERSIBLE))
    }

    @Test
    fun `once the run has read someone else's words, an approval-needing call waits`() {
        assertEquals(Decision.QUEUE, decide(ToolEffect.IRREVERSIBLE, tainted = true))
        assertEquals(Decision.QUEUE, decide(ToolEffect.REVERSIBLE, tainted = true))
    }

    @Test
    fun `a run that is not the user's own is queued`() {
        assertEquals(Decision.QUEUE, decide(ToolEffect.IRREVERSIBLE, provenance = Provenance.TRUSTED))
        assertEquals(Decision.QUEUE, decide(ToolEffect.IRREVERSIBLE, provenance = Provenance.UNTRUSTED))
    }

    @Test
    fun `yolo approves everything, as in AndyClaw's own chat`() {
        assertEquals(Decision.RUN, decide(ToolEffect.SENSITIVE, yolo = true))
        assertEquals(Decision.RUN, decide(ToolEffect.IRREVERSIBLE, tainted = true, yolo = true))
    }

    @Test
    fun `the lock screen queues whatever yolo says`() {
        assertEquals(Decision.QUEUE, decide(ToolEffect.IRREVERSIBLE, lockscreen = true))
        assertEquals(Decision.QUEUE, decide(ToolEffect.SENSITIVE, yolo = true, lockscreen = true))
    }

    @Test
    fun `the launcher recognises a queued call by its summary`() {
        val summary = LauncherApprovalPolicy.awaitingSummary("Send a text message")
        assertEquals("Waiting for your approval: Send a text message", summary)
        assertTrue(summary.startsWith("Waiting for your approval"))
        assertFalse(LauncherApprovalPolicy.notQueuedSummary("Send a text message").startsWith("Waiting for your approval"))
    }

    @Test
    fun `the model hears that the call waits for approval in this conversation`() {
        val text = LauncherApprovalPolicy.QUEUED_FOR_MODEL
        assertTrue(text.contains("waiting for it in this conversation"))
        assertTrue(text.contains("Do not call it again in this turn"))
        assertTrue(LauncherApprovalPolicy.isRefusalMessage(text))
        assertTrue(LauncherApprovalPolicy.isRefusalMessage(LauncherApprovalPolicy.NOT_QUEUED_FOR_MODEL))
        assertFalse(LauncherApprovalPolicy.isRefusalMessage("Error: something else"))
    }

    @Test
    fun `a card from the home screen says who asked`() {
        assertEquals("You, on the home screen", ApprovalSummaries.sourceLabel("launcher", "L1"))
        assertEquals("The lock screen", ApprovalSummaries.sourceLabel("lockscreen", "S1"))
    }

    /**
     * The shape IPC-01 was: `onApprovalNeeded { Log.i(...); return true }`. Every approval handler
     * in the launcher service must go through the policy, and none may reduce to a bare yes.
     */
    @Test
    fun `no onApprovalNeeded in LauncherBindingService just returns true`() {
        val source = File("src/main/java/org/ethereumphone/andyclaw/services/LauncherBindingService.kt").readText()
        val bodies = Regex("""fun onApprovalNeeded\s*\(""").findAll(source).map { m ->
            val open = source.indexOf('{', source.indexOf(')', m.range.last))
            var depth = 0
            var end = open
            for (i in open until source.length) {
                if (source[i] == '{') depth++
                if (source[i] == '}') { depth--; if (depth == 0) { end = i; break } }
            }
            source.substring(open + 1, end)
        }.toList()
        assertTrue("expected an onApprovalNeeded in LauncherBindingService", bodies.isNotEmpty())
        for (body in bodies) {
            val code = body.lines()
                .map { it.substringBefore("//").trim() }
                .filter { it.isNotEmpty() && !it.startsWith("Log.") }
                .joinToString(" ")
            assertFalse("onApprovalNeeded approves everything again: $code", code == "return true")
            assertTrue("onApprovalNeeded must decide through LauncherApprovalPolicy", body.contains("LauncherApprovalPolicy.decide("))
        }
    }
}
