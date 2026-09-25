package org.ethereumphone.andyclaw.skills.builtin

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.ethereumphone.andyclaw.agent.AgentRunToken
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One run drives the agent display at a time, and only that run puts it away. */
class AgentDisplayLeaseTest {

    private val live = mutableListOf<Job>()

    private fun liveJob() = Job().also { live += it }

    @After
    fun tearDown() {
        // The lease is process-wide: leave it free for the next test.
        live.forEach { it.cancel() }
        AgentDisplayLease.release("a")
        AgentDisplayLease.release("b")
    }

    @Test
    fun `a second live run is refused while the first holds the display`() {
        assertTrue(AgentDisplayLease.claim("a", liveJob()))
        assertFalse(AgentDisplayLease.claim("b", liveJob()))
        assertTrue("the owner can claim again", AgentDisplayLease.claim("a", null))
    }

    @Test
    fun `only the owner's release puts the display away`() {
        AgentDisplayLease.claim("a", liveJob())
        assertFalse("a heartbeat ending does not tear down the user's task", AgentDisplayLease.release("b"))
        assertTrue(AgentDisplayLease.isHeld())
        assertTrue(AgentDisplayLease.release("a"))
        assertFalse(AgentDisplayLease.isHeld())
    }

    @Test
    fun `a cancelled owner never blocks the next run`() {
        val first = liveJob()
        AgentDisplayLease.claim("a", first)
        first.cancel()
        assertTrue(AgentDisplayLease.claim("b", liveJob()))
        assertFalse("the old run's late release leaves the new owner alone", AgentDisplayLease.release("a"))
        assertTrue(AgentDisplayLease.isOwner("b"))
    }

    @Test
    fun `STOP stops the run holding the display, and only that one`() {
        AgentDisplayLease.claim("a", liveJob())
        AgentDisplayLease.noteStop()
        assertTrue(AgentDisplayLease.wasStopped("a"))
        assertFalse(AgentDisplayLease.wasStopped("b"))
    }

    @Test
    fun `a caller outside any run may use a free display but never owns it`() = runBlocking {
        assertTrue(AgentDisplayLease.claimForCaller())
        assertFalse(AgentDisplayLease.isHeld())

        AgentDisplayLease.claim("a", liveJob())
        assertFalse(AgentDisplayLease.claimForCaller())
    }

    @Test
    fun `a run claims through its token`() = runBlocking {
        val token = AgentRunToken(job = liveJob(), id = "a")
        withContext(token) { assertTrue(AgentDisplayLease.claimForCaller()) }
        assertTrue(AgentDisplayLease.isOwner("a"))
        assertFalse(withContext(AgentRunToken(job = liveJob(), id = "b")) { AgentDisplayLease.claimForCaller() })
    }
}
