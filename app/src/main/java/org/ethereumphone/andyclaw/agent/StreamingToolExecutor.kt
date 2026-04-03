package org.ethereumphone.andyclaw.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.JsonObject
import org.ethereumphone.andyclaw.llm.ContentBlock

/**
 * Executes tools concurrently as they arrive from the streaming LLM response,
 * rather than waiting for the entire response before starting execution.
 *
 * Ported from Claude Code's `StreamingToolExecutor.ts`.
 *
 * Concurrency model:
 * - Read-only/concurrent-safe tools can run in parallel with each other
 * - Non-concurrent tools require exclusive access (nothing else runs alongside)
 * - Results are always returned in the original order they appeared in the stream
 *
 * @param executeToolCall Function that executes a single tool call and returns the result.
 * @param isConcurrencySafe Function that returns true if a tool is safe to run concurrently.
 * @param scope Coroutine scope for launching tool executions.
 */
class StreamingToolExecutor(
    private val executeToolCall: suspend (ContentBlock.ToolUseBlock) -> ContentBlock,
    private val isConcurrencySafe: (toolName: String) -> Boolean,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "StreamToolExec"

        /** Tools known to be read-only and safe for concurrent execution. */
        private val CONCURRENT_SAFE_TOOLS = setOf(
            "memory_search", "memory_list",
            "search_available_tools",
            "list_directory", "read_file", "file_info",
        )

        fun defaultIsConcurrencySafe(toolName: String): Boolean =
            toolName in CONCURRENT_SAFE_TOOLS
    }

    private sealed class ToolStatus {
        object Queued : ToolStatus()
        object Executing : ToolStatus()
        data class Completed(val result: ContentBlock) : ToolStatus()
    }

    private data class TrackedTool(
        val block: ContentBlock.ToolUseBlock,
        val concurrencySafe: Boolean,
        var status: ToolStatus = ToolStatus.Queued,
        var deferred: Deferred<ContentBlock>? = null,
    )

    private val tools = mutableListOf<TrackedTool>()

    /**
     * Called from the streaming callback's `onToolUse` as each tool_use block
     * completes in the stream. Immediately starts execution if concurrency allows.
     *
     * Thread-safe: called from the streaming IO thread.
     */
    @Synchronized
    fun addTool(block: ContentBlock.ToolUseBlock) {
        val safe = isConcurrencySafe(block.name)
        val tracked = TrackedTool(block, safe)
        tools.add(tracked)
        Log.d(TAG, "Tool queued: ${block.name} (id=${block.id}, concurrencySafe=$safe)")
        processQueue()
    }

    /**
     * Returns true if any tools have been queued or are executing.
     */
    val hasTools: Boolean get() = tools.isNotEmpty()

    /**
     * Awaits all queued and executing tools and returns their results
     * in the original stream order. Called after the stream completes.
     */
    suspend fun awaitAll(): List<ContentBlock> {
        // Ensure all queued tools are started
        synchronized(this) { processQueue() }

        // Await all deferreds
        tools.mapNotNull { it.deferred }.awaitAll()

        return tools.map { tracked ->
            when (val status = tracked.status) {
                is ToolStatus.Completed -> status.result
                else -> {
                    Log.w(TAG, "Tool ${tracked.block.name} not completed, returning error")
                    ContentBlock.ToolResult(
                        toolUseId = tracked.block.id,
                        content = "Tool execution did not complete",
                        isError = true,
                    )
                }
            }
        }
    }

    /** Clears all tracked tools for reuse. */
    @Synchronized
    fun reset() {
        tools.clear()
    }

    // ── Internal ─────────────────────────────────────────────────────

    /**
     * Processes the queue, starting execution for tools where concurrency allows.
     * Must be called under synchronized(this).
     */
    private fun processQueue() {
        for (tracked in tools) {
            if (tracked.status !is ToolStatus.Queued) continue
            if (canExecute(tracked.concurrencySafe)) {
                startExecution(tracked)
            } else if (!tracked.concurrencySafe) {
                // Non-concurrent tool can't start yet — stop processing to maintain order
                break
            }
        }
    }

    /**
     * Whether a new tool can start executing given current state.
     */
    private fun canExecute(isConcurrencySafe: Boolean): Boolean {
        val executing = tools.filter { it.status is ToolStatus.Executing }
        if (executing.isEmpty()) return true
        return isConcurrencySafe && executing.all { it.concurrencySafe }
    }

    private fun startExecution(tracked: TrackedTool) {
        tracked.status = ToolStatus.Executing
        tracked.deferred = scope.async {
            Log.d(TAG, "Executing: ${tracked.block.name} (id=${tracked.block.id})")
            val startMs = System.currentTimeMillis()
            try {
                val result = executeToolCall(tracked.block)
                val elapsedMs = System.currentTimeMillis() - startMs
                Log.d(TAG, "Completed: ${tracked.block.name} in ${elapsedMs}ms")
                synchronized(this@StreamingToolExecutor) {
                    tracked.status = ToolStatus.Completed(result)
                    processQueue() // Try to start next queued tools
                }
                result
            } catch (e: Exception) {
                val elapsedMs = System.currentTimeMillis() - startMs
                Log.w(TAG, "Failed: ${tracked.block.name} in ${elapsedMs}ms: ${e.message}")
                val errorResult = ContentBlock.ToolResult(
                    toolUseId = tracked.block.id,
                    content = "Tool error: ${e.message}",
                    isError = true,
                )
                synchronized(this@StreamingToolExecutor) {
                    tracked.status = ToolStatus.Completed(errorResult)
                    processQueue()
                }
                errorResult
            }
        }
    }
}
