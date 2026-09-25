package org.ethereumphone.andyclaw.ui.autopilot

import org.ethereumphone.andyclaw.autopilot.AutopilotEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutopilotUiStateTest {

    private fun event(kind: AutopilotEvent.Kind, outcome: String? = null, message: String? = null, reason: String? = null) =
        AutopilotEvent(kind, "run-1", step = 3, subgoalIndex = 1, subgoals = listOf("a", "b"),
            reason = reason, outcome = outcome, message = message)

    private val running = AutopilotUiState("run-1").reduce(event(AutopilotEvent.Kind.STARTED))

    @Test
    fun `a hand-over keeps STOP and is not an end`() {
        val s = running.reduce(event(AutopilotEvent.Kind.FAILED, "handoff", "Taking longer than expected, handing over", "step_budget"))
        assertEquals(AutopilotUiState.Phase.HANDOFF, s.phase)
        assertFalse(s.finished)
        assertEquals("Taking longer than expected, handing over", s.message)
    }

    @Test
    fun `the end of the turn closes a hand-over`() {
        val s = running.reduce(event(AutopilotEvent.Kind.FAILED, "handoff")).endOfTurn()
        assertEquals(AutopilotUiState.Phase.ENDED, s.phase)
        assertTrue(s.finished)
        assertNotNull(s.finishedAtMs)
    }

    @Test
    fun `a user stop is its own ending, not a failure`() {
        val s = running.reduce(event(AutopilotEvent.Kind.FAILED, "stopped", "Stopped", "stopped_by_user"))
        assertEquals(AutopilotUiState.Phase.STOPPED, s.phase)
        assertTrue(s.finished)
    }

    @Test
    fun `a cancelled turn finishes a run still running`() {
        val s = running.endOfTurn(stopped = true)
        assertEquals(AutopilotUiState.Phase.STOPPED, s.phase)
        assertTrue(s.finished)
    }

    @Test
    fun `an old event without an outcome still reads as a failure`() {
        val s = running.reduce(event(AutopilotEvent.Kind.FAILED, reason = "blocker:login"))
        assertEquals(AutopilotUiState.Phase.FAILED, s.phase)
    }

    @Test
    fun `a finished run is left alone by the end of the turn`() {
        val done = running.reduce(event(AutopilotEvent.Kind.DONE, "success", "Done"))
        assertEquals(done, done.endOfTurn())
    }
}
