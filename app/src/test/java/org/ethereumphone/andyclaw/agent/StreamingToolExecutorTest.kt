package org.ethereumphone.andyclaw.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingToolExecutorTest {

    private fun executor(
        scope: CoroutineScope,
        run: suspend (ContentBlock.ToolUseBlock) -> ContentBlock,
    ) = StreamingToolExecutor(executeToolCall = run, isConcurrencySafe = { false }, scope = scope)

    @Test
    fun `tools queued behind a non-concurrent one are awaited, not reported unfinished`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ex = executor(scope) { block ->
                delay(50)
                ContentBlock.ToolResult(block.id, "done ${block.name}")
            }
            ex.addTool(toolUse("a", "tap"))
            ex.addTool(toolUse("b", "type"))
            ex.addTool(toolUse("c", "back"))

            val results = ex.awaitAll().map { it as ContentBlock.ToolResult }

            assertEquals(listOf("done tap", "done type", "done back"), results.map { it.content })
            assertTrue(results.none { it.isError })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a tool's own timeout is that tool's failure and the next one still runs`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ex = executor(scope) { block ->
                if (block.name == "slow") withTimeout(10) { delay(1_000) }
                ContentBlock.ToolResult(block.id, "done ${block.name}")
            }
            ex.addTool(toolUse("a", "slow"))
            ex.addTool(toolUse("b", "next"))

            val results = ex.awaitAll().map { it as ContentBlock.ToolResult }

            assertTrue(results[0].isError)
            assertEquals("done next", results[1].content)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a cancelled run ends its running tool and awaitAll returns instead of hanging`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val ex = executor(scope) { block ->
            started.complete(Unit)
            delay(30_000)
            ContentBlock.ToolResult(block.id, "late")
        }
        ex.addTool(toolUse("a", "agent_display_autopilot"))
        ex.addTool(toolUse("b", "agent_display_tap"))
        started.await()

        scope.cancel()
        val results = withTimeout(5_000) { ex.awaitAll() }.map { it as ContentBlock.ToolResult }

        assertEquals(2, results.size)
        assertTrue("nothing reports success for a tool that never finished", results.all { it.isError })
    }
}
