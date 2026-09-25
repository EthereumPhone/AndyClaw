package org.ethereumphone.andyclaw.flows

import kotlinx.coroutines.test.runTest
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
        override suspend fun clickNode(viewId: String, index: Int): Boolean {
            if (index != 0) return false
            if (viewId !in current.viewIds) return false
            clicks += viewId
            advance()
            return true
        }
        override suspend fun setNodeText(viewId: String, text: String): Boolean {
            if (viewId !in current.viewIds) return false
            typed += viewId to text
            return true
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
     * many consecutive reads fail after an action.
     */
    private class Driver(
        private val screens: Map<String, Screen>,
        private val transitions: Map<String, String>,
        start: String,
        private val settlesAfterReads: Map<String, Int> = emptyMap(),
        private val unsettled: Screen? = null,
        var nullReadsAfterAction: Int = 0,
    ) : FlowDisplayDriver {
        var current = start
        private var readsSinceArrival = 0
        private var nullReads = 0
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
            readsSinceArrival++
            val wait = settlesAfterReads[current] ?: 0
            return if (readsSinceArrival <= wait && unsettled != null) unsettled.json() else screens.getValue(current).json()
        }
        private fun act(viewId: String): Boolean {
            if (screens.getValue(current).nodes.none { it.viewId == viewId }) return false
            transitions["$current|$viewId"]?.let {
                current = it
                readsSinceArrival = 0
            }
            nullReads = nullReadsAfterAction
            return true
        }
        override suspend fun clickNode(viewId: String, index: Int): Boolean {
            if (!act(viewId)) return false
            clicks += viewId
            return true
        }
        override suspend fun setNodeText(viewId: String, text: String): Boolean {
            if (!act(viewId)) return false
            typed += viewId
            return true
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

    private fun Screen.copyWithout(viewId: String) = Screen(name, pkg, *nodes.filter { it.viewId != viewId }.toTypedArray())
    private fun Screen.copyPlus(node: Node) = Screen(name, pkg, *(nodes.toList() + node).toTypedArray())
}
