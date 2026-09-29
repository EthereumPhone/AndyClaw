package org.ethereumphone.andyclaw.flows

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Send hi to Bob" replayed through a flow compiled from "Send hi to Anna": the search finds one
 * fuzzy hit, "Bobby", its row id is unique, and without an identity check the message goes to
 * the wrong person while the postcondition (the body is on screen) still passes.
 */
class FlowValueDependentTargetTest {

    private class Screen(val nodes: List<Triple<String, String, String?>>) {
        /** (viewId, type, label) */
        fun json(): String {
            val elements = nodes.joinToString(",") { (id, type, label) ->
                val l = label?.let { ""","label":"$it"""" } ?: ""
                """{"id":0,"type":"$type","viewId":"$id"$l,"actions":["click"]}"""
            }
            return """{"screen":{"package":"com.msg"},"elements":[$elements],"scrollable":false}"""
        }
        val viewIds get() = nodes.map { it.first }
    }

    private class Driver(screens: List<Screen>) : FlowDisplayDriver {
        private val queue = ArrayDeque(screens)
        var current = queue.removeFirst()
        val clicks = mutableListOf<String>()
        val typed = mutableListOf<Pair<String, String>>()
        override suspend fun installedVersion(packageName: String) = "1.0"
        override suspend fun ensureApp(packageName: String) = true
        override suspend fun uiTree() = current.json()
        override suspend fun clickNode(viewId: String, index: Int): FlowDispatch {
            if (viewId !in current.viewIds) return FlowDispatch.NOT_DISPATCHED
            clicks += viewId
            if (queue.isNotEmpty()) current = queue.removeFirst()
            return FlowDispatch.DONE
        }
        override suspend fun setNodeText(viewId: String, text: String): FlowDispatch {
            typed += viewId to text
            return FlowDispatch.DONE
        }
    }

    private fun results(row: String) = Screen(listOf(
        Triple("search_field", "search_bar", null),
        Triple("contact_row", "list_item", row),
    ))
    private fun thread(title: String) = Screen(listOf(
        Triple("toolbar_title", "text", title),
        Triple("compose", "text_field", null),
        Triple("send_button", "icon_button", "Send"),
    ))
    private val sent = Screen(listOf(Triple("bubble", "text", "hi")))

    private fun flow(steps: List<FlowStep>) = Flow(
        flow = "msg.send",
        version = 1,
        app = "com.msg",
        appVersionRange = ">=1",
        params = listOf("name", "body"),
        preconditions = listOf(NodeExists(viewId = "search_field")),
        steps = steps,
        postconditions = listOf(NodeExists(viewId = "bubble")),
    )

    /** What the compiler now emits for a tap on a search hit. */
    private val guarded = flow(listOf(
        TypeStep(target = Selector(viewId = "search_field"), value = "{{name}}"),
        AssertStep(viewId = "contact_row", nodeTextContains = "{{name}}"),
        TapStep(viewId = "contact_row"),
        AssertStep(viewId = "toolbar_title", nodeTextContains = "{{name}}"),
        TypeStep(target = Selector(viewId = "compose"), value = "{{body}}"),
        CheckpointStep("send"),
        TapStep(viewId = "send_button"),
    ))

    /** What it emitted before, and what is already installed on devices. */
    private val legacy = flow(guarded.steps.filterNot { it is AssertStep })

    private fun interpreter(driver: Driver) =
        FlowInterpreter(driver, FlowCheckpointHandler { _, _, _ -> true }, sleep = { }, clock = { 0L })

    private val bob = mapOf("name" to "Bob", "body" to "hi")

    @Test
    fun `a compiled identity assert stops the lone fuzzy hit before it is tapped`() = runTest {
        val driver = Driver(listOf(results("Bobby"), thread("Bobby"), sent))
        val result = interpreter(driver).run(guarded, bob)
        assertEquals(FlowAbortReason.ASSERT_FAILED, (result as FlowRunResult.Aborted).reason)
        assertTrue("nothing was tapped", driver.clicks.isEmpty())
        assertFalse(result.committed)
    }

    @Test
    fun `a flow compiled before the asserts still refuses a near miss`() = runTest {
        val driver = Driver(listOf(results("Bobby"), thread("Bobby"), sent))
        val result = interpreter(driver).run(legacy, bob) as FlowRunResult.Aborted
        assertEquals(FlowAbortReason.AMBIGUOUS_TARGET, result.reason)
        assertTrue(driver.clicks.isEmpty())
        assertTrue("uncommitted, so the autopilot may do it properly", FlowRunAccounting.mayFallBack(result))
    }

    @Test
    fun `the right person passes both checks`() = runTest {
        for (f in listOf(guarded, legacy)) {
            val driver = Driver(listOf(results("Bob Smith"), thread("Bob Smith"), sent))
            val result = interpreter(driver).run(f, bob)
            assertTrue("expected completion, got $result", result is FlowRunResult.Completed)
            assertEquals(listOf("contact_row", "send_button"), driver.clicks)
        }
    }

    @Test
    fun `a near miss is a word prefix on a row's name, never a control or a preview`() {
        fun tree(type: String, label: String) = Screen(listOf(Triple("x", type, label))).json()
        assertEquals("Bob", FlowTargetGuard.partialValueMatch(tree("list_item", "Bobby"), "x", listOf("Bob")))
        assertNull(FlowTargetGuard.partialValueMatch(tree("list_item", "Bob"), "x", listOf("Bob")))
        assertNull("inside a word is not a search hit", FlowTargetGuard.partialValueMatch(tree("list_item", "this is fine"), "x", listOf("hi")))
        assertNull("a button's words are what it does", FlowTargetGuard.partialValueMatch(tree("button", "Done"), "x", listOf("Do")))
        assertNull("one-letter values say nothing", FlowTargetGuard.partialValueMatch(tree("list_item", "Bobby"), "x", listOf("B")))
        // A preview is content, not who the row is.
        val preview = """{"screen":{"package":"com.msg"},"elements":[{"type":"list_item","viewId":"x","label":"Anna","summary":"thisisfine"}]}"""
        assertNull(FlowTargetGuard.partialValueMatch(preview, "x", listOf("this")))
    }

    @Test
    fun `the guarded flow is valid`() {
        assertTrue(FlowValidator.validate(guarded).isValid)
    }
}
