package org.ethereumphone.andyclaw.heartbeat

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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
}
