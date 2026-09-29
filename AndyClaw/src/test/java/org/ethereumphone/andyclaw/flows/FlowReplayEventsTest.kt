package org.ethereumphone.andyclaw.flows

import kotlinx.coroutines.test.runTest
import org.ethereumphone.andyclaw.autopilot.AutopilotEvent
import org.ethereumphone.andyclaw.autopilot.AutopilotEvent.Kind
import org.ethereumphone.andyclaw.autopilot.AutopilotEventSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** IPC-08: a compiled-flow replay is told like an autopilot run, so the home screen can show it. */
class FlowReplayEventsTest {

    private class Screen(val viewIds: List<String>, val texts: Map<String, String> = emptyMap()) {
        fun json(): String {
            val elements = viewIds.joinToString(",") { id ->
                val label = texts[id]?.let { ""","label":"$it"""" } ?: ""
                """{"id":0,"type":"row","viewId":"$id"$label,"actions":["click"]}"""
            }
            return """{"screen":{"package":"com.msg"},"elements":[$elements],"scrollable":false}"""
        }
    }

    private class FakeDriver(screens: List<Screen>) : FlowDisplayDriver {
        private val queue = ArrayDeque(screens)
        var current: Screen = queue.removeFirst()
        override suspend fun installedVersion(packageName: String) = "7.3.0"
        override suspend fun ensureApp(packageName: String) = true
        override suspend fun uiTree(): String = current.json()
        override suspend fun clickNode(viewId: String, index: Int): FlowDispatch {
            if (viewId !in current.viewIds) return FlowDispatch.NOT_DISPATCHED
            if (queue.isNotEmpty()) current = queue.removeFirst()
            return FlowDispatch.DONE
        }
        override suspend fun setNodeText(viewId: String, text: String): FlowDispatch =
            if (viewId in current.viewIds) FlowDispatch.DONE else FlowDispatch.NOT_DISPATCHED
    }

    private val listScreen = Screen(listOf("conversation_list", "search_button"))
    private val threadScreen = Screen(listOf("toolbar_title", "compose_text", "send_button"), mapOf("toolbar_title" to "Anna"))
    private val sentScreen = Screen(listOf("toolbar_title", "conversation_item_sent"))

    private val flow = Flow(
        flow = "msg.send",
        version = 1,
        app = "com.msg",
        appVersionRange = ">=7,<8",
        params = listOf("body"),
        preconditions = listOf(NodeExists(viewId = "conversation_list")),
        steps = listOf(
            TapStep(viewId = "search_button"),
            TypeStep(target = Selector(viewId = "compose_text"), value = "{{body}}"),
            AssertStep(viewId = "toolbar_title", nodeTextContains = "Anna"),
            CheckpointStep("send"),
            TapStep(viewId = "send_button"),
        ),
        postconditions = listOf(NodeExists(viewId = "conversation_item_sent")),
    )

    private class Recorder : AutopilotEventSink {
        val events = mutableListOf<AutopilotEvent>()
        override fun onEvent(event: AutopilotEvent) { events += event }
        val terminals get() = events.filter { it.kind == Kind.DONE || it.kind == Kind.FAILED }
    }

    private suspend fun replay(
        driver: FlowDisplayDriver,
        sink: AutopilotEventSink,
        stop: FlowStopSignal = FlowStopSignal.NEVER,
    ): Pair<FlowReplayEvents, FlowRunResult> {
        val events = FlowReplayEvents(flow, sink, clock = { 0L })
        events.started()
        val result = FlowInterpreter(
            driver = driver,
            checkpoints = FlowCheckpointHandler { _, _, _ -> true },
            sleep = { },
            clock = { 0L },
            stop = stop,
            actions = events,
        ).run(flow, mapOf("body" to "see you at 6"))
        events.finished(result, handsOver = result is FlowRunResult.Aborted && FlowRunAccounting.mayFallBack(result))
        return events to result
    }

