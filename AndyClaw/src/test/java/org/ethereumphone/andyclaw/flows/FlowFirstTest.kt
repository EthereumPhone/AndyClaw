package org.ethereumphone.andyclaw.flows

import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.autopilot.AutopilotFlowCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowFirstTest {

    private val msg = "org.ethereumhpone.messenger"
    private val sendGoal = "Send 'hi' to Anna"

    /** A flow the way the compiler stores one: its goal with the values taken out. */
    private fun stored(
        version: Int = 1,
        stale: Boolean = false,
        params: List<String> = listOf("body"),
        steps: List<FlowStep>? = null,
        id: String = "messenger.send_body_to_anna",
        app: String = msg,
        goal: String? = "Send '{{body}}' to Anna",
        installedMs: Long = 0L,
    ) = StoredFlow(
        hash = "h$id$version$installedMs",
        flow = Flow(
            flow = id, version = version, app = app, appVersionRange = "*",
            params = params,
            steps = steps ?: listOf(TapStep(viewId = "row"), CheckpointStep("send"), TapStep(viewId = "send_button")),
            intent = goal?.let { FlowIntent(it) },
        ),
        meta = FlowMeta(stale = stale, installedMs = installedMs),
    )

    private fun select(
        flows: List<StoredFlow>,
        values: Map<String, String> = mapOf("body" to "hi"),
        goal: String = sendGoal,
        packageName: String = msg,
        noConfirm: Boolean = true,
        provenance: Provenance = Provenance.USER,
    ) = FlowFirst.select(packageName, goal, values, flows, noConfirm, provenance)

    @Test
    fun `the same task with its values replays the newest flow`() {
        assertEquals(2, select(listOf(stored(1), stored(2)))?.flow?.version)
        // Different text, same task: the value is a parameter.
        assertEquals(1, select(listOf(stored(1)), values = mapOf("body" to "see you"), goal = "Send 'see you' to Anna")?.flow?.version)
    }

    @Test
    fun `a stale flow or a missing value leaves it to the autopilot`() {
        assertNull(select(listOf(stored(stale = true))))
        assertNull(select(listOf(stored()), values = emptyMap()))
        assertNull(select(listOf(stored(params = listOf("body", "subject")))))
    }

    @Test
    fun `a flow that needs its approval card is not replayed from inside the autopilot`() {
        assertNull("confirmations are on", select(listOf(stored()), noConfirm = false))
        assertNull("a message's request", select(listOf(stored()), provenance = Provenance.UNTRUSTED))
    }

    @Test
    fun `a flow that only reads needs no card`() {
        val readOnly = stored(steps = listOf(WaitForStep(viewId = "x")), params = emptyList(), goal = "Show the chats")
        assertEquals(readOnly, select(listOf(readOnly), values = emptyMap(), goal = "Show the chats", noConfirm = false))
    }

    @Test
    fun `another task's flow is never taken, whatever its id`() {
        assertNull(select(listOf(stored()), goal = "Send 'hi' to Bob"))
        // The id is not the test: a flow under the same id whose goal differs is not this task.
        assertNull(select(listOf(stored(goal = "Delete the chat with Anna", params = emptyList())), values = emptyMap(), goal = "Open the chat with Anna"))
    }

    @Test
    fun `turning Wi-Fi on never replays turning it off`() {
        // Cut to 40 characters, both goals compiled to one id before ids carried a hash.
        val on = "Open the quick settings panel and turn Wi-Fi on"
        val off = "Open the quick settings panel and turn Wi-Fi off"
        val legacyId = "settings.open_the_quick_settings_panel_and_turn_w"
        val settings = "com.android.settings"
        val flowOff = stored(id = legacyId, app = settings, goal = off, params = emptyList())
        val flowOn = stored(id = legacyId, app = settings, goal = on, params = emptyList(), version = 2)

        assertNull(select(listOf(flowOff), values = emptyMap(), goal = on, packageName = settings))
        assertNull(select(listOf(flowOn), values = emptyMap(), goal = off, packageName = settings))
        assertEquals(flowOff, select(listOf(flowOff, flowOn), values = emptyMap(), goal = off, packageName = settings))
        assertEquals(flowOn, select(listOf(flowOff, flowOn), values = emptyMap(), goal = on, packageName = settings))

        // And new compilations of the two no longer share an id, so one cannot replace the other.
        assertNotEquals(AutopilotFlowCompiler.flowIdFor(settings, on), AutopilotFlowCompiler.flowIdFor(settings, off))
    }

    @Test
    fun `goals in another script are told apart`() {
        // Every Cyrillic or CJK goal used to compile to "<app>.task".
        val telegram = "org.telegram.messenger"
        val toAnna = stored(id = "messenger.task", app = telegram, goal = "Напиши Анне «{{body}}»")
        val toBoris = stored(id = "messenger.task", app = telegram, goal = "Напиши Борису «{{body}}»", installedMs = 5)
        val chinese = stored(id = "messenger.task", app = telegram, goal = "给安娜发{{body}}", installedMs = 9)
        val all = listOf(toAnna, toBoris, chinese)

        assertEquals(toAnna, select(all, values = mapOf("body" to "привет"), goal = "Напиши Анне «привет»", packageName = telegram))
        assertEquals(toBoris, select(all, values = mapOf("body" to "привет"), goal = "Напиши Борису «привет»", packageName = telegram))
        assertNull(select(all, values = mapOf("body" to "привет"), goal = "Напиши Ивану «привет»", packageName = telegram))

        val ids = listOf("Напиши Анне «привет»", "Напиши Борису «привет»", "给安娜发你好", "给鲍里斯发你好")
            .map { AutopilotFlowCompiler.flowIdFor(telegram, it, mapOf("body" to "привет")) }
        assertEquals("no two of them share an id: $ids", ids.size, ids.toSet().size)
    }

    @Test
    fun `the values must be exactly the flow's parameters`() {
        // Compiled for Anna, the chat was opened by tapping her row — `name` was never typed, so
        // the flow opens Anna's chat whatever name it is given.
        val openChat = stored(goal = "Open the chat with {{name}}", params = emptyList(),
            steps = listOf(TapStep(viewId = "row_anna"), WaitForStep(viewId = "compose")))
        assertNull(select(listOf(openChat), values = mapOf("name" to "Bob"), goal = "Open the chat with Bob"))
        // A value the flow would not use is a part of the task it would not do.
        assertNull(select(listOf(stored()), values = mapOf("body" to "hi", "subject" to "lunch")))
    }

    @Test
    fun `another app's flow for the same goal is not taken`() {
        // Package names ending alike used to share their flow ids too.
        val telegram = stored(app = "org.telegram.messenger")
        assertNull(select(listOf(telegram)))
        assertEquals(telegram, select(listOf(telegram), packageName = "org.telegram.messenger"))
    }

    @Test
    fun `a flow that stores no goal cannot say what it does, and is never chosen`() {
        assertNull(select(listOf(stored(goal = null))))
    }

    @Test
    fun `flows compiled before ids carried a hash are still replayed`() {
        val legacy = stored(id = "messenger.send_body_to_anna")
        assertEquals(legacy, select(listOf(legacy)))
    }

    @Test
    fun `one task compiled twice replays its newest compilation, and not a stale one`() {
        val legacy = stored(id = "messenger.send_body_to_anna", version = 3, installedMs = 100)
        val recompiled = stored(id = AutopilotFlowCompiler.flowIdFor(msg, sendGoal, mapOf("body" to "hi")), version = 1, installedMs = 200)
        assertEquals(recompiled, select(listOf(legacy, recompiled)))
        assertNull(select(listOf(legacy, recompiled.copy(meta = recompiled.meta.copy(stale = true)))))
    }

    @Test
    fun `the same task is the same app, stored goal and parameters`() {
        val a = stored().flow
        assertTrue(FlowFirst.sameTask(a, a.copy(flow = "messenger.send_body_to_anna_0123abcd", version = 1)))
        assertFalse(FlowFirst.sameTask(a, a.copy(intent = FlowIntent("Send '{{body}}' to Bob"))))
        assertFalse(FlowFirst.sameTask(a, a.copy(app = "org.telegram.messenger")))
        assertFalse(FlowFirst.sameTask(a, a.copy(params = listOf("body", "name"))))
        assertFalse(FlowFirst.sameTask(a.copy(intent = null), a.copy(intent = null)))
    }
}
