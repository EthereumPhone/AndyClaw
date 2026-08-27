package org.ethereumphone.andyclaw.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.ExecutionEngine.ToolCall
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.skills.ToolEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end over the real engine the agent runs: does a batch assembled by
 * [ExecutionEngineFactory] actually stop an untrusted run, and does it stop it before
 * anything else in the pre-flight chain gets a say?
 */
class ProvenanceEngineTest {

    private lateinit var registry: NativeSkillRegistry
    private val executed = mutableListOf<String>()

    private fun tool(
        name: String,
        effect: ToolEffect? = null,
        requiresApproval: Boolean = false,
    ) = ToolDefinition(
        name = name,
        description = "test tool",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        },
        requiresApproval = requiresApproval,
        effect = effect,
    )

    private fun skill(id: String, vararg tools: ToolDefinition) = object : AndyClawSkill {
        override val id = id
        override val name = id
        override val baseManifest = SkillManifest(description = id, tools = tools.toList())
        override val privilegedManifest: SkillManifest? = null
        override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
            executed.add(tool)
            return SkillResult.Success("ran:$tool")
        }
    }

    /** Records approvals, and answers them the way a headless runner now must. */
    private class RecordingCallbacks(
        private val approve: Boolean,
    ) : AgentLoop.Callbacks {
        val approvalsAsked = mutableListOf<String>()
        val blocked = mutableListOf<String>()

        override fun onToken(text: String) {}
        override fun onToolExecution(toolName: String) {}
        override fun onToolResult(toolName: String, result: SkillResult, input: JsonObject?) {}
        override fun onSecurityBlock(toolName: String, reason: String) { blocked.add(toolName) }
        override suspend fun onApprovalNeeded(
            description: String,
            toolName: String?,
            toolInput: JsonObject?,
        ): Boolean {
            approvalsAsked.add(toolName ?: "?")
            return approve
        }
        override suspend fun onPermissionsNeeded(permissions: List<String>): Boolean = true
        override fun onComplete(fullText: String, tokenUsage: TokenUsageSnapshot?) {}
        override fun onError(error: Throwable) {}
    }

    @Before
    fun setup() {
        executed.clear()
        registry = NativeSkillRegistry()
        registry.register(
            skill(
                "wallet",
                tool("agent_send_transaction", effect = ToolEffect.IRREVERSIBLE),
                tool("read_agent_balance", effect = ToolEffect.READ),
                tool("send_native_token", effect = ToolEffect.SENSITIVE, requiresApproval = true),
            )
        )
        registry.register(skill("misc", tool("mystery_tool")))
    }

    private fun engine(
        provenance: Provenance,
        callbacks: AgentLoop.Callbacks,
        conversationId: String? = null,
        enforce: Boolean = true,
    ) = ExecutionEngineFactory.create(
        skillRegistry = registry,
        tier = Tier.OPEN,
        enabledSkillIds = registry.getAll().map { it.id }.toSet(),
        safetyLayer = null,
        agentCallbacks = callbacks,
        budgetConfig = null,
        provenance = provenance,
        triggerConversationId = conversationId,
        enforceProvenance = enforce,
    )

    private fun call(name: String) = ToolCall(id = "tc_$name", name = name, input = JsonObject(emptyMap()))

    @Test
    fun `an untrusted run cannot sign, even when the runner would auto-approve`() = runBlocking {
        // A headless runner that says yes to everything is exactly the shape the gate
        // has to survive — auto-approval is what HeartbeatAgentRunner used to do
        // unconditionally.
        val cbs = RecordingCallbacks(approve = false)
        val result = engine(Provenance.UNTRUSTED, cbs).executeBatch(listOf(call("agent_send_transaction")))

        assertTrue(result.results[0].isError)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `an untrusted run reaches the tool when the user approves`() = runBlocking {
        val cbs = RecordingCallbacks(approve = true)
        val result = engine(Provenance.UNTRUSTED, cbs).executeBatch(listOf(call("agent_send_transaction")))

        assertEquals(listOf("agent_send_transaction"), cbs.approvalsAsked)
        assertFalse(result.results[0].isError)
        assertEquals(listOf("agent_send_transaction"), executed)
    }

    @Test
    fun `a trusted run signs without a prompt, as before`() = runBlocking {
        val cbs = RecordingCallbacks(approve = false)
        val result = engine(Provenance.TRUSTED, cbs).executeBatch(listOf(call("agent_send_transaction")))

        assertTrue("no approval should have been raised", cbs.approvalsAsked.isEmpty())
        assertFalse(result.results[0].isError)
        assertEquals(listOf("agent_send_transaction"), executed)
    }

    @Test
    fun `a sensitive tool is blocked outright for an untrusted run`() = runBlocking {
        val cbs = RecordingCallbacks(approve = true)
        val result = engine(Provenance.UNTRUSTED, cbs).executeBatch(listOf(call("send_native_token")))

        assertTrue(result.results[0].isError)
        assertTrue("payment must not even offer an approval", cbs.approvalsAsked.isEmpty())
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `an unclassified tool fails closed through the whole engine`() = runBlocking {
        val cbs = RecordingCallbacks(approve = false)
        val result = engine(Provenance.UNTRUSTED, cbs).executeBatch(listOf(call("mystery_tool")))

        assertTrue(result.results[0].isError)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `reads stay open to an untrusted run`() = runBlocking {
        val cbs = RecordingCallbacks(approve = false)
        val result = engine(Provenance.UNTRUSTED, cbs).executeBatch(listOf(call("read_agent_balance")))

        assertFalse(result.results[0].isError)
        assertEquals(listOf("read_agent_balance"), executed)
    }

    @Test
    fun `a user run keeps the pre-existing single approval, not two`() = runBlocking {
        val cbs = RecordingCallbacks(approve = true)
        engine(Provenance.USER, cbs).executeBatch(listOf(call("send_native_token")))

        assertEquals(
            "the pre-existing requiresApproval prompt must not be doubled",
            listOf("send_native_token"),
            cbs.approvalsAsked,
        )
    }

    @Test
    fun `log-only mode executes but still lets everything through`() = runBlocking {
        val cbs = RecordingCallbacks(approve = false)
        val result = engine(Provenance.UNTRUSTED, cbs, enforce = false)
            .executeBatch(listOf(call("agent_send_transaction")))

        assertFalse(result.results[0].isError)
        assertEquals(listOf("agent_send_transaction"), executed)
    }
}
