package org.ethereumphone.andyclaw.safety

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.ExecutionEngine.ToolCallResult
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayLease
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingApprovalExecutorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private val input = buildJsonObject {
        put("to", "+491701234567")
        put("message", "on my way")
    }

    private val ran = mutableListOf<Pair<String, JsonObject>>()

    private fun executor(
        store: PendingApprovalStore,
        result: suspend () -> ToolCallResult = { ToolCallResult("c", "send_sms", "sent", isError = false) },
    ) = PendingApprovalExecutor(
        store = store,
        scope = scope,
        runCall = { entry, input, _ ->
            synchronized(ran) { ran += entry.toolName to input }
            result()
        },
    )

    private fun store() = PendingApprovalStore(tmp.root, TestMac())

    private fun queued(store: PendingApprovalStore) = store.queue(
        PendingApprovalStore.Request(
            source = "xmtp",
            provenance = "UNTRUSTED",
            toolName = "send_sms",
            input = input,
            description = "Send a text message",
            conversationId = "0xabc",
        )
    )!!

    @Test
    fun `approve runs exactly the stored call, once`() = runBlocking {
        val store = store()
        val e = queued(store)
        val ex = executor(store)

        val first = ex.approve(e.id)
        val second = ex.approve(e.id)

        assertEquals("DONE", first.state)
        assertEquals(listOf("send_sms" to input), ran)
        assertEquals("ALREADY_HANDLED", second.state)
        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun `a call that fails says so and leaves the queue`() = runBlocking {
        val store = store()
        val e = queued(store)
        val r = executor(store) { ToolCallResult("c", "send_sms", "no signal", isError = true) }.approve(e.id)
        assertEquals("FAILED", r.state)
        assertTrue(r.message.contains("no signal"))
        assertEquals("FAILED", store.outcome(e.id)?.state)
    }

    @Test
    fun `a call a gate refuses is blocked, not retried`() = runBlocking {
        val store = store()
        val e = queued(store)
        val r = executor(store) {
            ToolCallResult("c", "send_sms", "[Provenance] may only reply there. Blocked.", isError = true,
                phase = ToolCallResult.Phase.BLOCKED_PREFLIGHT)
        }.approve(e.id)
        assertEquals("BLOCKED", r.state)
        assertFalse("the gate's tag is not shown to the user", r.message.contains("[Provenance]"))
        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun `a busy display keeps the card for later`() = runBlocking {
        val store = store()
        val e = queued(store)
        // The display tools answer "busy" as their own result, so it arrives as an executed call.
        val r = executor(store) {
            ToolCallResult("c", "agent_display_autopilot", AgentDisplayLease.BUSY, isError = true, phase = ToolCallResult.Phase.EXECUTED)
        }.approve(e.id)
        assertEquals("BLOCKED", r.state)
        assertEquals("still waiting, and runnable", 1, store.getAll().size)
        assertTrue(store.isExecutable(store.getAll().single()))
    }

    @Test
    fun `a long call answers RUNNING and finishes on its own`() = runBlocking {
        val store = store()
        val e = queued(store)
        val gate = CompletableDeferred<Unit>()
        val ex = executor(store) {
            gate.await()
            ToolCallResult("c", "send_sms", "sent", isError = false)
        }
        val r = ex.approve(e.id, waitMs = 50)
        assertEquals("RUNNING", r.state)
        assertEquals("a second tap does not start it again", "RUNNING", ex.approve(e.id).state)

        gate.complete(Unit)
        repeat(100) { if (store.outcome(e.id) == null) kotlinx.coroutines.delay(20) }
        assertEquals("DONE", store.outcome(e.id)?.state)
        assertEquals(1, ran.size)
    }

    @Test
    fun `decline runs nothing`() {
        val store = store()
        val e = queued(store)
        val r = executor(store).decline(e.id)
        assertEquals("DECLINED", r.state)
        assertTrue(ran.isEmpty())
        assertEquals("ALREADY_HANDLED", executor(store).decline(e.id).state)
    }

    @Test
    fun `an entry that cannot be run exactly is refused`() = runBlocking {
        val store = PendingApprovalStore(tmp.root, TestMac(), holdsSecret = { true })
        val e = queued(store)
        assertEquals("NOT_EXECUTABLE", executor(store).approve(e.id).state)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `the exact-call callbacks approve that call and no other`() = runBlocking {
        val cb = ExactCallCallbacks("send_sms", input)
        assertTrue(cb.onApprovalNeeded("x", "send_sms", buildJsonObject { put("message", "on my way"); put("to", "+491701234567") }))
        assertFalse(cb.onApprovalNeeded("x", "send_sms", buildJsonObject { put("to", "+1555"); put("message", "on my way") }))
        assertFalse(cb.onApprovalNeeded("x", "gmail_send", input))
    }
}
