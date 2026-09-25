package org.ethereumphone.andyclaw.agent

import kotlinx.coroutines.runBlocking
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * STOP — the rear-screen hold, the launcher's button, the live view's — ends the turn of the run
 * that holds the display, with one line and no further model call: the model would only go on
 * driving the display it was just told to leave alone.
 */
class AgentLoopStopTest {

    @Before
    fun setup() {
        ExecutionEngineFactory.clearRouteMemory()
    }

    private fun displaySkill(onTap: () -> Unit = {}) = NativeSkillRegistry().apply {
        register(testSkill("display", testTool("fake_display_tap")) { _, _ ->
            if (!AgentDisplayLease.claimForCaller()) return@testSkill SkillResult.Error(AgentDisplayLease.BUSY)
            onTap()
            SkillResult.Success("tapped")
        })
    }

    @Test
    fun `STOP while the run holds the display ends the turn without another model call`() = runBlocking {
        val registry = displaySkill { AgentDisplayLease.noteStop() }
        val client = ScriptedLlmClient(listOf(
            listOf(toolUse("t1", "fake_display_tap")),
            listOf(toolUse("t2", "fake_display_tap")),
        ))
        val callbacks = RecordingCallbacks()

        AgentLoop(client, registry, Tier.OPEN, enabledSkillIds = setOf("display")).run("go", emptyList(), callbacks)

        assertEquals("no model call after the STOP", 1, client.requests.size)
        assertEquals(AgentLoop.STOPPED_REPLY, callbacks.completed)
        assertTrue(callbacks.errors.isEmpty())
        assertFalse("the run gave the display back", AgentDisplayLease.isHeld())
    }

    @Test
    fun `a stopped autopilot ends the turn the same way`() = runBlocking {
        val registry = NativeSkillRegistry().apply {
            register(testSkill("display", testTool("agent_display_autopilot")) { _, _ ->
                SkillResult.Success("""{"status":"failed","outcome":"stopped","reason":"stopped_by_user","say":"Stopped."}""")
            })
        }
        val client = ScriptedLlmClient(listOf(listOf(toolUse("t1", "agent_display_autopilot"))))
        val callbacks = RecordingCallbacks()

        AgentLoop(client, registry, Tier.OPEN, enabledSkillIds = setOf("display")).run("go", emptyList(), callbacks)

        assertEquals(1, client.requests.size)
        assertEquals(AgentLoop.STOPPED_REPLY, callbacks.completed)
    }

    @Test
    fun `a run that never held the display is not stopped by a STOP meant for another`() = runBlocking {
        val other = kotlinx.coroutines.Job()
        AgentDisplayLease.claim("someone-else", other)
        AgentDisplayLease.noteStop()
        AgentDisplayLease.release("someone-else")
        other.cancel()

        val registry = displaySkill()
        val client = ScriptedLlmClient(listOf(listOf(toolUse("t1", "fake_display_tap"))))
        val callbacks = RecordingCallbacks()

        AgentLoop(client, registry, Tier.OPEN, enabledSkillIds = setOf("display")).run("go", emptyList(), callbacks)

        assertEquals("the model got its answer turn", 2, client.requests.size)
        assertEquals("done", callbacks.completed)
    }
}
