package org.ethereumphone.andyclaw.flows

import kotlinx.coroutines.test.runTest
import org.ethereumphone.andyclaw.skills.ToolEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replaying with no model in the loop, and — the part that makes that safe — refusing to
 * carry on the moment the screen is not the one the flow was compiled against.
 */
class FlowInterpreterTest {

    /** A screen the fake driver can be pointed at. */
    private class Screen(val pkg: String, val viewIds: List<String>, val texts: Map<String, String> = emptyMap()) {
        fun json(): String {
            val elements = viewIds.joinToString(",") { id ->
                val label = texts[id]?.let { ""","label":"$it"""" } ?: ""
                """{"id":0,"type":"row","viewId":"$id"$label,"actions":["click"]}"""
            }
            return """{"screen":{"package":"$pkg"},"elements":[$elements],"scrollable":false}"""
        }
    }

    private class FakeDriver(
        var version: String? = "7.3.0",
        screens: List<Screen>,
    ) : FlowDisplayDriver {
        private val queue = ArrayDeque(screens)
        var current: Screen = queue.removeFirst()
        val clicks = mutableListOf<String>()
        val typed = mutableListOf<Pair<String, String>>()
        var launched: String? = null
        var treeReads = 0

        /** Advance to the next screen when the flow acts. */
        private fun advance() {
            if (queue.isNotEmpty()) current = queue.removeFirst()
        }

        override suspend fun installedVersion(packageName: String) = version
        override suspend fun ensureApp(packageName: String): Boolean {
            launched = packageName
            return true
        }
        override suspend fun uiTree(): String {
            treeReads++
            return current.json()
        }
        override suspend fun clickNode(viewId: String, index: Int): FlowDispatch {
            if (index != 0) return FlowDispatch.NOT_DISPATCHED
            if (viewId !in current.viewIds) return FlowDispatch.NOT_DISPATCHED
            clicks += viewId
            advance()
            return FlowDispatch.DONE
        }
        override suspend fun setNodeText(viewId: String, text: String): FlowDispatch {
            if (viewId !in current.viewIds) return FlowDispatch.NOT_DISPATCHED
            typed += viewId to text
            return FlowDispatch.DONE
        }
    }

    private val listScreen = Screen("com.msg", listOf("conversation_list", "search_button"))
    private val threadScreen = Screen(
        "com.msg",
        listOf("toolbar_title", "compose_text", "send_button"),
        mapOf("toolbar_title" to "Anna"),
    )
    private val sentScreen = Screen("com.msg", listOf("toolbar_title", "conversation_item_sent"))

    private fun flow(steps: List<FlowStep>, params: List<String> = listOf("body")) = Flow(
        flow = "msg.send",
        version = 1,
        app = "com.msg",
        appVersionRange = ">=7,<8",
        params = params,
        preconditions = listOf(NodeExists(viewId = "conversation_list")),
        steps = steps,
        postconditions = listOf(NodeExists(viewId = "conversation_item_sent")),
    )

    private fun interpreter(driver: FlowDisplayDriver, approve: Boolean = true, clock: () -> Long = { 0L }) =
        FlowInterpreter(
            driver = driver,
            checkpoints = FlowCheckpointHandler { _, _, _ -> approve },
            sleep = { /* no waiting in tests */ },
            clock = clock,
        )

    private val happySteps = listOf(
        TapStep(viewId = "search_button"),
        TypeStep(target = Selector(viewId = "compose_text"), value = "{{body}}"),
        AssertStep(viewId = "toolbar_title", nodeTextContains = "Anna"),
        CheckpointStep("send"),
        TapStep(viewId = "send_button"),
    )

    @Test
    fun `a compiled flow replays end to end with no model`() = runTest {
        val driver = FakeDriver(screens = listOf(listScreen, threadScreen, sentScreen))
        val result = interpreter(driver).run(flow(happySteps), mapOf("body" to "see you at 6"))

        assertTrue("expected completion, got $result", result is FlowRunResult.Completed)
        assertEquals(listOf("search_button", "send_button"), driver.clicks)
        assertEquals(listOf("compose_text" to "see you at 6"), driver.typed)
        assertEquals("com.msg", driver.launched)
    }

    @Test
    fun `a changed screen shape aborts instead of guessing`() = runTest {
        val stepsWithChecksum = listOf(
            TapStep(viewId = "search_button", expectChecksum = "0000000000000000"),
        )
        val driver = FakeDriver(screens = listOf(listScreen))
        val result = interpreter(driver).run(flow(stepsWithChecksum, params = emptyList()), emptyMap())

        assertTrue(result is FlowRunResult.Aborted)
        assertEquals(FlowAbortReason.CHECKSUM_MISMATCH, (result as FlowRunResult.Aborted).reason)
        assertTrue("nothing may be tapped after a mismatch", driver.clicks.isEmpty())
    }

