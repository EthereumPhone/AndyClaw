package org.ethereumphone.andyclaw.llm

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.ethereumphone.andyclaw.skills.PromptAssembler
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Instrumented tests that run actual local model inference on the device.
 *
 * These tests measure real performance metrics and validate tool calling
 * with the Qwen2.5-1.5B model. They require the model GGUF to be
 * downloaded on the device.
 *
 * Run with: ./gradlew :app:connectedDebugAndroidTest --tests "*.LocalLlmInferenceTest"
 *
 * Tests are ordered alphabetically to run benchmarks in a predictable sequence.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LocalLlmInferenceTest {

    companion object {
        private const val TAG = "LocalLlmInferenceTest"
        private const val MODEL = "qwen2.5-1.5b-instruct"
    }

    private lateinit var llamaCpp: LlamaCpp
    private lateinit var client: LocalLlmClient
    private lateinit var downloadManager: ModelDownloadManager

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        llamaCpp = LlamaCpp()
        downloadManager = ModelDownloadManager(context)

        assumeTrue(
            "Model GGUF not downloaded — skip inference tests",
            downloadManager.isModelDownloaded
        )

        client = LocalLlmClient(llamaCpp, downloadManager)
    }

    @After
    fun tearDown() {
        if (::llamaCpp.isInitialized && llamaCpp.isModelLoaded) {
            llamaCpp.unload()
        }
    }

    // ── Performance benchmarks ──────────────────────────────────────

    @Test
    fun test01_modelLoadsSuccessfully() {
        val startMs = System.currentTimeMillis()
        val loaded = llamaCpp.load(downloadManager.modelFile.absolutePath)
        val elapsed = System.currentTimeMillis() - startMs

        assertTrue("Model should load", loaded)
        assertTrue("Model should be marked as loaded", llamaCpp.isModelLoaded)
        log("Model load: ${elapsed}ms")
    }

    @Test
    fun test02_tokenizeWorks() {
        ensureLoaded()
        val text = "Hello, how are you?"
        val tokens = llamaCpp.tokenize(text)

        assertTrue("Token count should be positive", tokens > 0)
        assertTrue("Token count should be reasonable", tokens in 3..20)
        log("Tokenize '$text' -> $tokens tokens")
    }

    @Test
    fun test03_localPromptSize() {
        ensureLoaded()
        val systemPrompt = PromptAssembler.assembleLocalSystemPrompt("Andy")
        val tools = buildTestTools()
        val request = buildRequest(systemPrompt, "What time is it?", tools)
        val prompt = client.formatChatML(request)
        val tokens = llamaCpp.tokenize(prompt)

        log("Local prompt: ${prompt.length} chars, $tokens tokens")
        log("Context utilization: ${tokens * 100 / 4096}% of 4096")

        assertTrue(
            "Prompt should be under 512 tokens (actual: $tokens)",
            tokens < 512
        )
    }

    @Test
    fun test04_simpleGreeting() {
        ensureLoaded()
        val result = runInference(
            systemPrompt = PromptAssembler.assembleLocalSystemPrompt("Andy"),
            userMessage = "Hello",
            tools = null, // no tools for simple greeting
        )

        assertTrue("Should produce some output", result.text.isNotBlank())
        assertTrue("Should not be a tool call", result.contentBlocks.none { it is ContentBlock.ToolUseBlock })
        log("Greeting response (${result.outputTokens} tokens, ${result.totalMs}ms): ${result.text.take(200)}")
    }

    @Test
    fun test05_simpleFactual() {
        ensureLoaded()
        val result = runInference(
            systemPrompt = PromptAssembler.assembleLocalSystemPrompt("Andy"),
            userMessage = "What is 2 + 2?",
            tools = null,
        )

        assertTrue("Should produce output", result.text.isNotBlank())
        assertTrue("Should contain '4'", result.text.contains("4"))
        log("Factual response (${result.outputTokens} tokens, ${result.totalMs}ms): ${result.text.take(200)}")
    }

    @Test
    fun test06_toolCallTime() {
        ensureLoaded()
        val tools = listOf(
            buildTool("get_current_time", "Get the current date and time on this device"),
        )
        val result = runInference(
            systemPrompt = PromptAssembler.assembleLocalSystemPrompt("Andy"),
            userMessage = "What time is it?",
            tools = tools,
        )

        val hasToolCall = result.contentBlocks.any { it is ContentBlock.ToolUseBlock }
        val toolBlock = result.contentBlocks.filterIsInstance<ContentBlock.ToolUseBlock>().firstOrNull()

        log("Tool call test (${result.outputTokens} tokens, ${result.totalMs}ms)")
        log("  Raw output: ${result.text.take(300)}")
        log("  Has tool call: $hasToolCall")
        if (toolBlock != null) {
            log("  Tool name: ${toolBlock.name}")
            log("  Tool args: ${toolBlock.input}")
        }
        log("  Stop reason: ${result.stopReason}")

        // This is the critical test — the model should attempt a tool call
        assertTrue(
            "Model should call get_current_time tool (output: ${result.text.take(100)})",
            hasToolCall && toolBlock?.name == "get_current_time"
        )
    }

    @Test
    fun test07_toolCallAlarm() {
        ensureLoaded()
        val tools = listOf(
            buildTool("set_alarm", "Set an alarm on this device", mapOf(
                "time" to "The time for the alarm (e.g. 07:00)",
                "label" to "Label for the alarm",
            )),
        )
        val result = runInference(
            systemPrompt = PromptAssembler.assembleLocalSystemPrompt("Andy"),
            userMessage = "Set an alarm for 7am",
            tools = tools,
        )

        val toolBlock = result.contentBlocks.filterIsInstance<ContentBlock.ToolUseBlock>().firstOrNull()

        log("Alarm test (${result.outputTokens} tokens, ${result.totalMs}ms)")
        log("  Raw output: ${result.text.take(300)}")
        log("  Tool: ${toolBlock?.name}, args: ${toolBlock?.input}")

        assertTrue(
            "Should call set_alarm (output: ${result.text.take(100)})",
            toolBlock?.name == "set_alarm"
        )
    }

    @Test
    fun test08_noToolWhenNotNeeded() {
        ensureLoaded()
        val tools = listOf(
            buildTool("get_current_time", "Get the current date and time"),
        )
        val result = runInference(
            systemPrompt = PromptAssembler.assembleLocalSystemPrompt("Andy"),
            userMessage = "What is the capital of France?",
            tools = tools,
        )

        val hasToolCall = result.contentBlocks.any { it is ContentBlock.ToolUseBlock }

        log("No-tool test (${result.outputTokens} tokens, ${result.totalMs}ms): ${result.text.take(200)}")
        log("  Has tool call: $hasToolCall")

        assertFalse(
            "Should NOT use tools for a factual question",
            hasToolCall
        )
        assertTrue(
            "Should mention Paris",
            result.text.lowercase().contains("paris")
        )
    }

    @Test
    fun test09_performanceSummary() {
        ensureLoaded()
        val tools = buildTestTools()
        val request = buildRequest(
            PromptAssembler.assembleLocalSystemPrompt("Andy"),
            "What time is it?",
            tools,
        )
        val prompt = client.formatChatML(request)
        val promptTokens = llamaCpp.tokenize(prompt)

        val startMs = System.currentTimeMillis()
        val response = runBlocking { client.sendMessage(request) }
        val totalMs = System.currentTimeMillis() - startMs

        val inputTokens = response.usage?.inputTokens ?: 0
        val outputTokens = response.usage?.outputTokens ?: 0
        val promptDecodeRate = if (totalMs > 0 && inputTokens > 0) inputTokens * 1000.0 / totalMs else 0.0

        log("═══════════════════════════════════════════")
        log("  PERFORMANCE SUMMARY")
        log("═══════════════════════════════════════════")
        log("  Prompt tokens:    $promptTokens")
        log("  Output tokens:    $outputTokens")
        log("  Total time:       ${totalMs}ms")
        log("  Decode rate:      ${"%.1f".format(promptDecodeRate)} tok/s")
        log("  Context usage:    ${promptTokens * 100 / 4096}%")
        log("  Content blocks:   ${response.content.size}")
        log("  Has tool call:    ${response.content.any { it is ContentBlock.ToolUseBlock }}")
        log("  Stop reason:      ${response.stopReason}")
        log("═══════════════════════════════════════════")

        assertTrue("Should complete in under 120 seconds", totalMs < 120_000)
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun ensureLoaded() {
        if (!llamaCpp.isModelLoaded) {
            val loaded = llamaCpp.load(downloadManager.modelFile.absolutePath)
            assertTrue("Model must load for test", loaded)
        }
    }

    data class InferenceResult(
        val text: String,
        val contentBlocks: List<ContentBlock>,
        val stopReason: String?,
        val outputTokens: Int,
        val totalMs: Long,
    )

    private fun runInference(
        systemPrompt: String,
        userMessage: String,
        tools: List<JsonObject>?,
    ): InferenceResult {
        val request = buildRequest(systemPrompt, userMessage, tools)
        val startMs = System.currentTimeMillis()
        val response = runBlocking { client.sendMessage(request) }
        val totalMs = System.currentTimeMillis() - startMs

        val text = response.content
            .filterIsInstance<ContentBlock.TextBlock>()
            .joinToString("") { it.text }

        return InferenceResult(
            text = text,
            contentBlocks = response.content,
            stopReason = response.stopReason,
            outputTokens = response.usage?.outputTokens ?: 0,
            totalMs = totalMs,
        )
    }

    private fun buildRequest(
        systemPrompt: String,
        userMessage: String,
        tools: List<JsonObject>?,
    ) = MessagesRequest(
        model = MODEL,
        maxTokens = 256,
        system = systemPrompt,
        messages = listOf(Message.user(userMessage)),
        tools = tools,
    )

    private fun buildTestTools() = listOf(
        buildTool("get_current_time", "Get the current date and time on this device"),
        buildTool("set_alarm", "Set an alarm on this device", mapOf(
            "time" to "Time for the alarm",
            "label" to "Label for the alarm",
        )),
    )

    private fun buildTool(
        name: String,
        description: String,
        params: Map<String, String> = emptyMap(),
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                for ((key, desc) in params) {
                    putJsonObject(key) {
                        put("type", "string")
                        put("description", desc)
                    }
                }
            }
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        // Also print to stdout so it appears in test console output
        println("[LocalLlmInference] $msg")
    }
}
