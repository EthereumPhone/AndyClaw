package org.ethereumphone.andyclaw.autopilot

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole loop against a fake messaging app: Jev drives, the planner is only asked when Jev
 * is unsure, and every way out comes back with a reason.
 */
class AutopilotExecutorTest {

    private val list = T.screen("com.msg", "Chats",
        T.button(1, "Search", y = 30, type = "icon_button"),
        T.button(2, "Bob", y = 200, type = "list_item"),
        T.button(3, "Anna", y = 280, type = "list_item"),
    )
    private val thread = T.screen("com.msg", "Anna",
        T.button(1, "Back", y = 30, type = "icon_button"),
        T.field(5, "Message"),
        T.button(9, "Send", y = 650, x = 690, type = "icon_button"),
    )
    private val typed = thread.copy(elements = thread.elements.map { if (it.id == 5) it.copy(value = "hi") else it })
    private val sent = T.screen("com.msg", "Anna",
        T.button(1, "Back", y = 30, type = "icon_button"),
        T.text(7, "hi · Sent", y = 500),
        T.field(5, "Message"),
    )

    private fun device() = FakeDevice(
        screens = mapOf("list" to list, "thread" to thread, "typed" to typed, "sent" to sent, "bob" to thread.copy(title = "Bob")),
        transitions = mapOf(
            "list|tap:Anna" to "thread",
            "list|tap:Bob" to "bob",
            "bob|back" to "list",
            "thread|type:Message" to "typed",
            "typed|tap:Send" to "sent",
        ),
        start = "list",
    )

    private val plan = AutopilotPlan(
        packageName = "com.msg",
        goal = "Send 'hi' to Anna",
        steps = listOf(
            PlanStep("Open the conversation with Anna", doneWhen = "Anna's conversation is open"),
            PlanStep("Write the message", typeKeys = listOf("body"), doneWhen = "the field contains the text"),
            PlanStep("Send it", doneWhen = "the message shows as sent"),
        ),
        values = mapOf("body" to "hi"),
        say = "Sent \"hi\" to Anna.",
    )

    private fun competentJev() = ScriptedJev(::competentAnswers)

    /** A competent Jev: reads the sub-goal and the screen title the way the real model would. */
    private fun competentAnswers(req: JevRequest): Map<String, JevAnswer> {
        val s = req.state
        val sub = Regex("SUB-GOAL (\\d)/").find(s)!!.groupValues[1].toInt()
        val title = Regex("title=\"([^\"]*)\"").find(s)?.groupValues?.get(1)
        val answers = mutableMapOf<String, JevAnswer>()
        when {
            s.contains("hi · Sent") -> answers[Questions.GOAL_DONE] = JevAnswer.Noul(0.97)
            sub == 1 && title == "Anna" -> {
                answers[Questions.SUBGOAL_DONE] = JevAnswer.Noul(0.95)
                answers[Questions.NEXT_FOLLOWING] = T.choice(ScriptedJev.keyFor(req, Questions.NEXT_FOLLOWING, "Type", "Message")!!, 0.95)
            }
            sub == 1 -> answers[Questions.NEXT] = T.choice(ScriptedJev.keyFor(req, Questions.NEXT, "Tap", "Anna")!!, 0.93)
            sub == 2 && s.contains("value:\"hi\"") -> {
                answers[Questions.SUBGOAL_DONE] = JevAnswer.Noul(0.9)
                answers[Questions.NEXT_FOLLOWING] = T.choice(ScriptedJev.keyFor(req, Questions.NEXT_FOLLOWING, "Tap", "Send")!!, 0.96)
            }
            sub == 2 -> answers[Questions.NEXT] = T.choice(ScriptedJev.keyFor(req, Questions.NEXT, "Type", "Message")!!, 0.95)
            sub == 3 -> answers[Questions.NEXT] = T.choice(ScriptedJev.keyFor(req, Questions.NEXT, "Tap", "Send")!!, 0.96)
        }
        answers[Questions.BLOCKER] = T.choice("none", 0.95, 0.05, "login")
        return answers
    }

    private val noPlanner = AutopilotPlanner { error("planner must not be needed") }

    @Test
    fun `drives the whole task with Jev alone and never asks the planner`() = runTest {
        val device = device()
        val events = mutableListOf<AutopilotEvent>()
        val jev = competentJev()
        val result = AutopilotExecutor(device, jev, noPlanner, events = { events += it }).run(plan)

        assertEquals(AutopilotResult.Status.SUCCESS, result.status)
        assertEquals(listOf("tap:Anna", "type:Message", "tap:Send"), device.performed)
        assertEquals(listOf("Message" to "hi"), device.typed)
        assertEquals("Sent \"hi\" to Anna.", result.say)
        assertEquals(0, result.plannerCalls)
        assertEquals("one Jev call per screen, sub-goal hand-offs included", 4, result.jevCalls)
        assertEquals(3, result.actions.size)
        assertTrue(events.any { it.kind == AutopilotEvent.Kind.ACTING && it.target?.label == "Send" })
        assertEquals(AutopilotEvent.Kind.DONE, events.last().kind)
        assertTrue(result.toToolResultJson().contains("\"status\":\"success\""))
    }

