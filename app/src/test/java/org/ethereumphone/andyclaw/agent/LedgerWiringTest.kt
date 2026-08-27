package org.ethereumphone.andyclaw.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.ExecutionEngine.ToolCall
import org.ethereumphone.andyclaw.ledger.LedgerDraft
import org.ethereumphone.andyclaw.ledger.LedgerKind
import org.ethereumphone.andyclaw.ledger.LedgerOutcome
import org.ethereumphone.andyclaw.ledger.LedgerSink
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.skills.ToolEffect
import org.ethereumphone.andyclaw.skills.ToolRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "Every tool execution produces a ledger row carrying provenance, rung, outcome and cost."
 *
 * Driven through the real engine, for the same reason `RouteGateTest` is: the claim is about
 * what the *engine* records, and a row written by a helper that the engine does not call
 * would prove nothing.
 */
class LedgerWiringTest {

    private lateinit var registry: NativeSkillRegistry
    private val rows = mutableListOf<LedgerDraft>()
    private val sink = LedgerSink { rows += it }

    private fun tool(
        name: String,
        rung: Int? = null,
        targets: List<String> = emptyList(),
        effect: ToolEffect? = null,
    ) = ToolDefinition(
        name = name,
        description = "test tool",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        },
        effect = effect,
        rung = rung,
        targetPackages = targets,
    )

    private fun skill(id: String, vararg tools: ToolDefinition) = object : AndyClawSkill {
        override val id = id
        override val name = id
        override val baseManifest = SkillManifest(description = id, tools = tools.toList())
        override val privilegedManifest: SkillManifest? = null
        override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult =
            if (tool == "always_fails") SkillResult.Error("nope") else SkillResult.Success("ran:$tool")
    }

    private object Callbacks : AgentLoop.Callbacks {
        override fun onToken(text: String) {}
        override fun onToolExecution(toolName: String) {}
        override fun onToolResult(toolName: String, result: SkillResult, input: JsonObject?) {}
        override suspend fun onApprovalNeeded(
            description: String,
            toolName: String?,
            toolInput: JsonObject?,
        ): Boolean = true
        override suspend fun onPermissionsNeeded(permissions: List<String>): Boolean = true
        override fun onComplete(fullText: String, tokenUsage: TokenUsageSnapshot?) {}
        override fun onError(error: Throwable) {}
    }

    private val ledger = AgentLedger(
        sink = sink,
        sessionId = "session-1",
        priceOf = { id ->
            if (id == "priced/model") ModelPrice(promptPerToken = 1e-6, completionPerToken = 2e-6) else null
        },
        flowRefOf = { name -> if (name == "flow_signal_send") "signal.send@3" else null },
    )

    @Before
    fun setup() {
        rows.clear()
        ExecutionEngineFactory.clearRouteMemory()
        registry = NativeSkillRegistry()
        registry.register(
            skill(
                "tools",
                tool("read_sms", rung = 0, targets = listOf("com.android.messaging"), effect = ToolEffect.READ),
                tool("always_fails", effect = ToolEffect.READ),
                tool("flow_signal_send", rung = 3, targets = listOf("org.thoughtcrime.securesms"), effect = ToolEffect.READ),
                tool("agent_display_create", rung = ToolRoutes.RUNG_DISPLAY, effect = ToolEffect.READ),
                tool("agent_send_transaction", effect = ToolEffect.SENSITIVE),
            )
        )
    }

    private fun engine(
        provenance: Provenance = Provenance.USER,
        withLedger: AgentLedger? = ledger,
    ) = ExecutionEngineFactory.create(
        skillRegistry = registry,
        tier = Tier.OPEN,
        enabledSkillIds = registry.getAll().map { it.id }.toSet(),
        safetyLayer = null,
        agentCallbacks = Callbacks,
        budgetConfig = null,
        provenance = provenance,
        ledger = withLedger,
        intent = "text anna",
    )

    private fun call(name: String, pkg: String? = null) = ToolCall(
        id = "tc-$name",
        name = name,
        input = if (pkg == null) JsonObject(emptyMap()) else JsonObject(mapOf("package_name" to JsonPrimitive(pkg))),
    )

    @Test
    fun `a tool that runs produces one row with its rung and outcome`() = runBlocking {
        engine().executeBatch(listOf(call("read_sms")))

        val row = rows.single()
        assertEquals(LedgerKind.TOOL, row.kind)
        assertEquals("session-1", row.sessionId)
        assertEquals("text anna", row.intent)
        assertEquals("USER", row.provenance)
        assertEquals(LedgerOutcome.OK, row.outcome)
        assertEquals(0, row.routeRung)
        assertEquals("read_sms", row.actions.single().tool)
        assertTrue(row.actions.single().ok)
    }

    @Test
    fun `a tool that fails is recorded as an error, not as a block`() = runBlocking {
        engine().executeBatch(listOf(call("always_fails")))

        val row = rows.single()
        assertEquals(LedgerOutcome.ERROR, row.outcome)
        assertTrue(!row.actions.single().ok)
    }

    @Test
    fun `a blocked tool is recorded, and as blocked`() = runBlocking {
        // The most interesting row there is: the boundary doing its job. A pre-flight block
        // never reaches a post-processor, so without the callback hook the ledger would
        // show only the tools that were allowed to run.
        engine(provenance = Provenance.UNTRUSTED).executeBatch(listOf(call("agent_send_transaction")))

        val row = rows.single()
        assertEquals(LedgerOutcome.BLOCKED, row.outcome)
        assertEquals("UNTRUSTED", row.provenance)
        assertEquals("agent_send_transaction", row.actions.single().tool)
        assertTrue(row.actions.single().note!!.contains("Provenance"))
    }

    @Test
    fun `the route gate's block is recorded too`() = runBlocking {
        engine().executeBatch(listOf(call("agent_display_create", pkg = "org.thoughtcrime.securesms")))

        val row = rows.single()
        assertEquals(LedgerOutcome.BLOCKED, row.outcome)
        assertTrue(row.actions.single().note!!.contains("[Route]"))
    }

    @Test
    fun `exactly one row per call, however the call ends`() = runBlocking {
        engine().executeBatch(listOf(call("read_sms"), call("always_fails")))
        assertEquals(2, rows.size)

        rows.clear()
        engine(provenance = Provenance.UNTRUSTED).executeBatch(listOf(call("agent_send_transaction")))
        assertEquals(1, rows.size)
    }

    @Test
    fun `a compiled flow's row names the flow and its version`() = runBlocking {
        engine().executeBatch(listOf(call("flow_signal_send")))

        val row = rows.single()
        assertEquals(3, row.routeRung)
        assertEquals("signal.send@3", row.flowRef)
    }

    @Test
    fun `a step row carries no tool input and no tool output`() = runBlocking {
        // Tool inputs routinely carry message bodies and addresses; this is a store the
        // user is invited to read and export.
        val withBody = ToolCall(
            id = "tc",
            name = "read_sms",
            input = JsonObject(mapOf("body" to JsonPrimitive("meet me at the usual place"))),
        )
        engine().executeBatch(listOf(withBody))

        val serialised = rows.single().toString()
        assertTrue(!serialised.contains("usual place"))
        assertTrue(!serialised.contains("ran:read_sms"))
    }

    @Test
    fun `no ledger means no rows and no change to what the model sees`() = runBlocking {
        val result = engine(withLedger = null).executeBatch(listOf(call("read_sms")))
        assertTrue(rows.isEmpty())
        assertEquals("ran:read_sms", result.results.single().content)
    }

    @Test
    fun `recording does not alter the tool result`() = runBlocking {
        val withLedger = engine().executeBatch(listOf(call("read_sms"))).results.single()
        rows.clear()
        val without = engine(withLedger = null).executeBatch(listOf(call("read_sms"))).results.single()

        assertEquals(without.content, withLedger.content)
        assertEquals(without.isError, withLedger.isError)
    }

    @Test
    fun `a step records how long it took`() = runBlocking {
        engine().executeBatch(listOf(call("read_sms")))
        assertTrue(rows.single().actions.single().durationMs >= 0)
    }

    @Test
    fun `cost is known only when the model is priced`() {
        assertEquals(0.0003, ledger.costOf(listOf("priced/model"), 100, 100)!!, 1e-12)
        // An ethOS Premium or local model the registry has never seen has an unknown cost,
        // and rendering that as free would be a small lie in the one screen whose job is
        // being trustworthy.
        assertNull(ledger.costOf(listOf("unknown/model"), 100, 100))
        assertNull(ledger.costOf(emptyList(), 100, 100))
    }

    @Test
    fun `a flow reference is resolved only for flow tools`() {
        assertEquals("signal.send@3", ledger.flowRef("flow_signal_send"))
        assertNull(ledger.flowRef("read_sms"))
    }
}
