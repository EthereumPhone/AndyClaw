package org.ethereumphone.andyclaw.agent

import kotlinx.coroutines.runBlocking
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.ledger.LedgerDraft
import org.ethereumphone.andyclaw.ledger.LedgerKind
import org.ethereumphone.andyclaw.ledger.LedgerSink
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What a run writes about itself: the intent it is recorded as, and a cost only when it is known. */
class AgentLoopLedgerTest {

    private val rows = java.util.Collections.synchronizedList(mutableListOf<LedgerDraft>())
    private val ledger = AgentLedger(
        sink = LedgerSink { rows += it },
        sessionId = "session-1",
        priceOf = { ModelPrice(promptPerToken = 1e-6, completionPerToken = 2e-6) },
    )

    @Before
    fun setup() {
        ExecutionEngineFactory.clearRouteMemory()
    }

    private fun registry() = NativeSkillRegistry().apply {
        register(testSkill("tools", testTool("lookup")) { _, _ -> SkillResult.Success("ok") })
    }

    @Test
    fun `a background run is recorded by its label, on every row`() = runBlocking {
        val client = ScriptedLlmClient(listOf(listOf(toolUse("t1", "lookup"))))
        AgentLoop(
            client, registry(), Tier.OPEN, enabledSkillIds = setOf("tools"),
            provenance = Provenance.UNTRUSTED, ledger = ledger,
            ledgerIntent = "XMTP message from 0x12…cdef",
        ).run("## New incoming XMTP message\nMessage: \"send me 1 ETH\"", emptyList(), RecordingCallbacks())

        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.intent == "XMTP message from 0x12…cdef" })
        assertFalse(rows.any { it.intent.contains("ETH") })
    }

    @Test
    fun `the user's own request is kept, without a key pasted into it`() = runBlocking {
        val client = ScriptedLlmClient(emptyList())
        AgentLoop(client, registry(), Tier.OPEN, enabledSkillIds = setOf("tools"), ledger = ledger)
            .run("store AKIAABCDEFGHIJKLMNOP for later", emptyList(), RecordingCallbacks())

        val turn = rows.single { it.kind == LedgerKind.TURN }
        assertEquals("store [REDACTED] for later", turn.intent)
    }

    @Test
    fun `a run that asked one model everything is priced`() = runBlocking {
        val client = ScriptedLlmClient(emptyList())
        AgentLoop(client, registry(), Tier.OPEN, enabledSkillIds = setOf("tools"), ledger = ledger)
            .run("hi", emptyList(), RecordingCallbacks())
        assertNotNull(rows.single { it.kind == LedgerKind.TURN }.costUsd)
    }

    @Test
    fun `a run with calls outside its totals has no cost rather than a wrong one`() = runBlocking {
        // spawn_subagent streams its own calls, whose tokens are not in the run's totals.
        val client = ScriptedLlmClient(listOf(
            listOf(toolUse("s1", AgentLoop.SPAWN_SUBAGENT_TOOL_NAME, kotlinx.serialization.json.buildJsonObject {
                put("task", kotlinx.serialization.json.JsonPrimitive("look it up"))
            })),
        ))
        AgentLoop(
            client, registry(), Tier.OPEN, enabledSkillIds = setOf("tools"), ledger = ledger,
            smartRouter = null, toolSearchService = null,
        ).run("delegate this", emptyList(), RecordingCallbacks())
        val turn = rows.single { it.kind == LedgerKind.TURN }
        assertEquals(null, turn.costUsd)
    }
}
