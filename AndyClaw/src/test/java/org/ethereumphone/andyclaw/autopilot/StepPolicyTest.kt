package org.ethereumphone.andyclaw.autopilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepPolicyTest {

    private val config = AutopilotConfig()
    private val screen = T.screen("com.msg", "Anna",
        T.button(3, "Back", y = 30),
        T.field(5, "Message"),
        T.button(9, "Send", viewId = "com.msg:id/send_button", y = 650, x = 690),
        T.button(12, "Pay now", y = 400),
    )
    private val plan = T.plan(
        PlanStep("Write the message", typeKeys = listOf("body")),
        PlanStep("Send it"),
        values = mapOf("body" to "hi"),
    )
    private val prompt = StepPromptBuilder.build(plan, 0, screen, listOf(HistoryEntry("tapped x", true)))

    private fun decide(
        vararg answers: Pair<String, JevAnswer>,
        subgoal: Int = 0,
        bump: Double = 0.0,
        lastCommitted: Boolean = false,
        undoable: Boolean = true,
    ) = StepPolicy.decide(T.response(*answers), prompt, plan, subgoal, screen, lastCommitted, bump, config, undoable)

    @Test
    fun `acts on a confident pick with a clear margin`() {
        val d = decide(Questions.NEXT to T.choice("type:5:body", 0.9, 0.05))
        assertEquals(StepDecision.Act(StepOption.Type(5, "body"), 0.9), d)
    }

    @Test
    fun `escalates when unsure or when the runner-up is close`() {
        assertEquals(StepDecision.Escalate("low_confidence"), decide(Questions.NEXT to T.choice("tap:3", 0.7)))
        assertEquals(StepDecision.Escalate("low_confidence"), decide(Questions.NEXT to T.choice("tap:3", 0.85, 0.75)))
    }

    @Test
    fun `a missed state demands more confidence`() {
        assertEquals(StepDecision.Escalate("low_confidence"),
            decide(Questions.NEXT to T.choice("tap:3", 0.82, 0.0), bump = 0.05))
    }

    @Test
    fun `commit-like actions need the higher bar`() {
        val send = T.choice("tap:9", 0.9, 0.0)
        assertEquals(StepDecision.Escalate("low_confidence_commit"), decide(Questions.NEXT to send))
        val sure = decide(Questions.NEXT to T.choice("tap:9", 0.95, 0.0))
        assertTrue(sure is StepDecision.Act && sure.commits)
        // Jev's own judgement that the action commits raises the bar too.
        assertEquals(StepDecision.Escalate("low_confidence_commit"),
            decide(Questions.NEXT to T.choice("tap:3", 0.9, 0.0), Questions.COMMITS to JevAnswer.Noul(0.8)))
    }

    @Test
    fun `payment and auth elements are never tapped by the autopilot`() {
        assertEquals(StepDecision.Escalate("sensitive"), decide(Questions.NEXT to T.choice("tap:12", 0.99, 0.0)))
    }

    @Test
    fun `a blocker overrides everything`() {
        assertEquals(StepDecision.Escalate("blocker:login"), decide(
            Questions.NEXT to T.choice("tap:3", 0.99),
            Questions.GOAL_DONE to JevAnswer.Noul(0.99),
            Questions.BLOCKER to T.choice("login", 0.8, 0.2, "none"),
        ))
        assertTrue(decide(Questions.NEXT to T.choice("tap:3", 0.99),
            Questions.BLOCKER to T.choice("login", 0.5, 0.5, "none")) is StepDecision.Act)
    }

    @Test
    fun `goal done needs more certainty before the last sub-goal`() {
        val early = decide(Questions.NEXT to T.choice("tap:3", 0.99), Questions.GOAL_DONE to JevAnswer.Noul(0.9))
        assertTrue("0.9 is not enough on sub-goal 1 of 2", early is StepDecision.Act)
        assertEquals(StepDecision.Done, decide(Questions.GOAL_DONE to JevAnswer.Noul(0.9), subgoal = 1))
    }

    @Test
    fun `a finished sub-goal acts on the following answer without another round trip`() {
        val d = decide(
            Questions.SUBGOAL_DONE to JevAnswer.Noul(0.9),
            Questions.NEXT_FOLLOWING to T.choice("tap:9", 0.97, 0.0),
        )
        assertTrue(d is StepDecision.Act && d.advancesSubgoal && d.option == StepOption.Tap(9))
        assertEquals(StepDecision.AdvanceAndReask, decide(
            Questions.SUBGOAL_DONE to JevAnswer.Noul(0.9),
            Questions.NEXT_FOLLOWING to T.choice("tap:9", 0.5),
        ))
    }

    @Test
    fun `an action that did not work is undone, unless it committed`() {
        assertEquals(StepDecision.Undo, decide(Questions.NEXT to T.choice("tap:3", 0.99), Questions.LAST_PROGRESS to JevAnswer.Noul(0.1)))
        assertTrue(decide(Questions.NEXT to T.choice("tap:3", 0.99), Questions.LAST_PROGRESS to JevAnswer.Noul(0.1),
            lastCommitted = true) is StepDecision.Act)
    }

    @Test
    fun `nothing is undone when the last move was itself an undo, or there was none`() {
        val d = decide(Questions.NEXT to T.choice("tap:3", 0.99), Questions.LAST_PROGRESS to JevAnswer.Noul(0.1), undoable = false)
        assertTrue(d is StepDecision.Act)
    }

    @Test
    fun `wait and none map to waiting and escalation`() {
        assertEquals(StepDecision.Wait, decide(Questions.NEXT to T.choice("wait", 0.9)))
        assertEquals(StepDecision.Escalate("no_option"), decide(Questions.NEXT to T.choice("none", 0.9)))
        assertEquals(StepDecision.Escalate("jev_no_answer"), decide())
    }
}
