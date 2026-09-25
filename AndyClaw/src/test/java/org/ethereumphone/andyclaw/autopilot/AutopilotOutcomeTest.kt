package org.ethereumphone.andyclaw.autopilot

import org.ethereumphone.andyclaw.autopilot.AutopilotOutcome.Outcome
import org.ethereumphone.andyclaw.autopilot.AutopilotResult.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** What the live view, the launcher card and the HUD say about how a run ended. */
class AutopilotOutcomeTest {

    @Test
    fun `a hand-over is not a failure`() {
        assertEquals(Outcome.HANDOFF, AutopilotOutcome.of(Status.NEEDS_PLANNER, "step_budget"))
        assertEquals("handoff", Outcome.HANDOFF.wire)
        assertEquals("Taking longer than expected, handing over", AutopilotOutcome.message(Status.NEEDS_PLANNER, "step_budget"))
    }

    @Test
    fun `STOP and cancel are their own outcomes`() {
        assertEquals(Outcome.STOPPED, AutopilotOutcome.of(Status.FAILED, AutopilotOutcome.REASON_STOPPED))
        assertEquals(Outcome.CANCELLED, AutopilotOutcome.of(Status.FAILED, AutopilotOutcome.REASON_CANCELLED))
        assertEquals("Stopped", AutopilotOutcome.message(Status.FAILED, AutopilotOutcome.REASON_STOPPED, "Stopped."))
    }

    @Test
    fun `blockers read as what the user has to do`() {
        assertEquals("Needs you to sign in", AutopilotOutcome.message(Status.NEEDS_PLANNER, "blocker:login"))
        assertEquals("Something on screen is in the way", AutopilotOutcome.message(Status.NEEDS_PLANNER, "blocker:weird"))
    }

    @Test
    fun `no message is ever a raw reason code`() {
        val reasons = listOf("step_budget", "time_budget", "subgoal_budget", "loop", "stuck", "stuck_loading",
            "planner_error", "planner_bad_json", "action_failed:tap", "sensitive", "sensitive_app", "app_unavailable",
            "app_not_installed", "screen_unreadable", "internal_error", "jev_unavailable", "no_option", "empty_replan")
        for (status in Status.values()) for (reason in reasons) {
            val message = AutopilotOutcome.message(status, reason)
            assertFalse("$status/$reason -> $message", message.contains('_') || message.contains(':'))
        }
    }

    @Test
    fun `a planner's own abort keeps what it said for the user`() {
        assertEquals("The shop is closed today.", AutopilotOutcome.message(Status.FAILED, "closed", "The shop is closed today."))
        assertEquals("Couldn't finish this", AutopilotOutcome.message(Status.FAILED, "closed"))
    }

    @Test
    fun `success is done`() {
        assertEquals(Outcome.SUCCESS, AutopilotOutcome.of(Status.SUCCESS, null))
        assertEquals("Done", AutopilotOutcome.message(Status.SUCCESS, null, "Sent."))
    }
}
