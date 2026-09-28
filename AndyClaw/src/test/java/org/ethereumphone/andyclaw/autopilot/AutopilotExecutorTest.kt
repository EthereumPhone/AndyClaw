package org.ethereumphone.andyclaw.autopilot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

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

    // ---- STOP ----

    /** The fake device, with STOP wired to a flag the test flips. */
    private class Stoppable(val fake: FakeDevice, val stop: AtomicBoolean = AtomicBoolean(false)) :
        AutopilotDevice by fake {
        override val stopRequested get() = stop.get()
    }

    @Test
    fun `STOP while Jev is still thinking abandons the call and nothing is tapped`() = runTest {
        val device = Stoppable(device())
        val jev = JevClient {
            device.stop.set(true)
            awaitCancellation()
        }
        val events = mutableListOf<AutopilotEvent>()
        val result = AutopilotExecutor(device, jev, noPlanner, events = { events += it }).run(plan)

        assertEquals("stopped_by_user", result.reason)
        assertEquals(AutopilotOutcome.Outcome.STOPPED, result.outcome)
        assertTrue(device.fake.performed.isEmpty())
        assertEquals("stopped", events.last().outcome)
    }

    @Test
    fun `STOP pressed while Jev answered means its pick is never performed`() = runTest {
        val device = Stoppable(device())
        val jev = ScriptedJev { req ->
            device.stop.set(true)
            competentAnswers(req)
        }
        val result = AutopilotExecutor(device, jev, noPlanner).run(plan)
        assertEquals("stopped_by_user", result.reason)
        assertTrue(device.fake.performed.isEmpty())
    }

    @Test
    fun `STOP while the planner is deciding means its pick is never performed`() = runTest {
        val device = Stoppable(device())
        val planner = AutopilotPlanner { ctx ->
            device.stop.set(true)
            PlannerDecision.Act(ctx.options.entries.first { it.value.contains("\"Anna\"") }.key)
        }
        val result = AutopilotExecutor(device, jev = null, planner = planner).run(plan)
        assertEquals("stopped_by_user", result.reason)
        assertTrue(device.fake.performed.isEmpty())
    }

    @Test
    fun `STOP between steps ends the run before the next action`() = runTest {
        val device = Stoppable(device())
        val result = AutopilotExecutor(device, ScriptedJev { req ->
            if (device.fake.performed.size == 1) device.stop.set(true)
            competentAnswers(req)
        }, noPlanner).run(plan)
        assertEquals("stopped_by_user", result.reason)
        assertEquals(listOf("tap:Anna"), device.fake.performed)
    }

    // ---- Every way out ends the run ----

    @Test
    fun `a planner that throws hands the task back with a terminal event`() = runTest {
        val events = mutableListOf<AutopilotEvent>()
        val planner = AutopilotPlanner { throw RuntimeException("HTTP 500") }
        val result = AutopilotExecutor(device(), jev = null, planner = planner, events = { events += it }).run(plan)

        assertEquals(AutopilotResult.Status.NEEDS_PLANNER, result.status)
        assertEquals("planner_error", result.reason)
        assertEquals(AutopilotOutcome.Outcome.HANDOFF, result.outcome)
        assertEquals(AutopilotEvent.Kind.FAILED, events.last().kind)
        assertEquals("handoff", events.last().outcome)
        assertTrue(result.toToolResultJson().contains("\"outcome\":\"handoff\""))
    }

    @Test
    fun `a planner answer that makes no sense hands back instead of failing`() = runTest {
        val planner = AutopilotPlanner { PlannerDecision.Unusable("planner_bad_json") }
        val result = AutopilotExecutor(device(), jev = null, planner = planner).run(plan)
        assertEquals(AutopilotResult.Status.NEEDS_PLANNER, result.status)
        assertEquals("planner_bad_json", result.reason)
    }

    @Test
    fun `a cancelled run still tells every view it ended`() = runTest {
        val events = java.util.Collections.synchronizedList(mutableListOf<AutopilotEvent>())
        val inFlight = CompletableDeferred<Unit>()
        val jev = JevClient {
            inFlight.complete(Unit)
            awaitCancellation()
        }
        val job = launch { AutopilotExecutor(device(), jev, noPlanner, events = { events += it }).run(plan) }
        inFlight.await()
        job.cancelAndJoin()

        assertEquals(AutopilotEvent.Kind.FAILED, events.last().kind)
        assertEquals("cancelled", events.last().reason)
        assertEquals("cancelled", events.last().outcome)
    }

    @Test
    fun `an unexpected exception ends the run as a failure, not a hang`() = runTest {
        val device = object : AutopilotDevice by device() {
            override suspend fun snapshot(): ScreenSnapshot = throw IllegalStateException("binder died")
        }
        val events = mutableListOf<AutopilotEvent>()
        val result = AutopilotExecutor(device, competentJev(), noPlanner, events = { events += it }).run(plan)
        assertEquals(AutopilotResult.Status.FAILED, result.status)
        assertEquals("internal_error", result.reason)
        assertEquals(AutopilotEvent.Kind.FAILED, events.last().kind)
    }

    @Test
    fun `an app that is not installed fails at once and says so`() = runTest {
        val fake = device()
        val device = object : AutopilotDevice by fake {
            override suspend fun isLaunchable(packageName: String) = false
        }
        val result = AutopilotExecutor(device, competentJev(), noPlanner).run(plan)
        assertEquals("app_not_installed", result.reason)
        assertNull("never launched", fake.launched)
        assertTrue(result.say!!.contains("com.msg"))
    }

    // ---- Undo, reads, Jev outages ----

    @Test
    fun `an undo is never itself undone`() = runTest {
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
                // Back on the list, Jev judges the Back press as "no progress" too.
                s.contains("title=\"Chats\"") -> competentAnswers(req) + (Questions.LAST_PROGRESS to JevAnswer.Noul(0.05))
                else -> competentAnswers(req)
            }
        }
        val result = AutopilotExecutor(device, jev, noPlanner).run(plan)
        assertEquals(AutopilotResult.Status.SUCCESS, result.status)
        assertEquals(listOf("tap:Bob", "back", "tap:Anna", "type:Message", "tap:Send"), device.performed)
    }

    @Test
    fun `a screen that cannot be read once is read again, not failed`() = runTest {
        val fake = device()
        var misses = 2
        val device = object : AutopilotDevice by fake {
            override suspend fun snapshot(): ScreenSnapshot? = if (misses-- > 0) null else fake.snapshot()
        }
        val result = AutopilotExecutor(device, competentJev(), noPlanner).run(plan)
        assertEquals(AutopilotResult.Status.SUCCESS, result.status)
    }

    @Test
    fun `a screen caught empty mid-transition is read again before Jev sees it`() = runTest {
        val fake = device()
        var empties = 2
        val device = object : AutopilotDevice by fake {
            override suspend fun snapshot(): ScreenSnapshot? =
                if (empties-- > 0) T.screen("com.msg", "Chats") else fake.snapshot()
        }
        val jev = competentJev()
        val result = AutopilotExecutor(device, jev, noPlanner).run(plan)
        assertEquals(AutopilotResult.Status.SUCCESS, result.status)
        assertTrue("Jev was shown an empty screen", jev.requests.first().state.contains("\"Anna\""))
    }

    @Test
    fun `a sub-goal the planner finds already met is moved past, not handed back`() = runTest {
        val device = device()
        val jev = JevClient { throw JevUnavailableException("no wallet sign-in") }
        // The model's plan opens with a step the autopilot has already done by launching the app.
        val stale = plan.copy(steps = listOf(PlanStep("Open the messaging app", doneWhen = "the chat list shows")) + plan.steps)
        val asked = mutableListOf<Int>()
        val planner = AutopilotPlanner { ctx ->
            asked += ctx.subgoalIndex
            fun pick(verb: String, label: String) =
                PlannerDecision.Act(ctx.options.entries.first { it.value.startsWith(verb) && it.value.contains("\"$label\"") }.key)
            when {
                ctx.subgoalIndex == 0 -> PlannerDecision.SubgoalDone
                ctx.screen.elements.any { it.label?.contains("Sent") == true } -> PlannerDecision.Done
                ctx.screen.title == "Chats" -> pick("Tap", "Anna")
                ctx.screen.elements.any { it.value == "hi" } -> pick("Tap", "Send")
                else -> pick("Type", "Message")
            }
        }
        val result = AutopilotExecutor(device, jev, planner).run(stale)
        assertEquals(AutopilotResult.Status.SUCCESS, result.status)
        assertEquals(listOf("tap:Anna", "type:Message", "tap:Send"), device.performed)
        assertEquals("asked about the stale sub-goal once", 1, asked.count { it == 0 })
    }

    // ---- Pages longer than the screen ----

    /** A page of six rows, two at a time, and a list that scrolls between the three positions. */
    private fun longPage(): FakeDevice {
        val list = ScreenElement(id = 9, type = "list", label = "scrollable list",
            actions = listOf("scroll_forward", "scroll_backward"), centerX = 360, centerY = 400,
            left = 0, top = 100, right = 720, bottom = 700)
        // A search bar that stays put while the list scrolls under it.
        fun part(vararg rows: String) = T.screen("com.app", "Home", T.button(0, "Search", y = 40, type = "search_bar"),
            *rows.mapIndexed { i, r -> T.button(i + 1, r, y = 200 + i * 100, type = "menu_item") }.toTypedArray(), list)
        return FakeDevice(
            screens = mapOf("top" to part("A", "B"), "mid" to part("C", "D"), "end" to part("E", "F"),
                "d" to T.screen("com.app", "D page", T.text(1, "Inside D"))),
            transitions = mapOf(
                "top|scroll_fwd:9" to "mid", "mid|scroll_fwd:9" to "end",
                "end|scroll_back:9" to "mid", "mid|scroll_back:9" to "top",
                "mid|tap:D" to "d",
            ),
            start = "top",
        )
    }

    private val openD = AutopilotPlan(packageName = "com.app", goal = "Open D",
        steps = listOf(PlanStep("Open the D page", doneWhen = "the D page shows")))

    @Test
    fun `an unsure step on a long page is answered by scanning it, not by the planner`() = runTest {
        val device = longPage()
        val jev = ScriptedJev { req ->
            val s = req.state
            when {
                s.contains("title=\"D page\"") -> mapOf(Questions.GOAL_DONE to JevAnswer.Noul(0.97))
                ScriptedJev.keyFor(req, Questions.NEXT, "Tap", "D") != null ->
                    mapOf(Questions.NEXT to T.choice(ScriptedJev.keyFor(req, Questions.NEXT, "Tap", "D")!!, 0.95))
                ScriptedJev.keyFor(req, Questions.NEXT, "Scroll to bring", "D") != null ->
                    mapOf(Questions.NEXT to T.choice(ScriptedJev.keyFor(req, Questions.NEXT, "Scroll to bring", "D")!!, 0.93))
                // Only part of the page seen: a guess, and Jev knows it - too close to call.
                else -> mapOf(Questions.NEXT to T.choice("scroll_fwd:9", 0.45, runnerUp = 0.35))
            }
        }
        val result = AutopilotExecutor(device, jev, noPlanner).run(openD)
        assertEquals(AutopilotResult.Status.SUCCESS, result.status)
        assertEquals(0, result.plannerCalls)
        // Scanned to the end (and one scroll that moved nothing), came back up to D, tapped it.
        assertEquals(listOf("scroll_fwd:9", "scroll_fwd:9", "scroll_fwd:9", "scroll_back:9", "tap:D"), device.performed)
        val offered = jev.requests.first { it.state.contains("Further up this page") }.state
        assertTrue("rows above are listed once the page is scanned", offered.contains("\"D\""))
    }

    @Test
    fun `a screen that lags behind the display is read until it catches up`() = runTest {
        val fake = device()
        // The new window reaches accessibility late: the first two reads after an action that
        // changed the display still show the screen the action was taken on.
        var stale: ScreenSnapshot? = null
        var lag = 0
        val device = object : AutopilotDevice by fake {
            override suspend fun perform(option: StepOption, screen: ScreenSnapshot, plan: AutopilotPlan): ActionOutcome {
                val outcome = fake.perform(option, screen, plan)
                if (outcome.changedScreen) { stale = screen; lag = 2 }
                return outcome
            }
            override suspend fun snapshot(): ScreenSnapshot? =
                if (lag-- > 0) stale else fake.snapshot()
        }
        val result = AutopilotExecutor(device, competentJev(), noPlanner).run(plan)
        assertEquals(AutopilotResult.Status.SUCCESS, result.status)
        assertEquals("nothing was done twice", listOf("tap:Anna", "type:Message", "tap:Send"), fake.performed)
    }

    @Test
    fun `scrolling to a row does not use up the sub-goal's actions, and the last one is judged`() = runTest {
        val device = longPage()
        val jev = ScriptedJev { req ->
            when {
                req.state.contains("title=\"D page\"") -> mapOf(Questions.GOAL_DONE to JevAnswer.Noul(0.97))
                ScriptedJev.keyFor(req, Questions.NEXT, "Tap", "D") != null ->
                    mapOf(Questions.NEXT to T.choice(ScriptedJev.keyFor(req, Questions.NEXT, "Tap", "D")!!, 0.95))
                else -> mapOf(Questions.NEXT to T.choice("scroll_fwd:9", 0.9))
            }
        }
        // One action per sub-goal: the tap that opens D. The scroll before it is not an action.
        val result = AutopilotExecutor(device, jev, noPlanner, config = AutopilotConfig(maxStepsPerSubgoal = 1)).run(openD)
        assertEquals(AutopilotResult.Status.SUCCESS, result.status)
        assertEquals(listOf("scroll_fwd:9", "tap:D"), device.performed)
    }

    @Test
    fun `a run without a reply hands back the text of the screen it ended on`() = runTest {
        val jev = ScriptedJev { req ->
            when {
                req.state.contains("title=\"D page\"") -> mapOf(Questions.GOAL_DONE to JevAnswer.Noul(0.97))
                ScriptedJev.keyFor(req, Questions.NEXT, "Tap", "D") != null ->
                    mapOf(Questions.NEXT to T.choice(ScriptedJev.keyFor(req, Questions.NEXT, "Tap", "D")!!, 0.95))
                else -> mapOf(Questions.NEXT to T.choice("scroll_fwd:9", 0.9))
            }
        }
        val result = AutopilotExecutor(longPage(), jev, noPlanner).run(openD)
        assertTrue(result.toToolResultJson().contains("\"screen_text\":\"Inside D\""))
        val said = AutopilotExecutor(longPage(), jev, noPlanner).run(openD.copy(say = "Opened D."))
        assertFalse(said.toToolResultJson().contains("screen_text"))
    }

    @Test
    fun `Jev is told the app was opened for it`() = runTest {
        val jev = competentJev()
        AutopilotExecutor(device(), jev, noPlanner).run(plan)
        assertTrue(jev.requests.first().state.contains("opened com.msg"))
    }

    @Test
    fun `with Jev down the planner drives every step of the task`() = runTest {
        val device = device()
        val jev = JevClient { throw JevUnavailableException("no wallet sign-in") }
        val planner = AutopilotPlanner { ctx ->
            fun pick(verb: String, label: String) =
                PlannerDecision.Act(ctx.options.entries.first { it.value.startsWith(verb) && it.value.contains("\"$label\"") }.key)
            when {
                ctx.screen.elements.any { it.label?.contains("Sent") == true } -> PlannerDecision.Done
                ctx.screen.title == "Chats" -> pick("Tap", "Anna")
                ctx.screen.elements.any { it.value == "hi" } -> pick("Tap", "Send")
                else -> pick("Type", "Message")
            }
        }
        val result = AutopilotExecutor(device, jev, planner).run(plan)
        assertEquals(AutopilotResult.Status.SUCCESS, result.status)
        assertEquals("more calls than the three escalations a Jev-driven run gets", 4, result.plannerCalls)
    }

    // ---- The event stream ----

    @Test
    fun `SUBGOAL_DONE names the sub-goal the run moved to`() = runTest {
        val events = mutableListOf<AutopilotEvent>()
        AutopilotExecutor(device(), competentJev(), noPlanner, events = { events += it }).run(plan)
        val done = events.filter { it.kind == AutopilotEvent.Kind.SUBGOAL_DONE }.map { it.subgoalIndex }
        assertEquals(listOf(1, 2), done)
    }

    @Test
    fun `a replan announces the new sub-goals`() = runTest {
        val events = mutableListOf<AutopilotEvent>()
        var replanned = false
        val planner = AutopilotPlanner {
            if (!replanned) {
                replanned = true
                PlannerDecision.Replan(listOf(PlanStep("Find Anna"), PlanStep("Say hi")))
            } else PlannerDecision.Done
        }
        AutopilotExecutor(device(), jev = null, planner = planner, events = { events += it }).run(plan)
        val announced = events.first { it.kind == AutopilotEvent.Kind.SUBGOAL_DONE }
        assertEquals(listOf("Find Anna", "Say hi"), announced.subgoals)
        assertEquals(0, announced.subgoalIndex)
    }

    @Test
    fun `one transient Jev error is retried without saying the model was asked`() = runTest {
        val events = mutableListOf<AutopilotEvent>()
        var failed = false
        val jev = object : JevClient {
            val inner = competentJev()
            override suspend fun evaluate(request: JevRequest): JevResponse {
                if (!failed) {
                    failed = true
                    throw IOException("reset")
                }
                return inner.evaluate(request)
            }
        }
        val result = AutopilotExecutor(device(), jev, noPlanner, events = { events += it }).run(plan)
        assertEquals(AutopilotResult.Status.SUCCESS, result.status)
        assertFalse(events.any { it.kind == AutopilotEvent.Kind.ESCALATED })
        assertTrue(result.escalations.isEmpty())
    }

    @Test
    fun `a hand-over tells the model where things are on screen`() {
        val summary = AutopilotExecutor.summarize(T.screen("com.msg", "Chats",
            T.button(1, "Search", viewId = "com.msg:id/search"),
            T.button(2, "Anna", y = 280, x = 360),
        ))
        assertTrue(summary.contains("viewId:com.msg:id/search"))
        assertTrue(summary.contains("\"Anna\" @(360,280)"))
    }

    @Test
    fun `two Jev timeouts on one screen do not hand the rest of the run to the planner`() = runTest {
        // agentbench android_version_from_settings: one slow step and the planner then made
        // every remaining decision, a model call each (14), for ~25 s of a 42 s turn.
        val device = device()
        var calls = 0
        val competent = competentJev()
        val jev = JevClient { req ->
            calls++
            if (calls <= 2) throw IOException("timeout") else competent.evaluate(req)
        }
        val planner = AutopilotPlanner { ctx -> PlannerDecision.Act(ctx.options.entries.first { it.value.contains("\"Anna\"") }.key) }
        val events = mutableListOf<AutopilotEvent>()
        val result = AutopilotExecutor(device, jev, planner, events = { events += it }).run(plan)

        assertEquals(AutopilotResult.Status.SUCCESS, result.status)
        assertEquals(listOf("tap:Anna", "type:Message", "tap:Send"), device.performed)
        assertEquals("the planner covers the one step Jev missed, no more", 1, result.plannerCalls)
        assertEquals(listOf("jev_error"), result.escalations)
        // Why it escalated is in the step log.
        assertTrue(events.first { it.kind == AutopilotEvent.Kind.ESCALATED }.jevAnswers!!.startsWith("error: IOException"))
    }

    @Test
    fun `a Jev that keeps failing is tried again less and less often`() = runTest {
        val device = device()
        var calls = 0
        val jev = JevClient { calls++; throw IOException("timeout") }
        val planner = AutopilotPlanner { ctx ->
            fun pick(verb: String, label: String) = PlannerDecision.Act(ctx.options.entries.first { (_, d) -> d.startsWith(verb) && d.contains("\"$label\"") }.key)
            when {
                ctx.screen.elements.any { it.label == "hi · Sent" } -> PlannerDecision.Done
                ctx.screen.title == "Chats" -> pick("Tap", "Anna")
                ctx.screen.elements.any { it.value == "hi" } -> pick("Tap", "Send")
                else -> pick("Type", "Message")
            }
        }
        val result = AutopilotExecutor(device, jev, planner).run(plan)

        assertEquals(AutopilotResult.Status.SUCCESS, result.status)
        assertEquals(4, result.plannerCalls)
        // Two on the first screen (a retry), then one probe after 1 step, then after 2 more.
        assertEquals(4, calls)
    }

    @Test
    fun `a start intent reaches the device and Jev is told where the app opened`() = runTest {
        var started: Pair<String, String?>? = null
        val base = device()
        val device = object : AutopilotDevice by base {
            override suspend fun ensureApp(packageName: String, startIntent: String?): Boolean {
                started = packageName to startIntent
                return base.ensureApp(packageName)
            }
        }
        val jev = competentJev()
        val uri = "intent:#Intent;action=android.settings.WIFI_SETTINGS;end"
        AutopilotExecutor(device, jev, noPlanner).run(plan.copy(startIntent = uri))

        assertEquals("com.msg" to uri, started)
        assertTrue(jev.requests.first().state.contains("opened com.msg at android.settings.WIFI_SETTINGS"))
    }
}
