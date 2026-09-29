package org.ethereumphone.andyclaw.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.ethereumphone.andyclaw.autopilot.JevAnswer
import org.ethereumphone.andyclaw.autopilot.JevClient
import org.ethereumphone.andyclaw.autopilot.JevQuestion
import org.ethereumphone.andyclaw.autopilot.JevRequest
import org.ethereumphone.andyclaw.autopilot.JevResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JevToolPrefetchTest {

    private fun tool(name: String, description: String, required: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("name", name)
            put("description", description)
            putJsonObject("input_schema") {
                put("type", "object")
                if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            }
        }

    private val tools = listOf(
        tool("get_device_info", "Battery level, charging state, storage and Android version."),
        tool("get_connectivity_status", "Wi-Fi network name, signal, mobile data and IP addresses."),
        tool("write_global_setting", "Change a global setting such as battery saver.", listOf("key", "value")),
        tool("send_sms", "Send an SMS message.", listOf("to", "body")),
        tool("agent_display_get_ui_tree", "Read the screen of the agent display."),
        tool("spawn_subagent", "Delegate a subtask."),
        tool("memory_store", "Store a memory about the battery."),
    )

    private class FakeJev(private val choice: String?, private val confidence: Double, private val delayMs: Long = 0) : JevClient {
        var calls = 0
        var lastRequest: JevRequest? = null
        override suspend fun evaluate(request: JevRequest): JevResponse {
            calls++
            lastRequest = request
            if (delayMs > 0) delay(delayMs)
            val answers = if (choice == null) emptyMap() else
                mapOf("tool" to JevAnswer.Choice(choice, mapOf(choice to confidence), confidence))
            return JevResponse(answers, rttMs = delayMs)
        }
    }

    @Test
    fun `only argument-free read-only local tools are candidates`() {
        val names = JevToolPrefetch.candidatesFrom(tools).map { it.name }
        assertEquals(listOf("get_device_info", "get_connectivity_status"), names)
    }

    @Test
    fun `a request sharing no word with any candidate never reaches Jev`() = runBlocking {
        val jev = FakeJev("get_device_info", 0.99)
        val prefetch = JevToolPrefetch(jev = { jev }, enabled = { true })
        assertNull(prefetch.pick("Write a haiku about spring", tools))
        assertEquals(0, jev.calls)
    }

    @Test
    fun `a confident pick is returned and only candidates are offered`() = runBlocking {
        val jev = FakeJev("get_device_info", 0.97)
        val prefetch = JevToolPrefetch(jev = { jev }, enabled = { true })
        val pick = prefetch.pick("What's my battery percentage?", tools)
        assertEquals("get_device_info", pick?.toolName)
        val options = (jev.lastRequest!!.questions["tool"] as JevQuestion.Choice).options.keys
        assertTrue(JevToolPrefetch.NONE in options)
        assertFalse("write tools are never offered", "write_global_setting" in options)
        assertFalse("memory_store is not READ", "memory_store" in options)
    }

    @Test
    fun `below the threshold, none, or an unknown tool is no pick`() = runBlocking {
        assertNull(JevToolPrefetch({ FakeJev("get_device_info", 0.6) }, { true }).pick("battery level?", tools))
        assertNull(JevToolPrefetch({ FakeJev(JevToolPrefetch.NONE, 0.99) }, { true }).pick("battery level?", tools))
        assertNull(JevToolPrefetch({ FakeJev("send_sms", 0.99) }, { true }).pick("battery level?", tools))
        assertNull(JevToolPrefetch({ FakeJev(null, 0.0) }, { true }).pick("battery level?", tools))
    }

    @Test
    fun `disabled or without a client does nothing`() = runBlocking {
        val jev = FakeJev("get_device_info", 0.99)
        assertNull(JevToolPrefetch({ jev }, { false }).pick("battery level?", tools))
        assertNull(JevToolPrefetch({ null }, { true }).pick("battery level?", tools))
        assertEquals(0, jev.calls)
    }

    @Test
    fun `a slow Jev is abandoned at the budget`() = runBlocking {
        val jev = FakeJev("get_device_info", 0.99, delayMs = JevToolPrefetch.BUDGET_MS + 2_000)
        val started = System.currentTimeMillis()
        assertNull(JevToolPrefetch({ jev }, { true }).pick("battery level?", tools))
        assertTrue(System.currentTimeMillis() - started < JevToolPrefetch.BUDGET_MS + 1_000)
    }

    @Test
    fun `a failing Jev is no pick, but cancelling the turn still cancels`() = runBlocking {
        val failing = JevClient { throw java.io.IOException("504") }
        assertNull(JevToolPrefetch({ failing }, { true }).pick("battery level?", tools))
        // A stray CancellationException from the client is a failure like any other…
        val stray = JevClient { throw CancellationException("client timeout") }
        assertNull(JevToolPrefetch({ stray }, { true }).pick("battery level?", tools))
        // …but the turn's own cancel is not turned into "no pick" and carried on from.
        var returned = false
        val job = launch {
            JevToolPrefetch({ FakeJev("get_device_info", 0.99, delayMs = 5_000) }, { true })
                .pick("battery level?", tools)
            returned = true
        }
        delay(100)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(returned)
    }
}
