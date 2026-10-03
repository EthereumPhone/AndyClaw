package org.ethereumphone.andyclaw.flows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the display's answer to a node action says about whether it happened. The strings are the
 * ones `AgentDisplayService` (system_server) and `AgentDisplayAccessibilityService` (this app)
 * actually send.
 */
class FlowDispatchTest {

    private fun of(answer: String?) = FlowDispatch.ofNodeActionResult(answer)

    @Test
    fun `an ok answer is done`() {
        assertEquals(FlowDispatch.DONE, of("""{"ok":true,"method":"a11y"}"""))
        assertEquals(FlowDispatch.DONE, of("""{"ok":true,"method":"tap","x":1.0,"y":2.0}"""))
    }

    @Test
    fun `a refusal given before anything reached the app is not dispatched`() {
        for (answer in listOf(
            """{"ok":false,"error":"Node not found: com.msg:id/send"}""",
            """{"ok":false,"error":"stopped"}""",
            """{"ok":false,"error":"busy"}""",
            """{"ok":false,"error":"AccessibilityService not connected. Check logcat for AgentDisplayService / AgentDisplayA11y tags."}""",
            """{"ok":false,"error":"org.ethereumphone.walletmanager is a private app; the agent does not operate or read it"}""",
            """{"ok":false,"error":"Framework service unavailable for tap fallback"}""",
            """{"ok":false,"error":"field did not take focus"}""",
        )) {
            assertEquals(answer, FlowDispatch.NOT_DISPATCHED, of(answer))
            assertFalse(of(answer).mayHaveHappened)
        }
    }

    @Test
    fun `an answer the OS stopped waiting for, or any other failure, may have happened`() {
        for (answer in listOf(
            """{"ok":false,"error":"timeout","outcome":"unknown"}""",
            """{"ok":false,"error":"interrupted","outcome":"unknown"}""",
            // "outcome":"unknown" wins over a refusal it is attached to.
            """{"ok":false,"error":"Node not found: x","outcome":"unknown"}""",
            """{"ok":false,"error":"proxy_error"}""",
            """{"ok":false,"error":"Proxy call failed: DeadObjectException"}""",
            """{"ok":false,"error":"Tap fallback failed: injection refused"}""",
            """{"ok":false,"error":"Type fallback failed: x"}""",
            """{"ok":false}""",
            """{"method":"a11y"}""",
            "not json",
            "",
            null,
        )) {
            assertEquals("$answer", FlowDispatch.UNKNOWN, of(answer))
            assertTrue(of(answer).mayHaveHappened)
        }
    }

    @Test
    fun `a refusal by the STOP latch is told apart`() {
        assertTrue(FlowDispatch.refusedByStop("""{"ok":false,"error":"stopped"}"""))
        assertFalse(FlowDispatch.refusedByStop("""{"ok":false,"error":"busy"}"""))
        assertFalse(FlowDispatch.refusedByStop("""{"ok":true,"method":"a11y"}"""))
        assertFalse(FlowDispatch.refusedByStop("not json"))
        assertFalse(FlowDispatch.refusedByStop(null))
    }
}
