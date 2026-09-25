package org.ethereumphone.andyclaw

import android.content.ContextWrapper
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.agent.AgentResponse
import org.ethereumphone.andyclaw.agent.AgentRunner
import org.ethereumphone.andyclaw.heartbeat.HeartbeatConfig
import org.ethereumphone.andyclaw.heartbeat.HeartbeatSkipReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The heartbeat that actually runs on a dGEN1 is the one [NodeRuntime] builds, and it used to be
 * built without the configuration — then rebuilt, bare, whenever the agent runner was set. Its
 * backstop was zero on every device, so every tick ran and was billed.
 */
class NodeRuntimeConfigTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the ethOS init order leaves the live heartbeat with its backstop`() = runBlocking {
        val dir = temp.newFolder("files")
        val file = File(dir, "HEARTBEAT.md").apply { writeText("# Heartbeat\n\n- [ ] check the flight\n") }
        val calls = AtomicInteger()
        val runtime = NodeRuntime(object : ContextWrapper(null) {
            override fun getFilesDir(): File = dir
        })

        // HeartbeatBindingService.ensureRuntimeReady: the config, then initialize.
        runtime.heartbeatConfig = HeartbeatConfig(heartbeatFilePath = file.absolutePath, backstopQuietMs = 600_000L)
        runtime.initialize()
        // Setting the runner afterwards (NodeForegroundService does) must not lose the config.
        runtime.agentRunner = object : AgentRunner {
            override suspend fun run(
                prompt: String,
                systemPrompt: String?,
                skillsPrompt: String?,
                provenance: Provenance,
                conversationId: String?,
            ): AgentResponse {
                calls.incrementAndGet()
                return AgentResponse("HEARTBEAT_OK")
            }
        }

        runtime.runHeartbeatNow(eventDriven = true)
        val tick = runtime.runHeartbeatNow()

        assertEquals("the event-driven run used the runner set after initialize", 1, calls.get())
        assertEquals(HeartbeatSkipReason.RECENT_EVENT_TRIGGER, tick?.skipReason)
    }
}
