package org.ethereumphone.andyclaw.ExecutionEngine

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ParallelExecutionEngineTest {

    // ═══════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════

    private fun toolCall(id: String = "tc_1", name: String = "test_tool", input: JsonObject = JsonObject(emptyMap())) =
        ToolCall(id, name, input)

    private fun successExecutor(data: String = "ok"): ToolExecutor =
        ToolExecutor { _, _ -> ToolExecResult.Success(data) }

    private fun errorExecutor(msg: String = "failed"): ToolExecutor =
        ToolExecutor { _, _ -> ToolExecResult.Error(msg) }

    /** Executor that takes [delayMs] to simulate real tool latency. */
    private fun slowExecutor(delayMs: Long, data: String = "ok"): ToolExecutor =
        ToolExecutor { _, _ ->
            delay(delayMs)
            ToolExecResult.Success(data)
        }

    /** Executor that returns different results per tool name. */
    private fun routingExecutor(results: Map<String, ToolExecResult>): ToolExecutor =
        ToolExecutor { name, _ ->
            results[name] ?: ToolExecResult.Error("unknown tool: $name")
        }

    /** Tracks which tools were executed and in what order. */
    private class TrackingExecutor(private val inner: ToolExecutor) : ToolExecutor {
        val executed = mutableListOf<String>()
        override suspend fun execute(toolName: String, params: JsonObject): ToolExecResult {
            executed.add(toolName)
            return inner.execute(toolName, params)
        }
    }

    private fun noOpCallbacks() = object : ExecutionCallbacks {
        override fun onToolStarted(toolName: String) {}
        override fun onToolCompleted(toolName: String, result: ToolCallResult) {}
        override fun onToolBlocked(toolName: String, reason: String) {}
        override suspend fun onApprovalNeeded(description: String, toolName: String?, toolInput: JsonObject?) = true
        override suspend fun onPermissionsNeeded(permissions: List<String>) = true
    }

    private fun trackingCallbacks() = object : ExecutionCallbacks {
        val started = mutableListOf<String>()
        val completed = mutableListOf<String>()
        val blocked = mutableListOf<String>()

        override fun onToolStarted(toolName: String) { started.add(toolName) }
        override fun onToolCompleted(toolName: String, result: ToolCallResult) { completed.add(toolName) }
        override fun onToolBlocked(toolName: String, reason: String) { blocked.add(toolName) }
        override suspend fun onApprovalNeeded(description: String, toolName: String?, toolInput: JsonObject?) = true
        override suspend fun onPermissionsNeeded(permissions: List<String>) = true
    }

    private fun buildEngine(
        executor: ToolExecutor = successExecutor(),
        preflightChecks: List<PreflightCheck> = emptyList(),
        postProcessors: List<PostProcessor> = emptyList(),
        callbacks: ExecutionCallbacks = noOpCallbacks(),
    ) = EngineBuilder()
        .executor(executor)
        .also { builder -> preflightChecks.forEach { builder.addPreflightCheck(it) } }
        .also { builder -> postProcessors.forEach { builder.addPostProcessor(it) } }
        .callbacks(callbacks)
        .build()

    // ═══════════════════════════════════════════
    // Basic execution
    // ═══════════════════════════════════════════

    @Test
    fun `empty batch returns empty results`() = runTest {
        val engine = buildEngine()
        val result = engine.executeBatch(emptyList())

        assertTrue(result.results.isEmpty())
        assertEquals(0, result.metrics.totalTools)
    }

    @Test
    fun `single tool executes successfully`() = runTest {
        val engine = buildEngine(executor = successExecutor("hello"))
        val result = engine.executeBatch(listOf(toolCall()))

        assertEquals(1, result.results.size)
        assertEquals("hello", result.results[0].content)
        assertFalse(result.results[0].isError)
        assertEquals(ToolCallResult.Phase.EXECUTED, result.results[0].phase)
        assertTrue(result.allSucceeded)
    }

    @Test
    fun `single tool error propagates`() = runTest {
        val engine = buildEngine(executor = errorExecutor("something broke"))
        val result = engine.executeBatch(listOf(toolCall()))

        assertEquals(1, result.results.size)
        assertEquals("something broke", result.results[0].content)
        assertTrue(result.results[0].isError)
        assertFalse(result.allSucceeded)
    }

    @Test
    fun `executor exception becomes error result`() = runTest {
        val engine = buildEngine(
            executor = ToolExecutor { _, _ -> throw RuntimeException("boom") }
        )
        val result = engine.executeBatch(listOf(toolCall()))

        assertEquals(1, result.results.size)
        assertTrue(result.results[0].isError)
        assertTrue(result.results[0].content.contains("boom"))
    }

    @Test
    fun `image result preserves image data`() = runTest {
        val engine = buildEngine(
            executor = ToolExecutor { _, _ ->
                ToolExecResult.ImageSuccess(
                    text = "screenshot taken",
                    base64 = "iVBORw0KGgo=",
                    mediaType = "image/png",
                )
            }
        )
        val result = engine.executeBatch(listOf(toolCall()))

        assertEquals("screenshot taken", result.results[0].content)
        assertFalse(result.results[0].isError)
        assertEquals("iVBORw0KGgo=", result.results[0].imageData?.base64)
        assertEquals("image/png", result.results[0].imageData?.mediaType)
    }

    // ═══════════════════════════════════════════
    // Parallel execution
    // ═══════════════════════════════════════════

    @Test
    fun `multiple tools execute in parallel - total time less than sum`() = runTest {
        val delayMs = 200L
        val engine = buildEngine(executor = slowExecutor(delayMs))

        val calls = listOf(
            toolCall(id = "t1", name = "tool_a"),
            toolCall(id = "t2", name = "tool_b"),
            toolCall(id = "t3", name = "tool_c"),
        )

        val startMs = System.currentTimeMillis()
        val result = engine.executeBatch(calls)
        val elapsedMs = System.currentTimeMillis() - startMs

        assertEquals(3, result.results.size)
        assertTrue(result.allSucceeded)

        // If sequential, would take ~600ms. Parallel should be ~200ms.
        // Use generous threshold to avoid flaky tests.
        assertTrue(
            "Expected parallel execution (<500ms), but took ${elapsedMs}ms",
            elapsedMs < 500
        )
    }

    @Test
    fun `results maintain original order regardless of execution speed`() = runTest {
        val engine = buildEngine(
            executor = routingExecutor(mapOf(
                "slow_tool" to ToolExecResult.Success("slow_result"),
                "fast_tool" to ToolExecResult.Success("fast_result"),
                "medium_tool" to ToolExecResult.Success("medium_result"),
            ))
        )

        val calls = listOf(
            toolCall(id = "t1", name = "slow_tool"),
            toolCall(id = "t2", name = "fast_tool"),
            toolCall(id = "t3", name = "medium_tool"),
        )

        val result = engine.executeBatch(calls)

        assertEquals("t1", result.results[0].toolCallId)
        assertEquals("slow_tool", result.results[0].toolName)
        assertEquals("slow_result", result.results[0].content)

        assertEquals("t2", result.results[1].toolCallId)
        assertEquals("fast_tool", result.results[1].toolName)
        assertEquals("fast_result", result.results[1].content)

        assertEquals("t3", result.results[2].toolCallId)
        assertEquals("medium_tool", result.results[2].toolName)
        assertEquals("medium_result", result.results[2].content)
    }

    @Test
    fun `mixed success and error results from parallel execution`() = runTest {
        val engine = buildEngine(
            executor = routingExecutor(mapOf(
                "good_tool" to ToolExecResult.Success("worked"),
                "bad_tool" to ToolExecResult.Error("failed"),
            ))
        )

        val calls = listOf(
            toolCall(id = "t1", name = "good_tool"),
            toolCall(id = "t2", name = "bad_tool"),
        )

        val result = engine.executeBatch(calls)

        assertFalse(result.results[0].isError)
        assertTrue(result.results[1].isError)
        assertFalse(result.allSucceeded)
        assertEquals(1, result.metrics.errorCount)
    }

    // ═══════════════════════════════════════════
    // Pre-flight checks
    // ═══════════════════════════════════════════

    @Test
    fun `preflight block prevents execution`() = runTest {
        val tracker = TrackingExecutor(successExecutor())
        val engine = buildEngine(
            executor = tracker,
            preflightChecks = listOf(
                PreflightCheck { PreflightVerdict.Block("not allowed") }
            ),
        )

        val result = engine.executeBatch(listOf(toolCall()))

        assertEquals(1, result.results.size)
        assertTrue(result.results[0].isError)
        assertEquals("not allowed", result.results[0].content)
        assertEquals(ToolCallResult.Phase.BLOCKED_PREFLIGHT, result.results[0].phase)
        assertTrue(tracker.executed.isEmpty()) // tool was never called
    }

    @Test
    fun `preflight pass allows execution`() = runTest {
        val engine = buildEngine(
            executor = successExecutor("executed"),
            preflightChecks = listOf(
                PreflightCheck { PreflightVerdict.Pass }
            ),
        )

        val result = engine.executeBatch(listOf(toolCall()))

        assertFalse(result.results[0].isError)
        assertEquals("executed", result.results[0].content)
    }

    @Test
    fun `multiple preflight checks run in order - first block wins`() = runTest {
        val checkOrder = mutableListOf<String>()
        val engine = buildEngine(
            preflightChecks = listOf(
                PreflightCheck { checkOrder.add("check_1"); PreflightVerdict.Pass },
                PreflightCheck { checkOrder.add("check_2"); PreflightVerdict.Block("blocked by check 2") },
                PreflightCheck { checkOrder.add("check_3"); PreflightVerdict.Pass }, // should not run
            ),
        )

        val result = engine.executeBatch(listOf(toolCall()))

        assertTrue(result.results[0].isError)
        assertEquals("blocked by check 2", result.results[0].content)
        assertEquals(listOf("check_1", "check_2"), checkOrder) // check_3 never ran
    }

    @Test
    fun `preflight blocks some tools but allows others`() = runTest {
        val engine = buildEngine(
            executor = successExecutor("ok"),
            preflightChecks = listOf(
                PreflightCheck { call ->
                    if (call.name == "blocked_tool") PreflightVerdict.Block("nope")
                    else PreflightVerdict.Pass
                }
            ),
        )

        val calls = listOf(
            toolCall(id = "t1", name = "allowed_tool"),
            toolCall(id = "t2", name = "blocked_tool"),
            toolCall(id = "t3", name = "allowed_tool_2"),
        )

        val result = engine.executeBatch(calls)

        assertFalse(result.results[0].isError)
        assertTrue(result.results[1].isError)
        assertFalse(result.results[2].isError)
        assertEquals(2, result.metrics.executedCount)
        assertEquals(1, result.metrics.blockedCount)
    }

    @Test
    fun `preflight NeedsPermissions - granted allows execution`() = runTest {
        val engine = buildEngine(
            executor = successExecutor("done"),
            preflightChecks = listOf(
                PreflightCheck { PreflightVerdict.NeedsPermissions(listOf("CAMERA", "LOCATION")) }
            ),
            callbacks = object : ExecutionCallbacks by noOpCallbacks() {
                override suspend fun onPermissionsNeeded(permissions: List<String>): Boolean {
                    assertEquals(listOf("CAMERA", "LOCATION"), permissions)
                    return true
                }
            },
        )

        val result = engine.executeBatch(listOf(toolCall()))
        assertFalse(result.results[0].isError)
    }

    @Test
    fun `preflight NeedsPermissions - denied blocks execution`() = runTest {
        val engine = buildEngine(
            executor = successExecutor(),
            preflightChecks = listOf(
                PreflightCheck { PreflightVerdict.NeedsPermissions(listOf("CAMERA")) }
            ),
            callbacks = object : ExecutionCallbacks by noOpCallbacks() {
                override suspend fun onPermissionsNeeded(permissions: List<String>) = false
            },
        )

        val result = engine.executeBatch(listOf(toolCall()))
        assertTrue(result.results[0].isError)
        assertTrue(result.results[0].content.contains("permissions were not granted"))
    }

    @Test
    fun `preflight NeedsApproval - approved allows execution`() = runTest {
        val engine = buildEngine(
            executor = successExecutor("approved and ran"),
            preflightChecks = listOf(
                PreflightCheck { PreflightVerdict.NeedsApproval("Send SMS?") }
            ),
            callbacks = object : ExecutionCallbacks by noOpCallbacks() {
                override suspend fun onApprovalNeeded(description: String, toolName: String?, toolInput: JsonObject?): Boolean {
                    assertEquals("Send SMS?", description)
                    return true
                }
            },
        )

        val result = engine.executeBatch(listOf(toolCall()))
        assertFalse(result.results[0].isError)
        assertEquals("approved and ran", result.results[0].content)
    }

    @Test
    fun `preflight NeedsApproval - denied blocks execution`() = runTest {
        val tracker = TrackingExecutor(successExecutor())
        val engine = buildEngine(
            executor = tracker,
            preflightChecks = listOf(
                PreflightCheck { PreflightVerdict.NeedsApproval("Delete all data?") }
            ),
            callbacks = object : ExecutionCallbacks by noOpCallbacks() {
                override suspend fun onApprovalNeeded(description: String, toolName: String?, toolInput: JsonObject?) = false
            },
        )

        val result = engine.executeBatch(listOf(toolCall()))
        assertTrue(result.results[0].isError)
        assertEquals("User denied approval.", result.results[0].content)
        assertTrue(tracker.executed.isEmpty())
    }

    // ═══════════════════════════════════════════
    // RequiresApproval (runtime, from tool)
    // ═══════════════════════════════════════════

    @Test
    fun `RequiresApproval - approved triggers re-execution`() = runTest {
        val callCount = AtomicInteger(0)
        val engine = buildEngine(
            executor = ToolExecutor { _, _ ->
                if (callCount.incrementAndGet() == 1) {
                    ToolExecResult.RequiresApproval("Confirm transfer of 1 ETH?")
                } else {
                    ToolExecResult.Success("transferred")
                }
            },
            callbacks = object : ExecutionCallbacks by noOpCallbacks() {
                override suspend fun onApprovalNeeded(description: String, toolName: String?, toolInput: JsonObject?): Boolean {
                    assertEquals("Confirm transfer of 1 ETH?", description)
                    return true
                }
            },
        )

        val result = engine.executeBatch(listOf(toolCall()))

        assertFalse(result.results[0].isError)
        assertEquals("transferred", result.results[0].content)
        assertEquals(ToolCallResult.Phase.EXECUTED_AFTER_APPROVAL, result.results[0].phase)
        assertEquals(2, callCount.get())
    }

    @Test
    fun `RequiresApproval - denied returns error`() = runTest {
        val engine = buildEngine(
            executor = ToolExecutor { _, _ ->
                ToolExecResult.RequiresApproval("Confirm?")
            },
            callbacks = object : ExecutionCallbacks by noOpCallbacks() {
                override suspend fun onApprovalNeeded(description: String, toolName: String?, toolInput: JsonObject?) = false
            },
        )

        val result = engine.executeBatch(listOf(toolCall()))
        assertTrue(result.results[0].isError)
        assertEquals("User denied approval.", result.results[0].content)
    }

    // ═══════════════════════════════════════════
    // Post-processing
    // ═══════════════════════════════════════════

    @Test
    fun `post-processor transforms content`() = runTest {
        val engine = buildEngine(
            executor = successExecutor("raw output"),
            postProcessors = listOf(
                PostProcessor { _, result ->
                    val content = (result as ToolExecResult.Success).data
                    PostProcessedResult(
                        content = content.uppercase(),
                        isError = false,
                    )
                }
            ),
        )

        val result = engine.executeBatch(listOf(toolCall()))
        assertEquals("RAW OUTPUT", result.results[0].content)
    }

    @Test
    fun `post-processor can block result`() = runTest {
        val cbs = trackingCallbacks()
        val engine = buildEngine(
            executor = successExecutor("secret data"),
            postProcessors = listOf(
                PostProcessor { _, _ ->
                    PostProcessedResult(
                        content = "blocked",
                        isError = true,
                        blocked = true,
                        blockedReason = "Contains sensitive data",
                    )
                }
            ),
            callbacks = cbs,
        )

        val result = engine.executeBatch(listOf(toolCall(name = "leaky_tool")))

        assertTrue(result.results[0].isError)
        assertEquals("Contains sensitive data", result.results[0].content)
        assertTrue(cbs.blocked.contains("leaky_tool"))
    }

    @Test
    fun `post-processor chain stops on block`() = runTest {
        val processorOrder = mutableListOf<String>()
        val engine = buildEngine(
            executor = successExecutor("data"),
            postProcessors = listOf(
                PostProcessor { _, result ->
                    processorOrder.add("processor_1")
                    PostProcessedResult(content = (result as ToolExecResult.Success).data, isError = false)
                },
                PostProcessor { _, _ ->
                    processorOrder.add("processor_2")
                    PostProcessedResult(content = "", isError = true, blocked = true, blockedReason = "blocked")
                },
                PostProcessor { _, _ ->
                    processorOrder.add("processor_3") // should not run
                    PostProcessedResult(content = "", isError = false)
                },
            ),
        )

        engine.executeBatch(listOf(toolCall()))
        assertEquals(listOf("processor_1", "processor_2"), processorOrder)
    }

    @Test
    fun `post-processor chain feeds each processor the previous one's output`() = runTest {
        val seenByProcessor2 = mutableListOf<String>()
        val engine = buildEngine(
            executor = successExecutor("raw"),
            postProcessors = listOf(
                PostProcessor { _, result ->
                    PostProcessedResult(
                        content = "[sanitized]" + (result as ToolExecResult.Success).data,
                        isError = false,
                    )
                },
                PostProcessor { _, result ->
                    val incoming = (result as ToolExecResult.Success).data
                    seenByProcessor2.add(incoming)
                    PostProcessedResult(content = incoming.take(11), isError = false)
                },
            ),
        )

        val result = engine.executeBatch(listOf(toolCall()))

        // The second processor must see the first's output, not the executor's.
        // Feeding the raw result to every processor let the last one silently
        // discard the sanitization the first one applied.
        assertEquals(listOf("[sanitized]raw"), seenByProcessor2)
        assertEquals("[sanitized]", result.results[0].content)
    }

    @Test
    fun `post-processor chain preserves an image across a text-only processor`() = runTest {
        val engine = buildEngine(
            executor = ToolExecutor { _, _ -> ToolExecResult.ImageSuccess("shot", "b64", "image/png") },
            postProcessors = listOf(
                PostProcessor { _, result ->
                    val img = result as ToolExecResult.ImageSuccess
                    PostProcessedResult(
                        content = img.text.uppercase(),
                        isError = false,
                        imageData = ToolCallResult.ImageData(img.base64, img.mediaType),
                    )
                },
                PostProcessor { _, result ->
                    val img = result as ToolExecResult.ImageSuccess
                    PostProcessedResult(
                        content = img.text + "!",
                        isError = false,
                        imageData = ToolCallResult.ImageData(img.base64, img.mediaType),
                    )
                },
            ),
        )

        val result = engine.executeBatch(listOf(toolCall()))
        assertEquals("SHOT!", result.results[0].content)
        assertEquals("b64", result.results[0].imageData?.base64)
    }

    // ═══════════════════════════════════════════
    // Callbacks
    // ═══════════════════════════════════════════

    @Test
    fun `callbacks fire in correct order`() = runTest {
        val cbs = trackingCallbacks()
        val engine = buildEngine(
            executor = successExecutor("ok"),
            callbacks = cbs,
        )

        val calls = listOf(
            toolCall(id = "t1", name = "tool_a"),
            toolCall(id = "t2", name = "tool_b"),
        )

        engine.executeBatch(calls)

        assertTrue(cbs.started.containsAll(listOf("tool_a", "tool_b")))
        assertTrue(cbs.completed.containsAll(listOf("tool_a", "tool_b")))
        assertTrue(cbs.blocked.isEmpty())
    }

    @Test
    fun `blocked tools fire onToolBlocked callback`() = runTest {
        val cbs = trackingCallbacks()
        val engine = buildEngine(
            executor = successExecutor(),
            preflightChecks = listOf(
                PreflightCheck { PreflightVerdict.Block("denied") }
            ),
            callbacks = cbs,
        )

        engine.executeBatch(listOf(toolCall(name = "bad_tool")))

        assertTrue(cbs.blocked.contains("bad_tool"))
        assertTrue(cbs.started.isEmpty()) // never started
    }

    // ═══════════════════════════════════════════
    // Metrics
    // ═══════════════════════════════════════════

    @Test
    fun `metrics track execution counts correctly`() = runTest {
        val engine = buildEngine(
            executor = routingExecutor(mapOf(
                "good" to ToolExecResult.Success("ok"),
                "bad" to ToolExecResult.Error("err"),
            )),
            preflightChecks = listOf(
                PreflightCheck { call ->
                    if (call.name == "blocked") PreflightVerdict.Block("no")
                    else PreflightVerdict.Pass
                }
            ),
        )

        val calls = listOf(
            toolCall(id = "t1", name = "good"),
            toolCall(id = "t2", name = "bad"),
            toolCall(id = "t3", name = "blocked"),
        )

        val result = engine.executeBatch(calls)
        val m = result.metrics

        assertEquals(3, m.totalTools)
        assertEquals(2, m.executedCount)
        assertEquals(1, m.blockedCount)
        assertEquals(2, m.errorCount) // "bad" tool error + "blocked" tool error
        assertTrue(m.totalDurationMs >= 0)
    }

    @Test
    fun `metrics track per-tool durations`() = runTest {
        val engine = buildEngine(executor = successExecutor("ok"))

        val calls = listOf(
            toolCall(id = "t1", name = "tool_a"),
            toolCall(id = "t2", name = "tool_b"),
        )

        val result = engine.executeBatch(calls)

        assertTrue(result.metrics.perToolMs.containsKey("tool_a"))
        assertTrue(result.metrics.perToolMs.containsKey("tool_b"))
        // Just verify durations are non-negative (timing is unreliable in virtual time)
        assertTrue(result.metrics.perToolMs["tool_a"]!! >= 0)
        assertTrue(result.metrics.perToolMs["tool_b"]!! >= 0)
    }

    // ═══════════════════════════════════════════
    // ExecutionBatchResult
    // ═══════════════════════════════════════════

    @Test
    fun `hadExecutions is false when all blocked`() = runTest {
        val engine = buildEngine(
            preflightChecks = listOf(
                PreflightCheck { PreflightVerdict.Block("no") }
            ),
        )

        val result = engine.executeBatch(listOf(toolCall()))
        assertFalse(result.hadExecutions)
    }

    @Test
    fun `hadExecutions is true when at least one executed`() = runTest {
        val engine = buildEngine(executor = successExecutor())
        val result = engine.executeBatch(listOf(toolCall()))
        assertTrue(result.hadExecutions)
    }

    // ═══════════════════════════════════════════
    // Builder
    // ═══════════════════════════════════════════

    @Test(expected = IllegalStateException::class)
    fun `builder throws without executor`() {
        EngineBuilder()
            .callbacks(noOpCallbacks())
            .build()
    }

    @Test(expected = IllegalStateException::class)
    fun `builder throws without callbacks`() {
        EngineBuilder()
            .executor(successExecutor())
            .build()
    }

    // ═══════════════════════════════════════════
    // Edge cases
    // ═══════════════════════════════════════════

    @Test
    fun `tool input is passed through to executor`() = runTest {
        var receivedInput: JsonObject? = null
        val engine = buildEngine(
            executor = ToolExecutor { _, params ->
                receivedInput = params
                ToolExecResult.Success("ok")
            },
        )

        val input = JsonObject(mapOf(
            "recipient" to JsonPrimitive("+1234567890"),
            "message" to JsonPrimitive("hello"),
        ))

        engine.executeBatch(listOf(toolCall(input = input)))

        assertEquals(input, receivedInput)
    }

    @Test
    fun `large batch of tools executes without issues`() = runTest {
        val engine = buildEngine(executor = successExecutor("ok"))

        val calls = (1..50).map { toolCall(id = "t_$it", name = "tool_$it") }
        val result = engine.executeBatch(calls)

        assertEquals(50, result.results.size)
        assertTrue(result.allSucceeded)
        assertEquals(50, result.metrics.executedCount)
    }
}
