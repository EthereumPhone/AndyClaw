package org.ethereumphone.andyclaw.agent

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayLease
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Which agent run a tool call belongs to. One per [AgentLoop.run], shared by that run's
 * sub-agents, and published into every tool call's coroutine context next to the provenance.
 *
 * Runs share one set of skill instances — the in-app chat, the launcher chat, heartbeats,
 * Telegram, cron — so anything a run holds has to say whose it is:
 *
 * - **The agent display.** [AgentDisplayLease] lets one run own it at a time, and only the owner
 *   puts it away when it ends. Before this, any run finishing — a heartbeat set off by a
 *   notification — parked the display under an autopilot that was still driving it.
 * - **STOP.** A STOP is aimed at whatever is on the agent display, so it stops the run that held
 *   the display when it was pressed ([AgentDisplayLease.noteStop]) — not a chat that merely
 *   happens to be running, and not the next run, which used to have its STOP flag cleared by
 *   whichever run started after it.
 */
class AgentRunToken(
    /** The run's [Job], so a lease held by a run that was cancelled or finished never blocks. */
    val job: Job?,
    val id: String = UUID.randomUUID().toString(),
) : AbstractCoroutineContextElement(Key) {

    /** STOP was pressed — rear screen, launcher or live view — while this run held the display. */
    val stopRequested: Boolean get() = AgentDisplayLease.wasStopped(id)

    /**
     * An untrusted run has read the owner's private data. From then on it may not reach the web,
     * which is how what it read could leave; see `ProvenanceGate.privacyVerdict`. Only ever set,
     * never cleared, for the length of the run.
     */
    @Volatile
    var readPrivateData: Boolean = false

    companion object Key : CoroutineContext.Key<AgentRunToken>
}

/** The calling run's token, or null outside an agent run. */
suspend fun currentRunToken(): AgentRunToken? = currentCoroutineContext()[AgentRunToken]
