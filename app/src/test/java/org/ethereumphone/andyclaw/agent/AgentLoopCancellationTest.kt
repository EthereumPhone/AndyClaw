package org.ethereumphone.andyclaw.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.MessageContent
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A cancelled turn — the user's STOP, the launcher's stopInference, a benchmark timeout — has to
 * reach the tool that is running, or an autopilot keeps driving the display for nobody.
 */
class AgentLoopCancellationTest {

    @Before
    fun setup() {
        ExecutionEngineFactory.clearRouteMemory()
    }

    @Test
    fun `cancelling the turn cancels a streamed tool that is still running`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val registry = NativeSkillRegistry().apply {
            register(testSkill("slow", testTool("slow_tool")) { _, _ ->
                started.complete(Unit)
                try {
                    delay(30_000)
                } catch (e: CancellationException) {
                    cancelled.complete(Unit)
                    throw e
                }
                SkillResult.Success("finished")
            })
        }
        val loop = AgentLoop(
            client = ScriptedLlmClient(listOf(listOf(toolUse("t1", "slow_tool")))),
            skillRegistry = registry,
            tier = Tier.OPEN,
            enabledSkillIds = setOf("slow"),
        )

        val run = launch(Dispatchers.Default) { loop.run("go", emptyList(), RecordingCallbacks()) }
        withTimeout(5_000) { started.await() }
        run.cancelAndJoin()

        // A tool on a scope of its own never sees this.
        withTimeout(5_000) { cancelled.await() }
    }

    @Test
    fun `a tool queued behind another in the same response comes back with its real result`() = runBlocking {
        val registry = NativeSkillRegistry().apply {
            register(testSkill("tools", testTool("first"), testTool("second")) { tool, _ ->
                delay(50)
                SkillResult.Success("ran:$tool")
            })
        }
        val client = ScriptedLlmClient(listOf(listOf(toolUse("a", "first"), toolUse("b", "second"))))
        val loop = AgentLoop(client, registry, Tier.OPEN, enabledSkillIds = setOf("tools"))

        loop.run("go", emptyList(), RecordingCallbacks())

        val results = client.requests[1].messages
            .flatMap { (it.content as? MessageContent.Blocks)?.blocks.orEmpty() }
            .filterIsInstance<ContentBlock.ToolResult>()
        assertEquals(listOf("a", "b"), results.map { it.toolUseId })
        assertTrue(results.none { it.isError })
        assertTrue(results[1].content.contains("ran:second"))
    }
}
