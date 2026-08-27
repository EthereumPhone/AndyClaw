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