    @Test
    fun `a matching checksum lets the step run`() = runTest {
        val checksum = NodeTreeChecksum.of(listScreen.json())
        val driver = FakeDriver(screens = listOf(listScreen, threadScreen, sentScreen))
        val steps = listOf(TapStep(viewId = "search_button", expectChecksum = checksum)) +
            happySteps.drop(1)
        val result = interpreter(driver).run(flow(steps), mapOf("body" to "x"))
        assertTrue("expected completion, got $result", result is FlowRunResult.Completed)
    }

    @Test
    fun `an app outside the compiled range is not replayed at all`() = runTest {
        val driver = FakeDriver(version = "9.0.0", screens = listOf(listScreen))
        val result = interpreter(driver).run(flow(happySteps), mapOf("body" to "x"))

        assertEquals(FlowAbortReason.APP_VERSION_MISMATCH, (result as FlowRunResult.Aborted).reason)
        assertTrue(driver.clicks.isEmpty())
        assertEquals("the app must not even be launched", null, driver.launched)
    }

    @Test
    fun `an uninstalled app aborts`() = runTest {
        val driver = FakeDriver(version = null, screens = listOf(listScreen))
        val result = interpreter(driver).run(flow(happySteps), mapOf("body" to "x"))
        assertEquals(FlowAbortReason.APP_NOT_INSTALLED, (result as FlowRunResult.Aborted).reason)
    }

    @Test
    fun `a refused checkpoint stops before the irreversible step`() = runTest {
        val driver = FakeDriver(screens = listOf(listScreen, threadScreen, sentScreen))
        val result = interpreter(driver, approve = false).run(flow(happySteps), mapOf("body" to "x"))

        assertEquals(FlowAbortReason.CHECKPOINT_REFUSED, (result as FlowRunResult.Aborted).reason)
        assertFalse("the send must not happen", "send_button" in driver.clicks)
    }

    @Test
    fun `a missing precondition aborts before anything is touched`() = runTest {
        val driver = FakeDriver(screens = listOf(threadScreen))
        val result = interpreter(driver).run(flow(happySteps), mapOf("body" to "x"))

        assertEquals(FlowAbortReason.PRECONDITION_FAILED, (result as FlowRunResult.Aborted).reason)
        assertTrue(driver.clicks.isEmpty())
    }

    @Test
    fun `a flow that ran but cannot prove it worked aborts`() = runTest {
        // The last screen never shows the postcondition node.
        val driver = FakeDriver(screens = listOf(listScreen, threadScreen, threadScreen))
        val result = interpreter(driver).run(flow(happySteps), mapOf("body" to "x"))
        assertEquals(FlowAbortReason.ASSERT_FAILED, (result as FlowRunResult.Aborted).reason)
    }

    @Test
    fun `a missing parameter aborts before the app is launched`() = runTest {
        val driver = FakeDriver(screens = listOf(listScreen))
        val result = interpreter(driver).run(flow(happySteps), emptyMap())
        assertEquals(FlowAbortReason.MISSING_PARAM, (result as FlowRunResult.Aborted).reason)
        assertEquals(null, driver.launched)
    }

    @Test
    fun `a step whose node has gone aborts rather than tapping something else`() = runTest {
        val driver = FakeDriver(screens = listOf(listScreen, listScreen, listScreen))
        val result = interpreter(driver).run(flow(happySteps), mapOf("body" to "x"))
        assertTrue(result is FlowRunResult.Aborted)
        assertEquals(FlowAbortReason.STEP_FAILED, (result as FlowRunResult.Aborted).reason)
    }

    @Test
    fun `wait_for gives up on its own deadline`() = runTest {
        var now = 0L
        val driver = FakeDriver(screens = listOf(listScreen))
        val steps = listOf(WaitForStep(viewId = "never_appears", timeoutMs = 500))
        val result = interpreter(driver, clock = { now += 100; now })
            .run(flow(steps, params = emptyList()), emptyMap())

        assertEquals(FlowAbortReason.WAIT_TIMEOUT, (result as FlowRunResult.Aborted).reason)
    }

    @Test
    fun `an index other than zero is refused rather than approximated`() = runTest {
        val driver = FakeDriver(screens = listOf(listScreen, threadScreen))
        val steps = listOf(TapStep(viewId = "search_button", index = 2))
        val result = interpreter(driver).run(flow(steps, params = emptyList()), emptyMap())
        assertEquals(FlowAbortReason.STEP_FAILED, (result as FlowRunResult.Aborted).reason)
    }

    @Test
    fun `the budget is a hard stop`() = runTest {
        var now = 0L
        val driver = FakeDriver(screens = listOf(listScreen, threadScreen, sentScreen))
        val result = interpreter(driver, clock = { now += 10_000; now })
            .run(flow(happySteps), mapOf("body" to "x"), budgetMs = 1_000)
        assertEquals(FlowAbortReason.BUDGET_EXCEEDED, (result as FlowRunResult.Aborted).reason)
    }

