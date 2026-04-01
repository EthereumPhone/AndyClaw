package org.ethereumphone.andyclaw.llm

import android.util.Log
import com.llamatik.library.platform.GenStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject as kxJsonObject

/**
 * [LlmClient] that runs inference locally via Llamatik (llama.cpp wrapper).
 *
 * Formats messages using the ChatML template expected by Qwen2.5, then calls
 * [LlamaCpp] (which delegates to Llamatik) for generation.
 *
 * Tool schemas are injected into the system prompt in a simplified text format
 * (Hermes-style `<tool_call>` tags) that small models can reliably produce.
 *
 * Small models (Qwen2.5-1.5B) have limited tool-calling capability,
 * so [maxToolCount] is set to 3.
 */
class LocalLlmClient(
    private val llamaCpp: LlamaCpp,
    private val modelDownloadManager: ModelDownloadManager?,
) : LlmClient {

    companion object {
        private const val TAG = "LocalLlmClient"
        /** Fallback: approximate characters per token when tokenizer is unavailable. */
        private const val CHARS_PER_TOKEN = 3.5

        private val TOOL_CALL_REGEX = Regex(
            """<tool_call>\s*([\s\S]*?)\s*</tool_call>"""
        )
        /** Matches raw JSON with "name"/"tool_name" + "arguments" keys (no wrapper tags). */
        private val RAW_JSON_TOOL_REGEX = Regex(
            """\{[^{}]*"(?:name|tool_name)"\s*:\s*"[^"]+"\s*,\s*"arguments"\s*:\s*\{[^}]*\}[^}]*\}"""
        )

    }

    override val maxToolCount: Int = 8

    /**
     * Ensures the model is loaded into memory, auto-loading from disk if needed.
     * Returns true if the model is ready, false otherwise.
     */
    private fun ensureModelLoaded(): Boolean {
        if (llamaCpp.isModelLoaded) return true

        if (modelDownloadManager?.isModelDownloaded != true) {
            Log.e(TAG, "Model file not downloaded — cannot load")
            return false
        }

        val path = modelDownloadManager!!.modelFile.absolutePath
        Log.i(TAG, "Auto-loading model from $path")
        val loaded = llamaCpp.load(path)
        if (!loaded) {
            Log.e(TAG, "Failed to load model from $path")
        }
        return loaded
    }

    override suspend fun sendMessage(request: MessagesRequest): MessagesResponse = withContext(Dispatchers.IO) {
        check(ensureModelLoaded()) { "Local model not loaded. Download and load the model first." }

        val prompt = formatChatML(request)
        Log.d(TAG, "sendMessage: model=${request.model}, prompt=${prompt.length} chars")

        val raw = llamaCpp.generate(prompt)
        val answer = stripThinking(raw)
        buildResponse(answer, request.model, promptText = prompt, tools = request.tools)
    }

    override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) = withContext(Dispatchers.IO) {
        if (!ensureModelLoaded()) {
            callback.onError(IllegalStateException("Local model not loaded. Download and load the model first."))
            return@withContext
        }

        val prompt = formatChatML(request)
        val tokenCount = llamaCpp.tokenize(prompt)
        Log.i(TAG, "streamMessage: model=${request.model}, prompt=${prompt.length} chars, ~$tokenCount tokens, tools=${request.tools?.size ?: 0}")

        val fullText = StringBuilder()

        llamaCpp.generateStream(prompt, object : GenStream {
            override fun onDelta(text: String) {
                fullText.append(text)
                callback.onToken(text)
            }

            override fun onComplete() {
                val answer = stripThinking(fullText.toString())
                callback.onComplete(buildResponse(answer, request.model, promptText = prompt, tools = request.tools))
            }

            override fun onError(message: String) {
                callback.onError(RuntimeException("Local inference error: $message"))
            }
        })
    }

    /**
     * Format messages into a ChatML prompt string for Qwen2.5.
     *
     * Injects tool schemas into the system prompt in a simple text format
     * that small models can parse. Uses Hermes-style `<tool_call>` tags
     * which Qwen2.5-Instruct models are fine-tuned to produce.
     *
     * Adds `/no_think` to disable thinking mode (faster, avoids `<think>` tags).
     */
    internal fun formatChatML(request: MessagesRequest): String {
        val sb = StringBuilder()

        // System prompt with tool schemas
        sb.append("<|im_start|>system\n")
        sb.append("/no_think\n")
        if (!request.system.isNullOrBlank()) {
            sb.append(request.system)
            sb.append("\n")
        }

        // Inject tool schemas in simplified text format
        val tools = request.tools
        if (!tools.isNullOrEmpty()) {
            sb.append("\n# Tools\n")
            sb.append("You have these tools:\n\n")
            for (tool in tools) {
                val name = tool["name"]?.jsonPrimitive?.content ?: continue
                val desc = tool["description"]?.jsonPrimitive?.content ?: ""
                sb.append("## $name\n")
                sb.append("$desc\n")
                // Simplified params — only top-level property names and types
                val schema = tool["input_schema"]?.jsonObject
                val props = schema?.get("properties")?.jsonObject
                if (props != null && props.isNotEmpty()) {
                    sb.append("Parameters: ")
                    sb.append(props.keys.joinToString(", "))
                    sb.append("\n")
                }
                sb.append("\n")
            }
            sb.append("To use a tool, respond ONLY with:\n")
            sb.append("<tool_call>\n")
            sb.append("{\"name\": \"tool_name\", \"arguments\": {\"param\": \"value\"}}\n")
            sb.append("</tool_call>\n")
        }

        sb.append("<|im_end|>\n")

        // Conversation messages
        for (msg in request.messages) {
            sb.append("<|im_start|>${msg.role}\n")
            sb.append(extractText(msg.content))
            sb.append("<|im_end|>\n")
        }

        // Prompt for assistant response
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /** Extract plain text from a [MessageContent]. */
    private fun extractText(content: MessageContent): String = when (content) {
        is MessageContent.Text -> content.value
        is MessageContent.Blocks -> content.blocks.joinToString("\n") { block ->
            when (block) {
                is ContentBlock.TextBlock -> block.text
                is ContentBlock.ToolUseBlock -> "<tool_call>\n{\"name\": \"${block.name}\", \"arguments\": ${block.input}}\n</tool_call>"
                is ContentBlock.ToolResult -> "[tool_result: ${block.content}]"
                is ContentBlock.ThinkingBlock -> block.thinking
                is ContentBlock.RedactedThinkingBlock -> ""
            }
        }
    }

    /** Strip `<think>...</think>` blocks from the raw model output. */
    private fun stripThinking(raw: String): String {
        val thinkEnd = raw.indexOf("</think>")
        return if (thinkEnd != -1) {
            raw.substring(thinkEnd + "</think>".length).trim()
        } else {
            raw.trim()
        }
    }

    /**
     * Parse tool calls from model output. Supports multiple formats:
     * 1. Hermes-style: `<tool_call>{"name":"...","arguments":{...}}</tool_call>`
     * 2. Raw JSON: `{"name":"...","arguments":{...}}`
     * 3. Alt key: `{"tool_name":"...","arguments":{...}}`
     */
    internal fun parseToolCalls(text: String, tools: List<JsonObject>?): List<ContentBlock> {
        if (tools.isNullOrEmpty()) return listOf(ContentBlock.TextBlock(text = text))

        val toolNames = tools.mapNotNull { it["name"]?.jsonPrimitive?.content }.toSet()

        // Try Hermes-style first, then fall back to raw JSON
        val matches = TOOL_CALL_REGEX.findAll(text).toList()
        if (matches.isNotEmpty()) {
            return parseFromMatches(text, matches, toolNames, grouped = true)
        }

        // Fallback: try raw JSON containing "name"/"tool_name" + "arguments"
        val rawMatches = RAW_JSON_TOOL_REGEX.findAll(text).toList()
        if (rawMatches.isNotEmpty()) {
            return parseFromMatches(text, rawMatches, toolNames, grouped = false)
        }

        return listOf(ContentBlock.TextBlock(text = text))
    }

    private fun parseFromMatches(
        text: String,
        matches: List<MatchResult>,
        toolNames: Set<String>,
        grouped: Boolean,
    ): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()

        // Text before first match
        val before = text.substring(0, matches.first().range.first).trim()
        if (before.isNotEmpty()) {
            blocks.add(ContentBlock.TextBlock(text = before))
        }

        for (match in matches) {
            try {
                val jsonStr = if (grouped) match.groupValues[1].trim() else match.value.trim()
                val parsed = Json.parseToJsonElement(jsonStr).kxJsonObject
                // Accept both "name" and "tool_name" keys
                val name = (parsed["name"] ?: parsed["tool_name"])?.jsonPrimitive?.content ?: ""
                val args = parsed["arguments"]?.jsonObject ?: buildJsonObject {}

                if (name.isNotBlank() && name in toolNames) {
                    blocks.add(ContentBlock.ToolUseBlock(
                        id = "local-tool-${System.currentTimeMillis()}",
                        name = name,
                        input = args,
                    ))
                }
            } catch (_: Exception) {
                // Malformed JSON — skip
            }
        }

        // Text after last match
        val after = text.substring(matches.last().range.last + 1).trim()
        if (after.isNotEmpty()) {
            blocks.add(ContentBlock.TextBlock(text = after))
        }

        if (blocks.none { it is ContentBlock.ToolUseBlock }) {
            return listOf(ContentBlock.TextBlock(text = text))
        }

        return blocks
    }

    /** Build a [MessagesResponse], parsing tool calls if tools were available. */
    private fun buildResponse(
        text: String,
        model: String,
        promptText: String? = null,
        tools: List<JsonObject>? = null,
    ): MessagesResponse {
        // Prefer native tokenizer; fall back to char-based estimate
        val inputTokens = if (promptText != null) {
            val native = llamaCpp.tokenize(promptText)
            if (native >= 0) native else (promptText.length / CHARS_PER_TOKEN).toInt()
        } else 0
        val outputTokens = run {
            val native = llamaCpp.tokenize(text)
            if (native >= 0) native else (text.length / CHARS_PER_TOKEN).toInt()
        }

        val contentBlocks = parseToolCalls(text, tools)
        val hasToolUse = contentBlocks.any { it is ContentBlock.ToolUseBlock }

        return MessagesResponse(
            id = "local-${System.currentTimeMillis()}",
            type = "message",
            role = "assistant",
            content = contentBlocks,
            model = model,
            stopReason = if (hasToolUse) "tool_use" else "end_turn",
            usage = Usage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
            ),
        )
    }
}
