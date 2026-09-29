package org.ethereumphone.andyclaw.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.ethereumphone.andyclaw.ExecutionEngine.ParallelExecutionEngine
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.MessageContent
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * IPC-01's model-facing half: a host that queued a refused call tells the model so, through
 * AgentLoop.Callbacks.notApprovedMessage, the factory's bridge and the engine.
 */
class NotApprovedMessageTest {

    @Before
    fun setup() {
        ExecutionEngineFactory.clearRouteMemory()
    }

    private fun sensitive(name: String) = testTool(name, ToolEffect.SENSITIVE)

    private fun toolResultsSentBack(client: ScriptedLlmClient) = client.requests[1].messages
        .flatMap { (it.content as? MessageContent.Blocks)?.blocks.orEmpty() }
        .filterIsInstance<ContentBlock.ToolResult>()

    @Test
    fun `the model hears the host's words for a queued call, and the tool never runs`() = runBlocking {
        var ran = false
        val registry = NativeSkillRegistry().apply {
            register(testSkill("s", sensitive("write_secure_setting")) { _, _ -> ran = true; SkillResult.Success("done") })
        }
        val client = ScriptedLlmClient(listOf(listOf(toolUse("t1", "write_secure_setting"))))
        val asked = mutableListOf<String?>()
        val callbacks = object : RecordingCallbacks() {
            override suspend fun onApprovalNeeded(description: String, toolName: String?, toolInput: JsonObject?) = false
            override fun notApprovedMessage(toolName: String?, toolInput: JsonObject?): String {
                asked += toolName
                return "Waiting for the user's approval in this conversation."
            }
        }
        AgentLoop(client, registry, Tier.OPEN, enabledSkillIds = setOf("s")).run("set it", emptyList(), callbacks)

        assertFalse(ran)
        assertEquals(listOf<String?>("write_secure_setting"), asked)
        val result = toolResultsSentBack(client).single()
        assertTrue(result.isError)
        assertEquals("Waiting for the user's approval in this conversation.", result.content)
        assertEquals("done", callbacks.completed)
    }

    @Test
    fun `a host that says nothing keeps the neutral refusal`() = runBlocking {
        val registry = NativeSkillRegistry().apply {
            register(testSkill("s", sensitive("write_secure_setting")) { _, _ -> SkillResult.Success("done") })
        }
        val client = ScriptedLlmClient(listOf(listOf(toolUse("t1", "write_secure_setting"))))
        val callbacks = object : RecordingCallbacks() {
            override suspend fun onApprovalNeeded(description: String, toolName: String?, toolInput: JsonObject?) = false
        }
        AgentLoop(client, registry, Tier.OPEN, enabledSkillIds = setOf("s")).run("set it", emptyList(), callbacks)

        assertEquals(ParallelExecutionEngine.NOT_APPROVED, toolResultsSentBack(client).single().content)
    }
}