    @Test
    fun `placeholders are substituted, unbound ones are left alone`() {
        assertEquals("hello Anna", FlowInterpreter.substitute("hello {{name}}", mapOf("name" to "Anna")))
        assertEquals("hello {{name}}", FlowInterpreter.substitute("hello {{name}}", emptyMap()))
        assertEquals("a-b", FlowInterpreter.substitute("{{ x }}-{{y}}", mapOf("x" to "a", "y" to "b")))
    }
}

/**
 * The replay's safety rules, against a screen that behaves like a real, slow app: it settles
 * late, shows results late, repeats view ids in lists, and changes what a button says.
 */
class FlowInterpreterSafetyTest {

    private data class Node(
        val viewId: String,
        val type: String = "button",
        val label: String? = null,
        val password: Boolean = false,
    )

    private class Screen(val name: String, val pkg: String, vararg val nodes: Node) {
        fun json(): String {
            val elements = nodes.mapIndexed { i, n ->
                buildString {
                    append("""{"id":$i,"type":"${n.type}","viewId":"${n.viewId}"""")
                    n.label?.let { append(""","label":"$it"""") }
                    if (n.password) append(""","password":true""")
                    append(""","actions":["click"]}""")
                }
            }.joinToString(",")
            return """{"screen":{"package":"$pkg"},"elements":[$elements],"scrollable":false}"""
        }
    }

    /**
     * A screen graph. Acting on a node moves to [transitions]["screen|viewId"]; a screen named in
     * [settlesAfterReads] shows as [unsettled] for that many reads first; [nullReads] makes that
     * many consecutive reads fail after an action. [answers] overrides what the display says about
     * an action on a view id: NOT_DISPATCHED as if the node had gone between the read and the tap
     * (nothing happens), UNKNOWN as if the answer was lost (the action happens), and [throwsOn]
     * makes the call throw once it has been carried out.
     */
    private class Driver(
        private val screens: Map<String, Screen>,
        private val transitions: Map<String, String>,
        start: String,
        private val settlesAfterReads: Map<String, Int> = emptyMap(),
        private val unsettled: Screen? = null,
        var nullReadsAfterAction: Int = 0,
        private val answers: Map<String, FlowDispatch> = emptyMap(),
        private val throwsOn: Set<String> = emptySet(),
        /** Reads after an action that still return the screen acted on, like a lagging a11y cache. */
        private val staleReadsAfterAction: Int = 0,
    ) : FlowDisplayDriver {
        var current = start
        private var readsSinceArrival = 0
        private var nullReads = 0
        private var staleReads = 0
        private var previous = start
        val clicks = mutableListOf<String>()
        val typed = mutableListOf<String>()
        var onRead: () -> Unit = {}

        override suspend fun installedVersion(packageName: String) = "7.3.0"
        override suspend fun ensureApp(packageName: String) = true
        override suspend fun uiTree(): String? {
            onRead()
            if (nullReads > 0) {
                nullReads--
                return null
            }
            if (staleReads > 0) {
                staleReads--
                return screens.getValue(previous).json()
            }
            readsSinceArrival++
            val wait = settlesAfterReads[current] ?: 0
            return if (readsSinceArrival <= wait && unsettled != null) unsettled.json() else screens.getValue(current).json()
        }
        /** Like the real display, the action resolves against the live screen, settled or not. */
        private fun act(viewId: String): FlowDispatch {
            if (answers[viewId] == FlowDispatch.NOT_DISPATCHED) return FlowDispatch.NOT_DISPATCHED
            if (screens.getValue(current).nodes.none { it.viewId == viewId }) return FlowDispatch.NOT_DISPATCHED
            previous = current
            transitions["$current|$viewId"]?.let {
                current = it
                readsSinceArrival = 0
            }
            nullReads = nullReadsAfterAction
            staleReads = staleReadsAfterAction
            return answers[viewId] ?: FlowDispatch.DONE
        }
        override suspend fun clickNode(viewId: String, index: Int): FlowDispatch {
            val sent = act(viewId)
            if (sent.mayHaveHappened) clicks += viewId
            if (viewId in throwsOn) throw IllegalStateException("binder died")
            return sent
        }
        override suspend fun setNodeText(viewId: String, text: String): FlowDispatch {
            val sent = act(viewId)
            if (sent.mayHaveHappened) typed += viewId
            if (viewId in throwsOn) throw IllegalStateException("binder died")
            return sent
        }
    }

    private val list = Screen("list", "com.msg",
        Node("conversation_list", "list"), Node("row", "list_item", "Anna"), Node("search_button"))
    private val listTwoRows = Screen("list2", "com.msg",
        Node("conversation_list", "list"), Node("row", "list_item", "Bob"), Node("row", "list_item", "Anna"))
    private val thread = Screen("thread", "com.msg",
        Node("toolbar_title", "text", "Anna"), Node("compose_text", "text_field"), Node("send_button"))
    private val sent = Screen("sent", "com.msg",
        Node("toolbar_title", "text", "Anna"), Node("conversation_item_sent", "text", "see you"))

    private fun flow(steps: List<FlowStep>, post: List<Condition> = listOf(NodeExists(viewId = "conversation_item_sent"))) = Flow(
        flow = "msg.send", version = 1, app = "com.msg", appVersionRange = ">=7,<8",
        params = emptyList(),
        preconditions = listOf(NodeExists(viewId = "conversation_list")),
        steps = steps,
        postconditions = post,
    )

    private val sendSteps = listOf(
        TapStep(viewId = "row"),
        AssertStep(viewId = "toolbar_title", nodeTextContains = "Anna"),
        TypeStep(target = Selector(viewId = "compose_text"), value = "see you"),
        CheckpointStep("send"),
        TapStep(viewId = "send_button"),
    )

    private val graph = mapOf("list|row" to "thread", "list2|row" to "thread", "thread|send_button" to "sent")

    private fun interpreter(driver: FlowDisplayDriver, stop: FlowStopSignal = FlowStopSignal.NEVER) =
        FlowInterpreter(driver, FlowCheckpointHandler { _, _, _ -> true }, sleep = { }, clock = { 0L }, stop = stop)

    @Test
    fun `a sent bubble that shows up late is still a success`() = runTest {
        val driver = Driver(mapOf("list" to list, "thread" to thread, "sent" to sent), graph, "list",
            settlesAfterReads = mapOf("sent" to 6), unsettled = thread)
        val result = interpreter(driver).run(flow(sendSteps), emptyMap())
        assertTrue("expected completion, got $result", result is FlowRunResult.Completed)
        assertEquals(listOf("row", "send_button"), driver.clicks)
    }

    @Test
    fun `a send that cannot be confirmed is reported as done, never to be repeated`() = runTest {
        // The app never shows the sent bubble, but the send button was tapped.
        val driver = Driver(mapOf("list" to list, "thread" to thread, "sent" to thread), graph, "list")
        val result = interpreter(driver).run(flow(sendSteps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.ASSERT_FAILED, result.reason)
        assertTrue(result.committed)
        assertFalse("no fallback may send it a second time", FlowRunAccounting.mayFallBack(result))
    }

    @Test
    fun `an abort before the checkpoint is not committed`() = runTest {
        val driver = Driver(mapOf("list" to list, "thread" to thread.copyWithout("compose_text")), graph, "list")
        val result = interpreter(driver).run(flow(sendSteps), emptyMap()) as FlowRunResult.Aborted
        assertFalse(result.committed)
        assertTrue(FlowRunAccounting.mayFallBack(result))
    }

    @Test
    fun `STOP halts the replay before the next action`() = runTest {
        val driver = Driver(mapOf("list" to list, "thread" to thread, "sent" to sent), graph, "list")
        val stop = FlowStopSignal { driver.clicks.isNotEmpty() }
        val result = interpreter(driver, stop).run(flow(sendSteps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.STOPPED, result.reason)
        assertEquals(listOf("row"), driver.clicks)
        assertTrue(driver.typed.isEmpty())
        assertFalse(FlowRunAccounting.counts(result))
        assertFalse(FlowRunAccounting.mayFallBack(result))
    }

    @Test
    fun `STOP during a wait_for ends the wait`() = runTest {
        var reads = 0
        val driver = Driver(mapOf("list" to list), emptyMap(), "list")
        driver.onRead = { reads++ }
        val steps = listOf(WaitForStep(viewId = "never_appears", timeoutMs = 30_000))
        val result = interpreter(driver, FlowStopSignal { reads >= 3 }).run(flow(steps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.STOPPED, result.reason)
        assertTrue("stopped within a poll or two, not at the deadline", reads < 10)
    }

    @Test
    fun `a list row taken by position with nothing proving it is refused`() = runTest {
        val driver = Driver(mapOf("list2" to listTwoRows, "thread" to thread, "sent" to sent), graph, "list2")
        val blind = listOf(
            TapStep(viewId = "row"),
            TypeStep(target = Selector(viewId = "compose_text"), value = "see you"),
            CheckpointStep("send"),
            TapStep(viewId = "send_button"),
        )
        val result = interpreter(driver).run(flow(blind), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.AMBIGUOUS_TARGET, result.reason)
        assertTrue("the first row is Bob; nothing may be tapped", driver.clicks.isEmpty())
    }

    @Test
    fun `a list row followed by an identity assert may be taken, and a wrong one stops it`() = runTest {
        // Bob is first now: the row tap opens a thread whose title is not Anna, and the assert
        // stops the flow before the checkpoint.
        val bobThread = Screen("thread", "com.msg",
            Node("toolbar_title", "text", "Bob"), Node("compose_text", "text_field"), Node("send_button"))
        val driver = Driver(mapOf("list2" to listTwoRows, "thread" to bobThread, "sent" to sent), graph, "list2")
        val result = interpreter(driver).run(flow(sendSteps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.ASSERT_FAILED, result.reason)
        assertFalse("send_button" in driver.clicks)
        assertFalse(result.committed)
    }

    @Test
    fun `a button that now reads Pay is never tapped`() = runTest {
        val cart = Screen("cart", "com.shop", Node("conversation_list", "list"), Node("primary_action", "button", "Pay 49 EUR"))
        val driver = Driver(mapOf("cart" to cart), emptyMap(), "cart")
        val steps = listOf(TapStep(viewId = "primary_action"))
        val result = interpreter(driver).run(flow(steps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.SENSITIVE_TARGET, result.reason)
        assertTrue(driver.clicks.isEmpty())
        assertFalse(FlowRunAccounting.mayFallBack(result))
    }

    @Test
    fun `a camelCase or German payment label is caught too`() = runTest {
        val cart = Screen("cart", "com.shop", Node("conversation_list", "list"), Node("primary_action", "button", "Jetzt kaufen"))
        val driver = Driver(mapOf("cart" to cart), emptyMap(), "cart")
        val result = interpreter(driver).run(flow(listOf(TapStep(viewId = "primary_action"))), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.SENSITIVE_TARGET, result.reason)
    }

    @Test
    fun `a chat row that mentions paying is content, not a Pay button`() = runTest {
        val chats = Screen("list", "com.msg", Node("conversation_list", "list"),
            Node("row", "list_item", "Anna: can you pay me back for the tickets tomorrow"))
        val driver = Driver(mapOf("list" to chats, "thread" to thread, "sent" to sent), graph, "list")
        val result = interpreter(driver).run(flow(sendSteps), emptyMap())
        assertTrue("expected completion, got $result", result is FlowRunResult.Completed)
    }

    @Test
    fun `typing into a password field is refused`() = runTest {
        val login = Screen("login", "com.msg", Node("conversation_list", "list"), Node("field", "text_field", password = true))
        val driver = Driver(mapOf("login" to login), emptyMap(), "login")
        val steps = listOf(TypeStep(target = Selector(viewId = "field"), value = "x"))
        val result = interpreter(driver).run(flow(steps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.SENSITIVE_TARGET, result.reason)
        assertTrue(driver.typed.isEmpty())
    }

    @Test
    fun `a private app on screen stops the replay`() = runTest {
        val wallet = Screen("wallet", "org.ethereumphone.walletmanager", Node("conversation_list", "list"), Node("row", "list_item"))
        val driver = Driver(mapOf("wallet" to wallet), emptyMap(), "wallet")
        val result = interpreter(driver).run(flow(listOf(TapStep(viewId = "row"))), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.SENSITIVE_TARGET, result.reason)
        assertTrue(driver.clicks.isEmpty())
    }

    @Test
    fun `a system toast over the app is not a private app`() = runTest {
        val withToast = """{"screen":{"package":"com.msg"},"windows":[{"package":"com.android.systemui"}],""" +
            """"elements":[{"id":0,"type":"list","viewId":"conversation_list"}]}"""
        assertEquals(null, FlowTargetGuard.privateAppOn(withToast))
        assertEquals("org.ethereumphone.walletmanager",
            FlowTargetGuard.privateAppOn("""{"screen":{"package":"org.ethereumphone.walletmanager"},"elements":[]}"""))
    }

    @Test
    fun `a screen that is still settling is given a moment before it counts as changed`() = runTest {
        val loading = Screen("loading", "com.msg", Node("conversation_list", "list"), Node("spinner", "image"))
        val expected = NodeTreeChecksum.ofV2(thread.json())
        val driver = Driver(mapOf("list" to list, "thread" to thread, "sent" to sent), graph, "list",
            settlesAfterReads = mapOf("thread" to 4), unsettled = loading)
        val steps = listOf(
            TapStep(viewId = "row"),
            AssertStep(viewId = "toolbar_title", nodeTextContains = "Anna", expectChecksum = expected),
            CheckpointStep("send"),
            TapStep(viewId = "send_button"),
        )
        val result = interpreter(driver).run(flow(steps), emptyMap())
        assertTrue("expected completion, got $result", result is FlowRunResult.Completed)
    }

    @Test
    fun `a screen that cannot be read after an action aborts instead of reusing the old one`() = runTest {
        val driver = Driver(mapOf("list" to list, "thread" to thread, "sent" to sent), graph, "list", nullReadsAfterAction = 10)
        val result = interpreter(driver).run(flow(sendSteps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.DISPLAY_UNAVAILABLE, result.reason)
        assertEquals(listOf("row"), driver.clicks)
    }

    @Test
    fun `a v2 checksum ignores how many rows a list has`() = runTest {
        val three = Screen("three", "com.msg", Node("conversation_list", "list"),
            Node("row", "list_item", "a"), Node("row", "list_item", "b"), Node("row", "list_item", "c"), Node("search_button"))
        val expected = NodeTreeChecksum.ofV2(listTwoRows.copyPlus(Node("search_button")).json())
        val driver = Driver(mapOf("three" to three), emptyMap(), "three")
        val steps = listOf(AssertStep(viewId = "conversation_list", nodeTextContains = "", expectChecksum = expected))
        val result = interpreter(driver).run(flow(steps, post = listOf(NodeExists(viewId = "search_button"))), emptyMap())
        assertTrue("expected completion, got $result", result is FlowRunResult.Completed)
    }

    // ── Committed: the last action, and only what went out ────────────────

    private val editor = Screen("editor", "com.notes",
        Node("note_editor", "container"), Node("note_text", "text_field"), Node("save", "button", "Save"))

    /** A notes app: no commit token anywhere, and its "saved" banner shows late or never. */
    private fun notesFlow(steps: List<FlowStep>) = Flow(
        flow = "notes.add", version = 1, app = "com.notes", appVersionRange = ">=7,<8",
        preconditions = listOf(NodeExists(viewId = "note_editor")),
        steps = steps,
        postconditions = listOf(NodeExists(viewId = "saved_banner")),
    )

    private val saveSteps = listOf(
        TypeStep(target = Selector(viewId = "note_text"), value = "milk"),
        TapStep(viewId = "save"),
    )

    @Test
    fun `a flow that ends on Save and cannot confirm it is committed, never done again`() = runTest {
        // Neither a checkpoint nor a commit token: only being the last action says the task is done.
        assertEquals(ToolEffect.REVERSIBLE, FlowStepEffects.of(TapStep(viewId = "save")))
        val driver = Driver(mapOf("editor" to editor), emptyMap(), "editor")
        val result = interpreter(driver).run(notesFlow(saveSteps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.ASSERT_FAILED, result.reason)
        assertEquals(listOf("save"), driver.clicks)
        assertTrue(result.committed)
        assertFalse("the autopilot must not save it a second time", FlowRunAccounting.mayFallBack(result))
    }

    @Test
    fun `a last action that never reached the app is not a commit, even past the checkpoint`() = runTest {
        // The button was on the screen that was checked, and gone when the tap looked for it.
        val send = Driver(mapOf("list" to list, "thread" to thread, "sent" to sent), graph, "list",
            answers = mapOf("send_button" to FlowDispatch.NOT_DISPATCHED))
        val unsent = interpreter(send).run(flow(sendSteps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.STEP_FAILED, unsent.reason)
        assertFalse("send_button" in send.clicks)
        assertFalse("nothing was sent, so nothing may be reported as sent", unsent.committed)
        assertTrue(FlowRunAccounting.mayFallBack(unsent))

        val save = Driver(mapOf("editor" to editor), emptyMap(), "editor",
            answers = mapOf("save" to FlowDispatch.NOT_DISPATCHED))
        val unsaved = interpreter(save).run(notesFlow(saveSteps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.STEP_FAILED, unsaved.reason)
        assertFalse(unsaved.committed)
        assertTrue(FlowRunAccounting.mayFallBack(unsaved))
    }

    @Test
    fun `a last action with no clear answer counts as done`() = runTest {
        // The OS stopped waiting ("outcome":"unknown"), and the tap landed anyway.
        val driver = Driver(mapOf("editor" to editor), emptyMap(), "editor", answers = mapOf("save" to FlowDispatch.UNKNOWN))
        val result = interpreter(driver).run(notesFlow(saveSteps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.STEP_FAILED, result.reason)
        assertTrue(result.committed)
        assertFalse(FlowRunAccounting.mayFallBack(result))
    }

    @Test
    fun `a last action that throws once it is out counts as done`() = runTest {
        val driver = Driver(mapOf("editor" to editor), emptyMap(), "editor", throwsOn = setOf("save"))
        val result = interpreter(driver).run(notesFlow(saveSteps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.STEP_FAILED, result.reason)
        assertTrue(result.committed)
        assertFalse(FlowRunAccounting.mayFallBack(result))
    }

    @Test
    fun `an unclear answer to a step that commits nothing leaves the task to another route`() = runTest {
        val driver = Driver(mapOf("list" to list, "thread" to thread, "sent" to sent), graph, "list",
            answers = mapOf("row" to FlowDispatch.UNKNOWN))
        val result = interpreter(driver).run(flow(sendSteps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.STEP_FAILED, result.reason)
        assertTrue("nothing after an unclear answer is acted on", driver.typed.isEmpty())
        assertFalse(result.committed)
        assertTrue(FlowRunAccounting.mayFallBack(result))
    }

    @Test
    fun `STOP after the last action went out is still reported as done`() = runTest {
        val driver = Driver(mapOf("editor" to editor), emptyMap(), "editor")
        val result = interpreter(driver, FlowStopSignal { "save" in driver.clicks })
            .run(notesFlow(saveSteps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.STOPPED, result.reason)
        assertTrue(result.committed)
    }

    // ── A target that is not on the screen yet ─────────────────────────

    private val loading = Screen("loading", "com.msg", Node("conversation_list", "list"), Node("spinner", "image"))

    private val blindSend = listOf(
        TapStep(viewId = "row"),
        TypeStep(target = Selector(viewId = "compose_text"), value = "see you"),
        CheckpointStep("send"),
        TapStep(viewId = "send_button"),
    )

    @Test
    fun `a target that is not on the screen yet is waited for, then acted on`() = runTest {
        val driver = Driver(mapOf("list" to list, "thread" to thread, "sent" to sent), graph, "list",
            settlesAfterReads = mapOf("thread" to 5), unsettled = loading)
        val result = interpreter(driver).run(flow(blindSend), emptyMap())
        assertTrue("expected completion, got $result", result is FlowRunResult.Completed)
        assertEquals(listOf("compose_text"), driver.typed)
        assertEquals(listOf("row", "send_button"), driver.clicks)
    }

    @Test
    fun `a target that never shows is never acted on`() = runTest {
        // The thread never finishes loading. Checked on the loading screen, the compose field and
        // the send button had zero matches, passed every check, and were acted on blind.
        var reads = 0
        val driver = Driver(mapOf("list" to list, "thread" to thread, "sent" to sent), graph, "list",
            settlesAfterReads = mapOf("thread" to Int.MAX_VALUE), unsettled = loading)
        driver.onRead = { reads++ }
        val typing = interpreter(driver).run(flow(blindSend), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.STEP_FAILED, typing.reason)
        assertTrue(driver.typed.isEmpty())
        assertEquals(listOf("row"), driver.clicks)
        assertFalse(typing.committed)
        assertTrue("a frozen clock is bounded by the poll count, not the deadline", reads < 100)

        val tapping = Driver(mapOf("list" to list, "thread" to thread, "sent" to sent), graph, "list",
            settlesAfterReads = mapOf("thread" to Int.MAX_VALUE), unsettled = loading)
        val result = interpreter(tapping).run(flow(listOf(TapStep(viewId = "row"), CheckpointStep("send"),
            TapStep(viewId = "send_button"))), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.STEP_FAILED, result.reason)
        assertEquals("no blind send", listOf("row"), tapping.clicks)
        assertFalse(result.committed)
    }

    @Test
    fun `a target that only shows up reading Pay is never tapped`() = runTest {
        // Checked on the screen before it arrived, the target had no matches and passed; the tap
        // then landed on the live "Pay" button.
        val checkout = Screen("checkout", "com.shop", Node("conversation_list", "list"), Node("primary_action", "button", "Pay 49 EUR"))
        val driver = Driver(mapOf("list" to list, "checkout" to checkout), mapOf("list|row" to "checkout"), "list",
            settlesAfterReads = mapOf("checkout" to 3), unsettled = loading)
        val result = interpreter(driver).run(flow(listOf(TapStep(viewId = "row"), TapStep(viewId = "primary_action"))), emptyMap())
            as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.SENSITIVE_TARGET, result.reason)
        assertEquals(listOf("row"), driver.clicks)
    }

    @Test
    fun `a list that arrives late with two rows is ambiguous, not the first row`() = runTest {
        val driver = Driver(mapOf("list" to list, "list2" to listTwoRows, "thread" to thread),
            mapOf("list|search_button" to "list2", "list2|row" to "thread"), "list",
            settlesAfterReads = mapOf("list2" to 3), unsettled = loading)
        val steps = listOf(TapStep(viewId = "search_button"), TapStep(viewId = "row"),
            TypeStep(target = Selector(viewId = "compose_text"), value = "x"), CheckpointStep("send"), TapStep(viewId = "send_button"))
        val result = interpreter(driver).run(flow(steps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.AMBIGUOUS_TARGET, result.reason)
        assertEquals(listOf("search_button"), driver.clicks)
    }

    @Test
    fun `STOP ends the wait for a target`() = runTest {
        var reads = 0
        val driver = Driver(mapOf("list" to list, "thread" to thread), graph, "list",
            settlesAfterReads = mapOf("thread" to Int.MAX_VALUE), unsettled = loading)
        driver.onRead = { reads++ }
        val result = interpreter(driver, FlowStopSignal { reads >= 4 }).run(flow(blindSend), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.STOPPED, result.reason)
        assertTrue("stopped within a poll, not at the bound", reads < 8)
        assertTrue(driver.typed.isEmpty())
    }

    // ── The identity assert ───────────────────────────────────────────

    @Test
    fun `Hanna does not pass for Anna`() = runTest {
        // Hanna's row is first. "Hanna" contains "Anna", and the substring match sent it to her.
        val hannaFirst = Screen("list2", "com.msg",
            Node("conversation_list", "list"), Node("row", "list_item", "Hanna"), Node("row", "list_item", "Anna"))
        val hannaThread = Screen("thread", "com.msg",
            Node("toolbar_title", "text", "Hanna"), Node("compose_text", "text_field"), Node("send_button"))
        val driver = Driver(mapOf("list2" to hannaFirst, "thread" to hannaThread, "sent" to sent), graph, "list2")
        val result = interpreter(driver).run(flow(sendSteps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.ASSERT_FAILED, result.reason)
        assertFalse("send_button" in driver.clicks)
        assertFalse(result.committed)
    }

    @Test
    fun `an identity assert names the parameter's value as a whole word`() = runTest {
        val steps = listOf(
            TapStep(viewId = "row"),
            AssertStep(viewId = "toolbar_title", nodeTextContains = "{{contact}}"),
            CheckpointStep("send"),
            TapStep(viewId = "send_button"),
        )
        fun titled(title: String) = Screen("thread", "com.msg",
            Node("toolbar_title", "text", title), Node("compose_text", "text_field"), Node("send_button"))
        suspend fun replay(title: String): FlowRunResult {
            val driver = Driver(mapOf("list" to list, "thread" to titled(title), "sent" to sent), graph, "list")
            return interpreter(driver).run(flow(steps).copy(params = listOf("contact")), mapOf("contact" to "Anna"))
        }
        assertTrue(replay("Anna") is FlowRunResult.Completed)
        assertTrue(replay("Anna Schmidt") is FlowRunResult.Completed)
        assertTrue(replay("anna") is FlowRunResult.Completed)
        assertEquals(FlowAbortReason.ASSERT_FAILED, (replay("Hanna") as FlowRunResult.Aborted).reason)
        assertEquals(FlowAbortReason.ASSERT_FAILED, (replay("Annabel") as FlowRunResult.Aborted).reason)
    }

    @Test
    fun `an identity assert on a screen that is still arriving is read again`() = runTest {
        // The thread's title shows a few reads after the tap; read once, the assert called it wrong.
        val driver = Driver(mapOf("list" to list, "thread" to thread, "sent" to sent), graph, "list",
            settlesAfterReads = mapOf("thread" to 6), unsettled = loading)
        val result = interpreter(driver).run(flow(sendSteps), emptyMap())
        assertTrue("expected completion, got $result", result is FlowRunResult.Completed)
        assertEquals(listOf("row", "send_button"), driver.clicks)
    }

    @Test
    fun `an identity assert is not judged on the screen the tap left`() = runTest {
        // The list's rows carry the name under the same view id the thread's title uses. Bob is
        // first now; the first reads after the tap still return the list, which names Anna too.
        val namedList = Screen("list", "com.msg", Node("conversation_list", "list"),
            Node("row", "list_item"), Node("name", "text", "Bob"),
            Node("row", "list_item"), Node("name", "text", "Anna"))
        val bobThread = Screen("thread", "com.msg",
            Node("name", "text", "Bob"), Node("compose_text", "text_field"), Node("send_button"))
        val steps = listOf(
            TapStep(viewId = "row"),
            AssertStep(viewId = "name", nodeTextContains = "Anna"),
            CheckpointStep("send"),
            TapStep(viewId = "send_button"),
        )
        val driver = Driver(mapOf("list" to namedList, "thread" to bobThread, "sent" to sent), graph, "list",
            staleReadsAfterAction = 2)
        val result = interpreter(driver).run(flow(steps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.ASSERT_FAILED, result.reason)
        assertFalse("send_button" in driver.clicks)
    }

    @Test
    fun `an identity assert that never holds gives up at its bound, even on a frozen clock`() = runTest {
        var reads = 0
        val bobThread = Screen("thread", "com.msg",
            Node("toolbar_title", "text", "Bob"), Node("compose_text", "text_field"), Node("send_button"))
        val driver = Driver(mapOf("list" to list, "thread" to bobThread, "sent" to sent), graph, "list")
        driver.onRead = { reads++ }
        val result = interpreter(driver).run(flow(sendSteps), emptyMap()) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.ASSERT_FAILED, result.reason)
        assertTrue(reads <= FlowInterpreter.MAX_SCREEN_POLLS + 5)
    }

    @Test
    fun `names is a whole word or the whole text, never a substring`() {
        fun tree(label: String) = Screen("t", "com.msg", Node("title", "text", label)).json()
        assertTrue(FlowTargetGuard.names(tree("Anna"), "title", "Anna"))
        assertTrue(FlowTargetGuard.names(tree("Chat with Anna"), "title", "anna"))
        assertTrue(FlowTargetGuard.names(tree("Anna Schmidt"), "title", "Anna Schmidt"))
        assertTrue(FlowTargetGuard.names(tree("Anna's phone"), "title", "Anna"))
        assertTrue(FlowTargetGuard.names(tree("Анна"), "title", "анна"))
        assertFalse(FlowTargetGuard.names(tree("Hanna"), "title", "Anna"))
        assertFalse(FlowTargetGuard.names(tree("Annabel"), "title", "Anna"))
        assertFalse(FlowTargetGuard.names(tree("Anna"), "other", "Anna"))
        assertFalse(FlowTargetGuard.names(tree("Anna"), "title", "  "))
    }

    private fun Screen.copyWithout(viewId: String) = Screen(name, pkg, *nodes.filter { it.viewId != viewId }.toTypedArray())
    private fun Screen.copyPlus(node: Node) = Screen(name, pkg, *(nodes.toList() + node).toTypedArray())
}
