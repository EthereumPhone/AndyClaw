package org.ethereumphone.andyclaw.heartbeat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.agent.AgentResponse
import org.ethereumphone.andyclaw.agent.AgentRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The trigger inversion: the clock is the backstop, not the loop.
 *
 * `andyclaw-to-agent-first.md` §3 — "an ambient device that thinks every 30 minutes isn't
 * ambient." Once something event-driven has woken the agent, the tick behind it re-reads the
 * same file against the same world and bills the user for it.
 */
class HeartbeatBackstopTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class RecordingRunner : AgentRunner {
        val prompts = mutableListOf<String>()
        override suspend fun run(
            prompt: String,
            systemPrompt: String?,
            skillsPrompt: String?,
            provenance: Provenance,
            conversationId: String?,
        ): AgentResponse {
            prompts += prompt
            return AgentResponse("done")
        }
    }

    private var now = 1_700_000_000_000L

    private fun runner(
        workspace: File,
        backstopMs: Long,
        agent: AgentRunner,
        results: MutableList<HeartbeatResult>,
    ): HeartbeatRunner {
        val r = HeartbeatRunner(
            scope = TestScope(StandardTestDispatcher()),
            agentRunner = agent,
            workspaceDir = workspace.absolutePath,
            onResult = { results += it },
            clock = { now },
        )
        r.updateConfig(
            HeartbeatConfig(
                heartbeatFilePath = File(workspace, "HEARTBEAT.md").absolutePath,
                backstopQuietMs = backstopMs,
            )
        )
        return r
    }

    private fun workspaceWithTasks(): File {
        val dir = temp.newFolder("workspace")
        // `isContentEffectivelyEmpty` treats a bare header as nothing to do, so the file
        // has to carry a real task or every run skips for the wrong reason.
        File(dir, "HEARTBEAT.md").writeText("# Heartbeat\n\n- [ ] check the flight status\n")
        return dir
    }

    @Test
    fun `a tick right after an event-driven run is skipped`() = runTest {
        val results = mutableListOf<HeartbeatResult>()
        val agent = RecordingRunner()
        val hb = runner(workspaceWithTasks(), HeartbeatConfig.DEFAULT_BACKSTOP_QUIET_MS, agent, results)

        hb.noteEventTrigger()
        now += 60_000

        val result = hb.runOnce()
        assertEquals(HeartbeatOutcome.SKIPPED, result.outcome)
        assertEquals(HeartbeatSkipReason.RECENT_EVENT_TRIGGER, result.skipReason)
        assertEquals("the agent must not have been asked anything", 0, agent.prompts.size)
    }

    @Test
    fun `the schedule still catches a quiet stretch`() = runTest {
        val results = mutableListOf<HeartbeatResult>()
        val agent = RecordingRunner()
        val hb = runner(workspaceWithTasks(), HeartbeatConfig.DEFAULT_BACKSTOP_QUIET_MS, agent, results)

        hb.noteEventTrigger()
        now += HeartbeatConfig.DEFAULT_BACKSTOP_QUIET_MS + 1

        val result = hb.runOnce()
        assertNull(result.skipReason)
        assertEquals(1, agent.prompts.size)
    }

    @Test
    fun `a device with no event-driven triggers runs every tick`() = runTest {
        val results = mutableListOf<HeartbeatResult>()
        val agent = RecordingRunner()
        // Backstop of zero: the old behaviour, and the right one for a device where the
        // clock is genuinely the only thing that wakes the agent.
        val hb = runner(workspaceWithTasks(), backstopMs = 0L, agent = agent, results = results)

        hb.noteEventTrigger()
        val result = hb.runOnce()

        assertNull(result.skipReason)
        assertEquals(1, agent.prompts.size)
    }

    @Test
    fun `nothing has happened yet, so nothing is suppressed`() = runTest {
        val results = mutableListOf<HeartbeatResult>()
        val agent = RecordingRunner()
        val hb = runner(workspaceWithTasks(), HeartbeatConfig.DEFAULT_BACKSTOP_QUIET_MS, agent, results)

        val result = hb.runOnce()
        assertNull(result.skipReason)
        assertEquals(1, agent.prompts.size)
    }

    @Test
    fun `an empty heartbeat file still reports the reason that actually applies`() = runTest {
        val results = mutableListOf<HeartbeatResult>()
        val agent = RecordingRunner()
        val empty = temp.newFolder("empty")
        File(empty, "HEARTBEAT.md").writeText("# Heartbeat\n")
        val hb = runner(empty, HeartbeatConfig.DEFAULT_BACKSTOP_QUIET_MS, agent, results)

        hb.noteEventTrigger()
        val result = hb.runOnce()

        // Both reasons apply; the backstop is checked first because it is the one the user
        // can act on, and a log full of EMPTY_HEARTBEAT_FILE would hide the inversion
        // working.
        assertEquals(HeartbeatSkipReason.RECENT_EVENT_TRIGGER, result.skipReason)
    }

    // ── The backstop, honestly ─────────────────────────────────────────

    @Test
    fun `an event-driven run is never stopped by the window`() = runTest {
        val results = mutableListOf<HeartbeatResult>()
        val agent = RecordingRunner()
        val hb = runner(workspaceWithTasks(), HeartbeatConfig.DEFAULT_BACKSTOP_QUIET_MS, agent, results)

        hb.runNow(eventDriven = true)
        now += 60_000
        val second = hb.runNow(eventDriven = true)

        assertEquals(2, agent.prompts.size)
        assertNull(second?.skipReason)
    }

    @Test
    fun `a successful event-driven run covers the next tick, a failed one does not`() = runTest {
        val results = mutableListOf<HeartbeatResult>()
        var fail = true
        val agent = object : AgentRunner {
            var calls = 0
            override suspend fun run(
                prompt: String,
                systemPrompt: String?,
                skillsPrompt: String?,
                provenance: Provenance,
                conversationId: String?,
            ): AgentResponse {
                calls++
                return if (fail) AgentResponse("boom", isError = true) else AgentResponse("done")
            }
        }
        val hb = runner(workspaceWithTasks(), HeartbeatConfig.DEFAULT_BACKSTOP_QUIET_MS, agent, results)

        hb.runNow(eventDriven = true) // fails
        now += 60_000
        assertNull("a failed run covered nothing", hb.runOnce().skipReason)

        fail = false
        hb.runNow(eventDriven = true)
        now += 60_000
        assertEquals(HeartbeatSkipReason.RECENT_EVENT_TRIGGER, hb.runOnce().skipReason)
    }

    @Test
    fun `requests while a run is going fold into one more run`() = runTest {
        val results = mutableListOf<HeartbeatResult>()
        val gate = CompletableDeferred<Unit>()
        val agent = object : AgentRunner {
            var calls = 0
            override suspend fun run(
                prompt: String,
                systemPrompt: String?,
                skillsPrompt: String?,
                provenance: Provenance,
                conversationId: String?,
            ): AgentResponse {
                calls++
                if (calls == 1) gate.await()
                return AgentResponse("done")
            }
        }
        val hb = runner(workspaceWithTasks(), 0L, agent, results)

        val first = async { hb.runNow(eventDriven = true) }
        yield()
        assertNull(hb.runNow(eventDriven = true))
        assertNull(hb.runNow(eventDriven = true))
        gate.complete(Unit)
        first.await()

        assertEquals("one run, plus one trailing run for everything that asked meanwhile", 2, agent.calls)
    }

    @Test
    fun `a message from somebody else does not suppress the user's own list`() = runTest {
        val results = mutableListOf<HeartbeatResult>()
        val agent = RecordingRunner()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val hb = HeartbeatRunner(
            scope = scope,
            agentRunner = agent,
            workspaceDir = temp.root.absolutePath,
            onResult = { results += it },
            clock = { now },
        )
        val ws = workspaceWithTasks()
        hb.updateConfig(HeartbeatConfig(heartbeatFilePath = File(ws, "HEARTBEAT.md").absolutePath,
            backstopQuietMs = HeartbeatConfig.DEFAULT_BACKSTOP_QUIET_MS))

        hb.requestNowWithContext("gm from 0xabc", Provenance.UNTRUSTED, "0xabc")
        scope.testScheduler.advanceUntilIdle()
        now += 60_000

        assertNull(hb.runOnce().skipReason)
    }

    @Test
    fun `a tick that arrives during an event-driven run does not buy a second run`() = runTest {
        val results = mutableListOf<HeartbeatResult>()
        val gate = CompletableDeferred<Unit>()
        val agent = object : AgentRunner {
            var calls = 0
            override suspend fun run(
                prompt: String,
                systemPrompt: String?,
                skillsPrompt: String?,
                provenance: Provenance,
                conversationId: String?,
            ): AgentResponse {
                calls++
                gate.await()
                return AgentResponse("done")
            }
        }
        val hb = runner(workspaceWithTasks(), HeartbeatConfig.DEFAULT_BACKSTOP_QUIET_MS, agent, results)

        val event = async { hb.runNow(eventDriven = true) }
        yield()
        assertNull("the tick is folded into the run going", hb.runNow())
        gate.complete(Unit)
        event.await()

        assertEquals(1, agent.calls)
        assertEquals(HeartbeatSkipReason.RECENT_EVENT_TRIGGER, results.last().skipReason)
    }

    @Test
    fun `a cancelled run does not leave the heartbeat folded for good`() = runTest {
        val results = mutableListOf<HeartbeatResult>()
        val never = CompletableDeferred<Unit>()
        var block = true
        val agent = object : AgentRunner {
            var calls = 0
            override suspend fun run(
                prompt: String,
                systemPrompt: String?,
                skillsPrompt: String?,
                provenance: Provenance,
                conversationId: String?,
            ): AgentResponse {
                calls++
                if (block) never.await()
                return AgentResponse("done")
            }
        }
        val hb = runner(workspaceWithTasks(), 0L, agent, results)

        val stuck = async { hb.runNow() }
        yield()
        stuck.cancel()
        yield()
        block = false

        val next = hb.runNow()
        assertEquals("the next request runs rather than folding into a dead run", 2, agent.calls)
        assertNull(next?.skipReason)
    }
}
