package org.ethereumphone.andyclaw.llm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.ethereumphone.andyclaw.skills.PromptAssembler

/**
 * On-device benchmark for local LLM inference.
 *
 * Trigger via ADB:
 *   adb shell am broadcast -a org.ethereumphone.andyclaw.RUN_LLM_BENCHMARK
 *
 * Results are logged to logcat with tag "LlmBenchmark".
 * Filter with: adb logcat -s LlmBenchmark:I
 */
class LocalLlmBenchmark : BroadcastReceiver() {

    companion object {
        private const val TAG = "LlmBenchmark"
        const val ACTION = "org.ethereumphone.andyclaw.RUN_LLM_BENCHMARK"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        Log.i(TAG, "Benchmark triggered via broadcast")

        val llamaCpp = LlamaCpp()
        val downloadManager = ModelDownloadManager(context)

        if (!downloadManager.isModelDownloaded) {
            Log.e(TAG, "SKIP: Model not downloaded")
            return
        }

        val client = LocalLlmClient(llamaCpp, downloadManager)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                runBenchmark(llamaCpp, client, downloadManager)
            } catch (e: Exception) {
                Log.e(TAG, "Benchmark failed", e)
            } finally {
                llamaCpp.unload()
            }
        }
    }

    private suspend fun runBenchmark(
        llamaCpp: LlamaCpp,
        client: LocalLlmClient,
        downloadManager: ModelDownloadManager,
    ) {
        log("═══════════════════════════════════════════")
        log("  LOCAL LLM BENCHMARK STARTING")
        log("═══════════════════════════════════════════")

        // ── Test 1: Model load ──
        var startMs = System.currentTimeMillis()
        val loaded = llamaCpp.load(downloadManager.modelFile.absolutePath)
        val loadMs = System.currentTimeMillis() - startMs
        log("01_model_load | ${if (loaded) "PASS" else "FAIL"} | ${loadMs}ms")
        if (!loaded) { log("ABORT: Model failed to load"); return }

        // ── Test 2: Tokenizer ──
        val tokens = llamaCpp.tokenize("Hello, how are you?")
        log("02_tokenizer | ${if (tokens in 3..20) "PASS" else "FAIL"} | tokens=$tokens")

        // ── Test 3: Prompt size ──
        val systemPrompt = PromptAssembler.assembleLocalSystemPrompt("Andy")
        val tools = buildTestTools()
        val testRequest = buildRequest(systemPrompt, "What time is it?", tools)
        val prompt = client.formatChatML(testRequest)
        val promptTokens = llamaCpp.tokenize(prompt)
        log("03_prompt_size | ${if (promptTokens < 512) "PASS" else "FAIL"} | tokens=$promptTokens chars=${prompt.length} ctx_usage=${promptTokens * 100 / 4096}%")

        // ── Test 4: Simple greeting ──
        var result = runInference(client, systemPrompt, "Hello", null)
        log("04_greeting | ${if (result.text.isNotBlank()) "PASS" else "FAIL"} | ${result.totalMs}ms ${result.outputTokens}tok | ${result.text.take(100)}")

        // ── Test 5: Factual (2+2) ──
        result = runInference(client, systemPrompt, "What is 2 + 2?", null)
        log("05_factual | ${if (result.text.contains("4")) "PASS" else "FAIL"} | ${result.totalMs}ms ${result.outputTokens}tok | ${result.text.take(100)}")

        // ── Test 6: Tool call — execute_code for time ──
        val codeTools = listOf(
            buildTool("execute_code", "Run Java/BeanShell code on the device. Returns stdout as a string.", mapOf("code" to "Java/BeanShell code to execute")),
            buildTool("get_device_info", "Get device info: model, OS version, battery level, storage"),
        )
        result = runInference(client, systemPrompt, "What time is it?", codeTools)
        val hasCodeTool6 = result.contentBlocks.any { it is ContentBlock.ToolUseBlock && (it as ContentBlock.ToolUseBlock).name == "execute_code" }
        log("06_tool_time | ${if (hasCodeTool6) "PASS" else "FAIL"} | ${result.totalMs}ms ${result.outputTokens}tok | tool=$hasCodeTool6 | ${result.text.take(100)}")
        log("06_tool_time | raw: ${result.rawOutput.take(300)}")

        // ── Test 7: Tool call — device info ──
        result = runInference(client, systemPrompt, "What phone am I using?", codeTools)
        val hasDeviceTool = result.contentBlocks.any { it is ContentBlock.ToolUseBlock && (it as ContentBlock.ToolUseBlock).name == "get_device_info" }
        log("07_tool_device | ${if (hasDeviceTool) "PASS" else "FAIL"} | ${result.totalMs}ms ${result.outputTokens}tok | tool=$hasDeviceTool | ${result.text.take(100)}")
        log("07_tool_device | raw: ${result.rawOutput.take(300)}")

        // ── Test 8: Tool call — web search ──
        val searchTools = listOf(
            buildTool("web_search", "Search the web for current information", mapOf("query" to "Search query string")),
        )
        result = runInference(client, systemPrompt, "Search for Bitcoin price", searchTools)
        val hasSearchTool = result.contentBlocks.any { it is ContentBlock.ToolUseBlock && (it as ContentBlock.ToolUseBlock).name == "web_search" }
        log("08_tool_search | ${if (hasSearchTool) "PASS" else "FAIL"} | ${result.totalMs}ms ${result.outputTokens}tok | tool=$hasSearchTool | ${result.text.take(100)}")
        log("08_tool_search | raw: ${result.rawOutput.take(300)}")

        // ── Test 9: No tool when not needed ──
        result = runInference(client, systemPrompt, "What is the capital of France?", codeTools)
        val noToolUsed = result.contentBlocks.none { it is ContentBlock.ToolUseBlock }
        val mentionsParis = result.text.lowercase().contains("paris")
        log("09_no_tool | ${if (noToolUsed && mentionsParis) "PASS" else "FAIL"} | ${result.totalMs}ms | no_tool=$noToolUsed paris=$mentionsParis | ${result.text.take(100)}")

        // ── Summary ──
        log("═══════════════════════════════════════════")
        log("  BENCHMARK COMPLETE")
        log("═══════════════════════════════════════════")
    }

    private data class InferenceResult(
        val text: String,
        val rawOutput: String,
        val contentBlocks: List<ContentBlock>,
        val stopReason: String?,
        val outputTokens: Int,
        val totalMs: Long,
    )

    private suspend fun runInference(
        client: LocalLlmClient,
        systemPrompt: String,
        userMessage: String,
        tools: List<JsonObject>?,
    ): InferenceResult {
        val request = buildRequest(systemPrompt, userMessage, tools)
        val startMs = System.currentTimeMillis()
        // Use streaming path (same as real app) — non-streaming path has different native code
        val deferred = CompletableDeferred<MessagesResponse>()
        client.streamMessage(request, object : StreamingCallback {
            override fun onToken(text: String) {}
            override fun onToolUse(id: String, name: String, input: kotlinx.serialization.json.JsonObject) {}
            override fun onComplete(response: MessagesResponse) { deferred.complete(response) }
            override fun onError(error: Throwable) { deferred.completeExceptionally(error) }
        })
        val response = deferred.await()
        val totalMs = System.currentTimeMillis() - startMs

        val text = response.content
            .filterIsInstance<ContentBlock.TextBlock>()
            .joinToString("") { it.text }

        val rawOutput = response.content.joinToString("") { block ->
            when (block) {
                is ContentBlock.TextBlock -> block.text
                is ContentBlock.ToolUseBlock -> "<tool_call>{\"name\":\"${block.name}\",\"arguments\":${block.input}}</tool_call>"
                else -> ""
            }
        }

        return InferenceResult(
            text = text,
            rawOutput = rawOutput,
            contentBlocks = response.content,
            stopReason = response.stopReason,
            outputTokens = response.usage?.outputTokens ?: 0,
            totalMs = totalMs,
        )
    }

    private fun buildRequest(system: String, user: String, tools: List<JsonObject>?) =
        MessagesRequest(
            model = "qwen2.5-1.5b-instruct",
            maxTokens = 256,
            system = system,
            messages = listOf(Message.user(user)),
            tools = tools,
        )

    private fun buildTestTools() = listOf(
        buildTool("execute_code", "Run Java/BeanShell code on the device. Returns stdout as a string.", mapOf("code" to "Java/BeanShell code to execute")),
        buildTool("get_device_info", "Get device info: model, OS version, battery level, storage"),
    )

    private fun buildTool(name: String, desc: String, params: Map<String, String> = emptyMap()): JsonObject =
        buildJsonObject {
            put("name", name)
            put("description", desc)
            putJsonObject("input_schema") {
                put("type", "object")
                putJsonObject("properties") {
                    for ((k, d) in params) {
                        putJsonObject(k) { put("type", "string"); put("description", d) }
                    }
                }
            }
        }

    private fun log(msg: String) = Log.i(TAG, msg)
}
