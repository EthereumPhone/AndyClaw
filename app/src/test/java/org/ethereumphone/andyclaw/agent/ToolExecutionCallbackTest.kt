package org.ethereumphone.andyclaw.agent

import kotlinx.coroutines.runBlocking
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * `onToolExecution` is what the launcher shows as "running <tool>" (`ILauncherCallback`), so it
 * fires once per call. The streaming executor used to announce every call itself on top of the
 * engine's `onToolStarted`, and each call reached the launcher twice (seen in agentbench traces).
 */
class ToolExecutionCallbackTest {

    @Before
    fun setup() {
        ExecutionEngineFactory.clearRouteMemory()
    }

    @Test
    fun `each tool call is announced exactly once`() = runBlocking {
        val registry = NativeSkillRegistry().apply {
            register(testSkill("tools", testTool("lookup"), testTool("fetch")) { _, _ -> SkillResult.Success("ok") })
        }
        val client = ScriptedLlmClient(listOf(
            listOf(toolUse("t1", "lookup")),
            listOf(toolUse("t2", "lookup"), toolUse("t3", "fetch")),
        ))
        val started = java.util.Collections.synchronizedList(mutableListOf<String>())
        val callbacks = object : RecordingCallbacks() {
            override fun onToolExecution(toolName: String) { started += toolName }
        }
        AgentLoop(client, registry, Tier.OPEN, enabledSkillIds = setOf("tools"), toolSearchService = null)
            .run("look things up", emptyList(), callbacks)

        assertEquals(listOf("fetch", "lookup", "lookup"), started.sorted())
    }
}