    @Test
    fun `a replay is STARTED, a step per action, and one DONE`() = runTest {
        val sink = Recorder()
        val (events, result) = replay(FakeDriver(listOf(listScreen, threadScreen, sentScreen)), sink)
        assertTrue("expected completion, got $result", result is FlowRunResult.Completed)

        val first = sink.events.first()
        assertEquals(Kind.STARTED, first.kind)
        assertTrue(first.runId.startsWith("flow-"))
        assertEquals(listOf("Tap search button", "Type into compose text", "Tap send button"), first.subgoals)

        assertEquals(listOf("tap", "type", "tap"), sink.events.filter { it.kind == Kind.ACTING }.map { it.action })
        assertEquals(listOf(1, 2, 3), sink.events.filter { it.kind == Kind.SETTLED }.map { it.step })
        assertEquals(3, sink.events.count { it.kind == Kind.SUBGOAL_DONE })

        val last = sink.events.last()
        assertEquals(Kind.DONE, last.kind)
        assertEquals("success", last.outcome)
        assertEquals(1, sink.terminals.size)
        assertTrue(sink.events.all { it.runId == events.runId })
        // No element bounds are known, so no target; and a typed value never leaves.
        assertTrue(sink.events.all { it.target == null })
        assertFalse(sink.events.any { e -> listOfNotNull(e.message, e.reason, e.action).any { "see you" in it } || e.subgoals.any { "see you" in it } })
    }

    @Test
    fun `STOP ends the replay's card as stopped`() = runTest {
        val sink = Recorder()
        replay(FakeDriver(listOf(listScreen, threadScreen, sentScreen)), sink, stop = { true })
        val last = sink.terminals.single()
        assertEquals(Kind.FAILED, last.kind)
        assertEquals("stopped", last.outcome)
    }

    @Test
    fun `a replay the screens outgrew hands over, one that may have acted does not`() {
        val stale = FlowRunResult.Aborted(FlowAbortReason.CHECKSUM_MISMATCH, "changed", 0, 0, emptyList())
        assertEquals("handoff", FlowReplayEvents.abortOutcome(stale, handsOver = true).first.wire)
        assertEquals("failed", FlowReplayEvents.abortOutcome(stale, handsOver = false).first.wire)
        val committed = stale.copy(committed = true)
        assertEquals("failed", FlowReplayEvents.abortOutcome(committed, handsOver = true).first.wire)
        val sensitive = stale.copy(reason = FlowAbortReason.SENSITIVE_TARGET)
        assertEquals("sensitive", FlowReplayEvents.abortOutcome(sensitive, handsOver = false).second)
        assertTrue(FlowReplayEvents.abortOutcome(stale, true).third.length <= 120)
    }

    @Test
    fun `a cancel after the end adds nothing, and a cancel before it is the one ending`() {
        val sink = Recorder()
        val events = FlowReplayEvents(flow, sink)
        events.started()
        events.cancelled()
        events.finished(FlowRunResult.Completed(1, 0, emptyList()), handsOver = false)
        events.crashed()
        assertEquals(listOf(Kind.STARTED, Kind.FAILED), sink.events.map { it.kind })
        assertEquals("cancelled", sink.events.last().outcome)
    }

    @Test
    fun `a sink that throws does not stop the replay`() = runTest {
        val (_, result) = replay(FakeDriver(listOf(listScreen, threadScreen, sentScreen)), AutopilotEventSink { error("boom") })
        assertTrue(result is FlowRunResult.Completed)
    }

    @Test
    fun `a step's label names the view, never a value`() {
        assertEquals("Tap send button", FlowReplayEvents.label(TapStep(viewId = "org.app:id/send_button")))
        assertEquals("Type into message", FlowReplayEvents.label(TypeStep(Selector(viewId = "message"), "{{body}}")))
        assertEquals("Tap", FlowReplayEvents.label(TapStep(viewId = null)))
        assertNull(FlowReplayEvents(flow, AutopilotEventSink { }).subgoals.firstOrNull { "{{" in it })
    }
}
