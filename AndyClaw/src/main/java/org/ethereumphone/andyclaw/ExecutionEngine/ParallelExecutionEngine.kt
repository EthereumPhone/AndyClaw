package org.ethereumphone.andyclaw.ExecutionEngine

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Executes tool calls with parallel execution of independent tools.
 *
 * Three-phase pipeline:
 *   1. PRE-FLIGHT  (sequential) — safety, permissions, approval checks
 *   2. EXECUTION   (parallel)   — independent tools run concurrently
 *   3. POST-PROCESS (sequential) — sanitization, truncation, wrapping
 *
 * The engine is agnostic to LLM types, skill registries, and safety
 * implementations. All behavior is injected via [ToolExecutor],
 * [PreflightCheck], [PostProcessor], and [ExecutionCallbacks].
 */
class ParallelExecutionEngine(
    private val executor: ToolExecutor,
    private val preflightChecks: List<PreflightCheck> = emptyList(),
    private val postProcessors: List<PostProcessor> = emptyList(),
    private val callbacks: ExecutionCallbacks,
) {
    companion object {
        private const val TAG = "ExecEngine"
    }

    /**
     * Execute a batch of tool calls. Independent tools run in parallel.
     * Results are returned in the same order as the input [toolCalls].
     */
    suspend fun executeBatch(toolCalls: List<ToolCall>): ExecutionBatchResult {
        if (toolCalls.isEmpty()) {
            return ExecutionBatchResult(emptyList(), emptyMetrics())
        }

        val batchStartMs = System.currentTimeMillis()
        val perToolMs = mutableMapOf<String, Long>()

        Log.i(TAG, "Batch start: ${toolCalls.size} tool(s): ${toolCalls.joinToString { it.name }}")

        // ─── Phase 1: Pre-flight (sequential) ───
        val preflightResults = runPreflight(toolCalls)
        val readyTools = preflightResults.filter { it.value is PreflightOutcome.Ready }
        val blockedResults = preflightResults
            .filter { it.value !is PreflightOutcome.Ready }
            .map { (call, outcome) ->
                val reason = when (outcome) {
                    is PreflightOutcome.Blocked -> outcome.reason
                    is PreflightOutcome.Ready -> error("unreachable")
                }
                callbacks.onToolBlocked(call.name, reason)
                ToolCallResult(
                    toolCallId = call.id,
                    toolName = call.name,
                    content = reason,
                    isError = true,
                    phase = ToolCallResult.Phase.BLOCKED_PREFLIGHT,
                )
            }

        Log.i(TAG, "Pre-flight: ${readyTools.size} ready, ${blockedResults.size} blocked")

        // ─── Phase 2: Execution (parallel) ───
        val executionResults = runParallelExecution(
            readyTools.keys.toList(),
            perToolMs,
        )

        // ─── Phase 3: Post-processing (sequential) ───
        val processedResults = runPostProcessing(executionResults)

        // ─── Reassemble in original order ───
        val resultMap = mutableMapOf<String, ToolCallResult>()
        for (r in blockedResults) resultMap[r.toolCallId] = r
        for (r in processedResults) resultMap[r.toolCallId] = r

        val orderedResults = toolCalls.map { call ->
            resultMap[call.id] ?: ToolCallResult(
                toolCallId = call.id,
                toolName = call.name,
                content = "Internal error: no result produced for tool call",
                isError = true,
            )
        }

        val batchDurationMs = System.currentTimeMillis() - batchStartMs
        val metrics = ExecutionMetrics(
            totalTools = toolCalls.size,
            executedCount = executionResults.size,
            blockedCount = blockedResults.size,
            errorCount = orderedResults.count { it.isError },
            parallelBatches = if (readyTools.isNotEmpty()) 1 else 0,
            totalDurationMs = batchDurationMs,
            maxToolDurationMs = perToolMs.values.maxOrNull() ?: 0,
            perToolMs = perToolMs,
        )

        Log.i(TAG, "Batch complete: ${metrics.totalDurationMs}ms total, " +
            "${metrics.executedCount} executed (${metrics.maxToolDurationMs}ms max), " +
            "${metrics.blockedCount} blocked, ${metrics.errorCount} errors")

        return ExecutionBatchResult(orderedResults, metrics)
    }

    // ═══════════════════════════════════════════
    // Phase 1: Pre-flight
    // ═══════════════════════════════════════════

    private suspend fun runPreflight(
        toolCalls: List<ToolCall>,
    ): Map<ToolCall, PreflightOutcome> {
        val results = linkedMapOf<ToolCall, PreflightOutcome>()

        for (call in toolCalls) {
            val outcome = runPreflightForTool(call)
            results[call] = outcome
        }

        return results
    }

    private suspend fun runPreflightForTool(call: ToolCall): PreflightOutcome {
        for (check in preflightChecks) {
            when (val verdict = check.check(call)) {
                is PreflightVerdict.Pass -> continue

                is PreflightVerdict.Block -> {
                    Log.d(TAG, "Pre-flight BLOCK [${call.name}]: ${verdict.reason}")
                    return PreflightOutcome.Blocked(verdict.reason)
                }

                is PreflightVerdict.NeedsApproval -> {
                    val approved = callbacks.onApprovalNeeded(
                        description = verdict.description,
                        toolName = call.name,
                        toolInput = call.input,
                    )
                    if (!approved) {
                        Log.d(TAG, "Pre-flight DENIED approval [${call.name}]")
                        return PreflightOutcome.Blocked("User denied approval.")
                    }
                    Log.d(TAG, "Pre-flight APPROVED [${call.name}]")
                }

                is PreflightVerdict.NeedsPermissions -> {
                    val granted = callbacks.onPermissionsNeeded(verdict.permissions)
                    if (!granted) {
                        Log.d(TAG, "Pre-flight DENIED permissions [${call.name}]")
                        return PreflightOutcome.Blocked(
                            "Required Android permissions were not granted: " +
                                "${verdict.permissions.joinToString()}. " +
                                "Ask the user to grant them in device settings."
                        )
                    }
                    Log.d(TAG, "Pre-flight permissions GRANTED [${call.name}]")
                }
            }
        }

        return PreflightOutcome.Ready
    }

    // ═══════════════════════════════════════════
    // Phase 2: Parallel execution
    // ═══════════════════════════════════════════

    private suspend fun runParallelExecution(
        readyTools: List<ToolCall>,
        perToolMs: MutableMap<String, Long>,
    ): List<ExecutedTool> = coroutineScope {
        readyTools.map { call ->
            async {
                callbacks.onToolStarted(call.name)
                val startMs = System.currentTimeMillis()
                val result = try {
                    executor.execute(call.name, call.input)
                } catch (e: Exception) {
                    Log.e(TAG, "Tool execution threw [${call.name}]: ${e.message}", e)
                    ToolExecResult.Error("Tool execution failed: ${e.message}")
                }
                val durationMs = System.currentTimeMillis() - startMs
                perToolMs[call.name] = durationMs
                Log.d(TAG, "Executed [${call.name}] in ${durationMs}ms -> ${result::class.simpleName}")

                // Handle RequiresApproval: tool ran once, now asks for confirmation,
                // then runs again if approved.
                val finalResult = if (result is ToolExecResult.RequiresApproval) {
                    handleRequiresApproval(call, result)
                } else {
                    ExecutedTool(call, result, ToolCallResult.Phase.EXECUTED)
                }

                finalResult
            }
        }.awaitAll()
    }

    private suspend fun handleRequiresApproval(
        call: ToolCall,
        approval: ToolExecResult.RequiresApproval,
    ): ExecutedTool {
        val approved = callbacks.onApprovalNeeded(
            description = approval.description,
            toolName = call.name,
            toolInput = call.input,
        )
        if (!approved) {
            return ExecutedTool(
                call,
                ToolExecResult.Error("User denied approval."),
                ToolCallResult.Phase.BLOCKED_PREFLIGHT,
            )
        }

        // Re-execute after approval
        val retryResult = try {
            executor.execute(call.name, call.input)
        } catch (e: Exception) {
            ToolExecResult.Error("Tool re-execution failed: ${e.message}")
        }

        return ExecutedTool(call, retryResult, ToolCallResult.Phase.EXECUTED_AFTER_APPROVAL)
    }

    // ═══════════════════════════════════════════
    // Phase 3: Post-processing
    // ═══════════════════════════════════════════

    private fun runPostProcessing(
        executedTools: List<ExecutedTool>,
    ): List<ToolCallResult> {
        return executedTools.map { executed ->
            val (call, result, phase) = executed

            // If no post-processors, convert directly
            if (postProcessors.isEmpty()) {
                return@map resultToToolCallResult(call, result, phase)
            }

            // Run through the post-processor chain. Each processor after the first
            // sees the PREVIOUS processor's output, not the raw executor result —
            // feeding `result` in every time meant the last processor silently
            // discarded every earlier one's work (truncation threw away
            // sanitization). Warnings accumulate across the whole chain.
            var chained: PostProcessedResult? = null
            val warnings = mutableListOf<String>()
            for (processor in postProcessors) {
                val input = chained?.asExecResult() ?: result
                chained = processor.process(call, input)
                warnings.addAll(chained.warnings)
                if (chained.blocked) break
            }
            var processed = chained ?: defaultPostProcess(call, result)
            if (!processed.blocked) {
                processed = processed.copy(warnings = warnings.toList())
            }

            val finalResult = if (processed.blocked) {
                callbacks.onToolBlocked(call.name, processed.blockedReason ?: "Blocked by post-processor")
                ToolCallResult(
                    toolCallId = call.id,
                    toolName = call.name,
                    content = processed.blockedReason ?: processed.content,
                    isError = true,
                    phase = phase,
                )
            } else {
                for (w in processed.warnings) {
                    Log.w(TAG, "Post-process warning [${call.name}]: $w")
                }
                ToolCallResult(
                    toolCallId = call.id,
                    toolName = call.name,
                    content = processed.content,
                    isError = processed.isError,
                    imageData = processed.imageData,
                    phase = phase,
                )
            }

            callbacks.onToolCompleted(call.name, finalResult)
            finalResult
        }
    }

    // ═══════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════

    /**
     * Re-wrap a [PostProcessedResult] as a [ToolExecResult] so it can be handed to
     * the next [PostProcessor] in the chain. An image survives the hop so a
     * screenshot is not silently dropped by a text-only processor upstream of one
     * that expects it.
     */
    private fun PostProcessedResult.asExecResult(): ToolExecResult {
        val img = imageData
        return when {
            isError -> ToolExecResult.Error(content)
            img != null -> ToolExecResult.ImageSuccess(content, img.base64, img.mediaType)
            else -> ToolExecResult.Success(content)
        }
    }

    private fun defaultPostProcess(call: ToolCall, result: ToolExecResult): PostProcessedResult {
        return when (result) {
            is ToolExecResult.Success -> PostProcessedResult(
                content = result.data,
                isError = false,
            )
            is ToolExecResult.ImageSuccess -> PostProcessedResult(
                content = result.text,
                isError = false,
                imageData = ToolCallResult.ImageData(result.base64, result.mediaType),
            )
            is ToolExecResult.Error -> PostProcessedResult(
                content = result.message,
                isError = true,
            )
            is ToolExecResult.RequiresApproval -> PostProcessedResult(
                content = result.description,
                isError = true,
            )
        }
    }

    private fun resultToToolCallResult(
        call: ToolCall,
        result: ToolExecResult,
        phase: ToolCallResult.Phase,
    ): ToolCallResult {
        val processed = defaultPostProcess(call, result)
        val tcr = ToolCallResult(
            toolCallId = call.id,
            toolName = call.name,
            content = processed.content,
            isError = processed.isError,
            imageData = processed.imageData,
            phase = phase,
        )
        callbacks.onToolCompleted(call.name, tcr)
        return tcr
    }

    private fun emptyMetrics() = ExecutionMetrics(
        totalTools = 0,
        executedCount = 0,
        blockedCount = 0,
        errorCount = 0,
        parallelBatches = 0,
        totalDurationMs = 0,
        maxToolDurationMs = 0,
        perToolMs = emptyMap(),
    )

    // ═══════════════════════════════════════════
    // Internal types
    // ═══════════════════════════════════════════

    private sealed class PreflightOutcome {
        data object Ready : PreflightOutcome()
        data class Blocked(val reason: String) : PreflightOutcome()
    }

    private data class ExecutedTool(
        val call: ToolCall,
        val result: ToolExecResult,
        val phase: ToolCallResult.Phase,
    )
}