    @Test
    fun `asks the planner when Jev is unsure and follows its pick`() = runTest {
        val device = device()
        val jev = ScriptedJev { req ->
            if (req.state.contains("title=\"Chats\"")) {
                mapOf(Questions.NEXT to T.choice(ScriptedJev.keyFor(req, Questions.NEXT, "Tap", "Anna")!!, 0.55, 0.4, "tap:2"))
            } else competentAnswers(req)
        }
        val contexts = mutableListOf<PlannerContext>()
        val planner = AutopilotPlanner { ctx ->
            contexts += ctx
            PlannerDecision.Act(ctx.options.entries.first { it.value.contains("\"Anna\"") }.key)
        }
        val result = AutopilotExecutor(device, jev, planner).run(plan)

        assertEquals(AutopilotResult.Status.SUCCESS, result.status)
        assertEquals(1, result.plannerCalls)
        assertEquals("low_confidence", contexts.single().reason)
        assertEquals("tap:Anna", device.performed.first())
    }

    @Test
    fun `a login wall goes back to the planner with a reason and a screen summary`() = runTest {
        val jev = ScriptedJev { mapOf(Questions.BLOCKER to T.choice("login", 0.9, 0.1, "none")) }
        val result = AutopilotExecutor(device(), jev, planner = null).run(plan)
        assertEquals(AutopilotResult.Status.NEEDS_PLANNER, result.status)
        assertEquals("blocker:login", result.reason)
        assertTrue(result.screenSummary!!.contains("Anna"))
        assertTrue(result.toToolResultJson().contains("\"screen\""))
    }

    @Test
    fun `a wrong pick is undone and not repeated`() = runTest {
        val device = device()
        var sawBob = false
        val jev = ScriptedJev { req ->
            val s = req.state
            when {
                s.contains("title=\"Bob\"") -> {
                    sawBob = true
                    mapOf(Questions.LAST_PROGRESS to JevAnswer.Noul(0.05))
                }
                s.contains("title=\"Chats\"") && !sawBob ->
                    mapOf(Questions.NEXT to T.choice(ScriptedJev.keyFor(req, Questions.NEXT, "Tap", "Bob")!!, 0.9))
                else -> competentAnswers(req)
            }
        }
        val result = AutopilotExecutor(device, jev, noPlanner).run(plan)
        assertEquals(AutopilotResult.Status.SUCCESS, result.status)
        assertEquals(listOf("tap:Bob", "back", "tap:Anna", "type:Message", "tap:Send"), device.performed)
        // Back on the list, the Bob option was withdrawn.
        val afterUndo = jev.requests.last { it.state.contains("title=\"Chats\"") }
        assertTrue(ScriptedJev.keyFor(afterUndo, Questions.NEXT, "Tap", "Bob") == null)
    }

    @Test
    fun `taps that change nothing end as stuck instead of looping`() = runTest {
        val device = FakeDevice(mapOf("list" to list), emptyMap(), "list")
        val jev = ScriptedJev { req ->
            mapOf(Questions.NEXT to T.choice((req.questions[Questions.NEXT] as JevQuestion.Choice).options.keys.first(), 0.95))
        }
        val result = AutopilotExecutor(device, jev, planner = null).run(plan)
        assertEquals(AutopilotResult.Status.NEEDS_PLANNER, result.status)
        assertEquals("stuck", result.reason)
        assertEquals(2, device.performed.size)
    }

    @Test
    fun `Jev outage falls back to the planner for each step`() = runTest {
        val device = device()
        val failing = JevClient { throw JevUnavailableException("disabled", 503) }
        var calls = 0
        val planner = AutopilotPlanner { ctx ->
            calls++
            when (ctx.screen.title) {
                "Chats" -> PlannerDecision.Act(ctx.options.entries.first { it.value.contains("\"Anna\"") }.key)
                else -> PlannerDecision.Done
            }
        }
        val result = AutopilotExecutor(device, failing, planner).run(plan)
        assertEquals(AutopilotResult.Status.SUCCESS, result.status)
        assertEquals(2, calls)
        assertTrue(result.escalations.all { it == "jev_unavailable" })
    }

    @Test
    fun `a stop request ends the run before the next action`() = runTest {
        val device = object : AutopilotDevice by device() {
            var actions = 0
            override val stopRequested get() = actions >= 1
            override suspend fun perform(option: StepOption, screen: ScreenSnapshot, plan: AutopilotPlan): ActionOutcome {
                actions++
                return ActionOutcome(ok = true, changedScreen = true)
            }
        }
        val result = AutopilotExecutor(device, competentJev(), noPlanner).run(plan)
        assertEquals(AutopilotResult.Status.FAILED, result.status)
        assertEquals("stopped_by_user", result.reason)
        assertEquals(1, device.actions)
    }

    @Test
    fun `the planner cannot steer the autopilot into a payment button`() = runTest {
        val payScreen = T.screen("com.shop", "Cart", T.button(4, "Pay now"))
        val device = FakeDevice(mapOf("cart" to payScreen), emptyMap(), "cart")
        val planner = AutopilotPlanner { ctx -> PlannerDecision.Act("tap:4") }
        val result = AutopilotExecutor(device, jev = null, planner = planner)
            .run(plan.copy(packageName = "com.shop"))
        assertEquals("sensitive", result.reason)
        assertTrue(device.performed.isEmpty())
    }
}
