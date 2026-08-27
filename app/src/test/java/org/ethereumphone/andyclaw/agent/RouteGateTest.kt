package org.ethereumphone.andyclaw.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import org.ethereumphone.andyclaw.skills.ToolRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The execution ladder as a mechanism rather than a prompt line.
 *
 * `agent-os-design.md` §3 says never skip a rung to reach a lower one. Everything below
 * runs through the real engine `AgentLoop` builds, because the point of the change is
 * that the *engine* refuses — a system-prompt preference is something the model can talk
 * itself out of, and `execute_code`'s tool bridge does not read the system prompt at all.
 */
class RouteGateTest {

    private lateinit var registry: NativeSkillRegistry
    private val executed = mutableListOf<String>()

    private fun tool(
        name: String,
        rung: Int? = null,
        targets: List<String> = emptyList(),
    ) = ToolDefinition(
        name = name,
        description = "test tool",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        },
        rung = rung,
        targetPackages = targets,
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

    @Before
    fun setup() {
        executed.clear()
        // The gate advises once per package per window, and that memory is object-level
        // because the engine is rebuilt per call. Each test starts from silence.
        ExecutionEngineFactory.clearRouteMemory()
        registry = NativeSkillRegistry()
        registry.register(
            skill(
                "agent_display",
                tool("agent_display_create", rung = ToolRoutes.RUNG_DISPLAY),
                tool("agent_display_click_node", rung = ToolRoutes.RUNG_DISPLAY),
            )
        )
    }

    private fun engine() = ExecutionEngineFactory.create(
        skillRegistry = registry,
        tier = Tier.OPEN,
        enabledSkillIds = registry.getAll().map { it.id }.toSet(),
        safetyLayer = null,
        agentCallbacks = Callbacks,
        budgetConfig = null,
        provenance = Provenance.USER,
    )

    private fun launch(pkg: String) = ToolCall(
        id = "tc",
        name = "agent_display_create",
        input = JsonObject(mapOf("package_name" to JsonPrimitive(pkg))),
    )

    @Test
    fun `the display is available when nothing else covers the app`() = runBlocking {
        val result = engine().executeBatch(listOf(launch("com.unknown.app")))
        assertFalse(result.results[0].isError)
        assertEquals(listOf("agent_display_create"), executed)
    }

    @Test
    fun `a compiled flow for the same app blocks the display`() = runBlocking {
        registry.register(
            skill("flows", tool("signal_send_to_thread", rung = 3, targets = listOf("org.thoughtcrime.securesms")))
        )
        val result = engine().executeBatch(listOf(launch("org.thoughtcrime.securesms")))

        assertTrue(result.results[0].isError)
        assertTrue(executed.isEmpty())
        // The message is the mechanism: it has to name the tool the model should use.
        assertTrue(result.results[0].content.contains("signal_send_to_thread"))
        assertTrue(result.results[0].content.contains("rung 3"))
    }

    @Test
    fun `a native route seeded in ToolRoutes blocks the display too`() = runBlocking {
        registry.register(skill("messenger", tool("send_xmtp_message")))
        val result = engine().executeBatch(listOf(launch("org.ethereumhpone.messenger")))

        assertTrue(result.results[0].isError)
        assertTrue(result.results[0].content.contains("send_xmtp_message"))
        assertTrue(result.results[0].content.contains("rung 0"))
    }

    @Test
    fun `a seeded route that is not registered does not block anything`() = runBlocking {
        // `send_xmtp_message` is on the ladder, but the skill is not loaded on this
        // device — blocking on a tool the model cannot call would be a dead end.
        val result = engine().executeBatch(listOf(launch("org.ethereumhpone.messenger")))
        assertFalse(result.results[0].isError)
        assertEquals(listOf("agent_display_create"), executed)
    }

    @Test
    fun `a stale flow stops standing in front of the display`() = runBlocking {
        // FlowSkill drops targetPackages when a flow goes stale, precisely so a route
        // nobody is sure about cannot block the fallback.
        registry.register(skill("flows", tool("signal_send_to_thread", rung = 3, targets = emptyList())))
        val result = engine().executeBatch(listOf(launch("org.thoughtcrime.securesms")))
        assertFalse(result.results[0].isError)
    }

    @Test
    fun `a flow for a different app is irrelevant`() = runBlocking {
        registry.register(skill("flows", tool("signal_send", rung = 3, targets = listOf("org.thoughtcrime.securesms"))))
        val result = engine().executeBatch(listOf(launch("com.android.settings")))
        assertFalse(result.results[0].isError)
    }

    @Test
    fun `mid-session display steps are never gated`() = runBlocking {
        registry.register(skill("flows", tool("signal_send", rung = 3, targets = listOf("org.thoughtcrime.securesms"))))
        // A tap names no package; only the door is routed, not every step through it.
        val tap = ToolCall(id = "tc", name = "agent_display_click_node", input = JsonObject(emptyMap()))
        val result = engine().executeBatch(listOf(tap))
        assertFalse(result.results[0].isError)
        assertEquals(listOf("agent_display_click_node"), executed)
    }

    @Test
    fun `a lower rung is never blocked by a higher one`() = runBlocking {
        registry.register(skill("messenger", tool("send_xmtp_message")))
        val call = ToolCall(
            id = "tc",
            name = "send_xmtp_message",
            input = JsonObject(mapOf("package_name" to JsonPrimitive("org.ethereumhpone.messenger"))),
        )
        val result = engine().executeBatch(listOf(call))
        assertFalse(result.results[0].isError)
        assertEquals(listOf("send_xmtp_message"), executed)
    }

    @Test
    fun `the best available rung is named first`() = runBlocking {
        registry.register(skill("messenger", tool("send_xmtp_message")))
        registry.register(
            skill("flows", tool("messenger_send_flow", rung = 3, targets = listOf("org.ethereumhpone.messenger")))
        )
        val content = engine().executeBatch(listOf(launch("org.ethereumhpone.messenger"))).results[0].content
        assertTrue(content.indexOf("send_xmtp_message") < content.indexOf("messenger_send_flow"))
    }

    @Test
    fun `the gate advises once and then gets out of the way`() = runBlocking {
        registry.register(skill("messenger", tool("send_xmtp_message")))

        val first = engine().executeBatch(listOf(launch("org.ethereumhpone.messenger")))
        assertTrue("the first attempt must name the better route", first.results[0].isError)

        // A better route existing does not mean it covers this task — the model has to
        // be able to say "it doesn't" and drive the UI anyway.
        val second = engine().executeBatch(listOf(launch("org.ethereumhpone.messenger")))
        assertFalse(second.results[0].isError)
        assertEquals(listOf("agent_display_create"), executed)
    }

    @Test
    fun `advice for one app does not silence the gate for another`() = runBlocking {
        registry.register(skill("messenger", tool("send_xmtp_message")))
        registry.register(skill("flows", tool("flow_gmail_archive", rung = 3, targets = listOf("com.google.android.gm"))))

        engine().executeBatch(listOf(launch("org.ethereumhpone.messenger")))
        val other = engine().executeBatch(listOf(launch("com.google.android.gm")))
        assertTrue(other.results[0].isError)
        assertTrue(other.results[0].content.contains("flow_gmail_archive"))
    }
}
