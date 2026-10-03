package org.ethereumphone.andyclaw.autopilot

import kotlinx.coroutines.test.runTest
import org.ethereumphone.andyclaw.flows.AssertStep
import org.ethereumphone.andyclaw.flows.CheckpointStep
import org.ethereumphone.andyclaw.flows.FlowCodec
import org.ethereumphone.andyclaw.flows.FlowValidator
import org.ethereumphone.andyclaw.flows.Selector
import org.ethereumphone.andyclaw.flows.NodeExists
import org.ethereumphone.andyclaw.flows.NodeTextContains
import org.ethereumphone.andyclaw.flows.TapStep
import org.ethereumphone.andyclaw.flows.TypeStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutopilotFlowCompilerTest {

    private val list = T.screen("com.msg", "Chats",
        T.button(2, "Bob", y = 200, type = "menu_item", viewId = "com.msg:id/row_bob"),
        T.button(3, "Anna", y = 280, type = "menu_item", viewId = "com.msg:id/row_anna"),
    )
    private val thread = T.screen("com.msg", "Anna",
        T.field(5, "Message", viewId = "com.msg:id/compose"),
        T.button(9, "Send", y = 650, x = 690, type = "icon_button", viewId = "com.msg:id/send_btn"),
    )
    private val typed = thread.copy(elements = thread.elements.map { if (it.id == 5) it.copy(value = "hi there") else it })
    private val sent = T.screen("com.msg", "Anna",
        T.text(7, "hi there", y = 500).copy(viewId = "com.msg:id/bubble"),
        T.field(5, "Message", viewId = "com.msg:id/compose"),
    )

    private val plan = AutopilotPlan(
        packageName = "com.msg",
        goal = "Send 'hi there' to Anna",
        steps = listOf(
            PlanStep("Open the chat with Anna"),
            PlanStep("Write 'hi there'", typeKeys = listOf("body")),
            PlanStep("Send it"),
        ),
        values = mapOf("body" to "hi there"),
        say = "Sent.",
    )

    private fun device() = FakeDevice(
        mapOf("list" to list, "thread" to thread, "typed" to typed, "sent" to sent),
        mapOf("list|tap:Anna" to "thread", "thread|type:Message" to "typed", "typed|tap:Send" to "sent"),
        "list",
    )

    /** Jev that always picks the element named by the current sub-goal. */
    private val jev = ScriptedJev { req ->
        val s = req.state
        val sub = Regex("SUB-GOAL (\\d)/").find(s)!!.groupValues[1].toInt()
        when {
            s.contains("[7] text \"hi there\"") -> mapOf(Questions.GOAL_DONE to JevAnswer.Noul(0.97))
            sub == 1 && s.contains("title=\"Anna\"") -> mapOf(Questions.SUBGOAL_DONE to JevAnswer.Noul(0.9))
            sub == 1 -> mapOf(Questions.NEXT to T.choice(ScriptedJev.keyFor(req, Questions.NEXT, "Tap", "Anna")!!, 0.95))
            sub == 2 && s.contains("value:\"hi there\"") -> mapOf(Questions.SUBGOAL_DONE to JevAnswer.Noul(0.9))
            sub == 2 -> mapOf(Questions.NEXT to T.choice(ScriptedJev.keyFor(req, Questions.NEXT, "Type", "Message")!!, 0.95))
            else -> mapOf(Questions.NEXT to T.choice(ScriptedJev.keyFor(req, Questions.NEXT, "Tap", "Send")!!, 0.97))
        }
    }

    @Test
    fun `a clean run compiles into a valid, parameterised flow with a checkpoint before send`() = runTest {
        val result = AutopilotExecutor(device(), jev, planner = null).run(plan)
        assertEquals(AutopilotResult.Status.SUCCESS, result.status)

        val id = AutopilotFlowCompiler.flowIdFor(plan.packageName, plan.goal, plan.values)
        assertTrue(id, Regex("msg\\.send_body_to_anna_[0-9a-f]{8}").matches(id))
        val compiled = AutopilotFlowCompiler.compile(result, id, ">=1") as AutopilotFlowCompiler.Result.Compiled
        val flow = compiled.flow

        assertEquals(listOf("body"), flow.params)
        assertEquals(NodeExists(viewId = "com.msg:id/row_anna"), flow.preconditions.single())
        assertEquals(TapStep(viewId = "com.msg:id/row_anna"), flow.steps[0])
        assertEquals("{{body}}", (flow.steps[1] as TypeStep).value)
        assertTrue("checkpoint sits right before send", flow.steps[2] is CheckpointStep)
        assertEquals("com.msg:id/send_btn", (flow.steps[3] as TapStep).viewId)
        assertEquals(NodeTextContains(viewId = "com.msg:id/bubble", value = "{{body}}"), flow.postconditions.single())
        assertEquals("Send '{{body}}' to Anna", flow.intent!!.goal)

        // Round-trips through the codec the repository stores it with.
        assertEquals(flow, FlowCodec.parseOrNull(FlowCodec.json.encodeToString(org.ethereumphone.andyclaw.flows.Flow.serializer(), flow)))
    }

    @Test
    fun `runs that needed the planner or hit unnamed elements are not compiled`() = runTest {
        val result = AutopilotExecutor(device(), jev, planner = null).run(plan)
        val escalated = result.copy(escalations = listOf("low_confidence"))
        assertEquals(AutopilotFlowCompiler.Result.Skipped("needed_the_planner"),
            AutopilotFlowCompiler.compile(escalated, "msg.x", ">=1"))

        val noIds = result.copy(actions = result.actions.map { it.copy(target = it.target!!.copy(viewId = null)) })
        assertEquals(AutopilotFlowCompiler.Result.Skipped("no_view_id"),
            AutopilotFlowCompiler.compile(noIds, "msg.x", ">=1"))
    }

    @Test
    fun `values are replaced only as whole words`() {
        assertEquals("say {{body}} but keep this", AutopilotFlowCompiler.parameterize("say hi but keep this", mapOf("body" to "hi")))
        assertTrue(AutopilotFlowCompiler.flowIdFor("com.android.settings", "Turn on dark mode!").startsWith("settings.turn_on_dark_mode_"))
    }

    @Test
    fun `an id tells tasks apart that its readable part cannot`() {
        val settings = "com.android.settings"
        val on = AutopilotFlowCompiler.flowIdFor(settings, "Open the quick settings panel and turn Wi-Fi on")
        val off = AutopilotFlowCompiler.flowIdFor(settings, "Open the quick settings panel and turn Wi-Fi off")
        assertNotEquals(on, off)

        val cyrillic = AutopilotFlowCompiler.flowIdFor("org.telegram.messenger", "Напиши Анне")
        val cjk = AutopilotFlowCompiler.flowIdFor("org.telegram.messenger", "给安娜发消息")
        assertNotEquals(cyrillic, cjk)
        assertTrue(cyrillic, cyrillic.startsWith("messenger.task_"))

        // Two apps whose package names end alike.
        assertNotEquals(
            AutopilotFlowCompiler.flowIdFor("org.telegram.messenger", "Send 'hi' to Anna", mapOf("body" to "hi")),
            AutopilotFlowCompiler.flowIdFor("org.ethereumhpone.messenger", "Send 'hi' to Anna", mapOf("body" to "hi")),
        )
    }

    @Test
    fun `an id is the same for the same task with other values, and stays a valid id no longer than before`() {
        val a = AutopilotFlowCompiler.flowIdFor("com.msg", "Send 'hi there' to Anna", mapOf("body" to "hi there"))
        val b = AutopilotFlowCompiler.flowIdFor("com.msg", "Send 'see you at 6' to Anna", mapOf("body" to "see you at 6"))
        assertEquals("a recompile of the task must replace its flow", a, b)
        // Another value key is another task.
        assertNotEquals(a, AutopilotFlowCompiler.flowIdFor("com.msg", "Send 'hi there' to Anna", mapOf("text" to "hi there")))

        val long = AutopilotFlowCompiler.flowIdFor("com.android.settings", "Open the quick settings panel and turn Wi-Fi on, then go back home")
        assertTrue(long, long.substringAfter('.').length <= 40)
        for (id in listOf(a, long, AutopilotFlowCompiler.flowIdFor("x.y", "给安娜发消息"))) {
            assertTrue(id, Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$").matches(id))
        }
    }

    @Test
    fun `a run that started at an intent is not compiled`() = runTest {
        // A flow replays from the app's start page, where its first target would not be.
        val result = AutopilotExecutor(device(), jev, planner = null)
            .run(plan.copy(startIntent = "intent:#Intent;action=android.settings.WIFI_SETTINGS;end"))
        assertEquals(AutopilotResult.Status.SUCCESS, result.status)
        assertEquals(AutopilotFlowCompiler.Result.Skipped("started_at_intent"),
            AutopilotFlowCompiler.compile(result, "msg.x", ">=1"))
    }

    // ── Taps chosen by a typed value (a search hit) ─────────────────────

    private val searchHome = T.screen("com.msg", "Chats", T.field(1, "Search", viewId = "com.msg:id/search"))
    private fun searchResults(row: String, typed: String) = T.screen("com.msg", "Search",
        T.field(1, "Search", value = typed, viewId = "com.msg:id/search"),
        T.button(2, row, y = 200, type = "menu_item", viewId = "com.msg:id/contact_row"),
    )
    private fun chat(title: String, withSend: Boolean) = T.screen("com.msg", title, *listOfNotNull(
        T.text(4, title, y = 60).copy(viewId = "com.msg:id/toolbar_title"),
        T.field(5, "Message", viewId = "com.msg:id/compose"),
        T.button(9, "Send", y = 650, x = 690, type = "icon_button", viewId = "com.msg:id/send_btn").takeIf { withSend },
    ).toTypedArray())
    private val searchSent = T.screen("com.msg", "Anna", T.text(7, "hi there", y = 500).copy(viewId = "com.msg:id/bubble"))

    private fun searchRun(name: String, row: String): AutopilotResult {
        val results = searchResults(row, name)
        val plan = AutopilotPlan(
            packageName = "com.msg",
            goal = "Send 'hi there' to $name",
            steps = listOf(PlanStep("Find $name", typeKeys = listOf("name")), PlanStep("Write 'hi there'", typeKeys = listOf("body")), PlanStep("Send it")),
            values = mapOf("name" to name, "body" to "hi there"),
            say = "Sent.",
        )
        val actions = listOf(
            ExecutedAction(StepOption.Type(1, "name"), searchHome.elements[0], "name", 0, searchHome, changedScreen = true),
            ExecutedAction(StepOption.Tap(2), results.elements[1], null, 0, results, changedScreen = true),
            // The Send button only shows once there is text, as in most messengers.
            ExecutedAction(StepOption.Type(5, "body"), chat(row, false).elements[1], "body", 1, chat(row, false), changedScreen = true),
            ExecutedAction(StepOption.Tap(9), chat(row, true).elements[2], null, 2, chat(row, true), changedScreen = true),
        )
        return AutopilotResult(
            status = AutopilotResult.Status.SUCCESS, steps = actions.size, durationMs = 1, say = "Sent.", reason = null,
            trace = emptyList(), screenSummary = null, plannerCalls = 0, jevCalls = 4, jevMsP50 = 100,
            escalations = emptyList(), actions = actions, finalScreen = searchSent, plan = plan,
        )
    }

    @Test
    fun `a tap on a search hit carries an identity assert before it and after it`() {
        val flow = (AutopilotFlowCompiler.compile(searchRun("Anna", "Anna"), "msg.search_send", ">=1")
            as AutopilotFlowCompiler.Result.Compiled).flow
        assertEquals(
            listOf(
                TypeStep(target = Selector(viewId = "com.msg:id/search"), value = "{{name}}"),
                AssertStep(viewId = "com.msg:id/contact_row", nodeTextContains = "{{name}}"),
                TapStep(viewId = "com.msg:id/contact_row"),
                AssertStep(viewId = "com.msg:id/toolbar_title", nodeTextContains = "{{name}}"),
                TypeStep(target = Selector(viewId = "com.msg:id/compose"), value = "{{body}}"),
            ),
            flow.steps.take(5),
        )
        assertTrue(flow.steps[5] is CheckpointStep)
        assertEquals("a Send button that appears with the text is no search hit",
            "com.msg:id/send_btn", (flow.steps[6] as TapStep).viewId)
        assertTrue(FlowValidator.validate(flow).isValid)
    }

    @Test
    fun `a hit the typed value only partly names cannot be checked, so it is not compiled`() {
        assertEquals(AutopilotFlowCompiler.Result.Skipped("value_dependent_target"),
            AutopilotFlowCompiler.compile(searchRun("Ann", "Anna"), "msg.x", ">=1"))
        // Searched by number, the row shows a name: nothing ties the row to the value. The row is a
        // menu_item, as ScreenAnalyzer calls every row, and that is no control.
        assertEquals(AutopilotFlowCompiler.Result.Skipped("value_dependent_target"),
            AutopilotFlowCompiler.compile(searchRun("+4915112345678", "Anna"), "msg.x", ">=1"))
    }

    @Test
    fun `a committing button nobody here can read is left to the autopilot`() {
        val run = searchRun("Anna", "Anna")
        val russian = run.copy(actions = run.actions.mapIndexed { i, a ->
            if (i == run.actions.lastIndex) a.copy(target = a.target!!.copy(label = "Отправить")) else a
        })
        assertEquals(AutopilotFlowCompiler.Result.Skipped("unreadable_commit"),
            AutopilotFlowCompiler.compile(russian, "msg.x", ">=1"))
        // A row in that script is a name, not a verb: it still compiles.
        val named = searchRun("Анна", "Анна")
        assertTrue(AutopilotFlowCompiler.compile(named, "msg.x", ">=1") is AutopilotFlowCompiler.Result.Compiled)
    }
}
