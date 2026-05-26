package org.ethereumphone.andyclaw.llm

import android.util.Log
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
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
    /**
     * Returns the absolute path of the user-selected GGUF, or null to fall
     * back to [modelDownloadManager]'s default download. Wired in NodeApp
     * from [GgufRegistry] + the `selectedGgufFilename` pref.
     */
    private val selectedModelPathProvider: () -> String? = { null },
    /** Returns the current runtime config to apply at init + per-generation. */
    private val configProvider: () -> LocalLlmRuntimeConfig = { LocalLlmRuntimeConfig.DEFAULT },
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
     * Ensures a model is loaded with the current config. Resolves the path in
     * this order: (1) the user-selected GGUF from [selectedModelPathProvider],
     * (2) [modelDownloadManager]'s default download. Reloads if the selected
     * path or hardware-level config changed since the last load. Sampling
     * params are applied unconditionally (cheap, no reload).
     */
    private fun ensureModelLoaded(): Boolean {
        val desiredConfig = configProvider()

        val selectedPath = try { selectedModelPathProvider() } catch (_: Exception) { null }
        val path = selectedPath
            ?: modelDownloadManager?.takeIf { it.isModelDownloaded }?.modelFile?.absolutePath
            ?: run {
                Log.e(TAG, "No GGUF available — none selected and default not downloaded")
                return false
            }

        // Compare HARDWARE fields only: sampling changes (temp / topP / etc.)
        // are applied per-generation via updateGenerateParams and must not
        // trigger a 750MB+ model reload. See LocalLlmRuntimeConfig.hardwareEquals.
        val needsLoad = !llamaCpp.isModelLoaded ||
            llamaCpp.loadedModelPath != path ||
            !desiredConfig.hardwareEquals(llamaCpp.loadedConfig)
        if (needsLoad) {
            Log.i(TAG, "Loading model: $path (config=$desiredConfig)")
            val loaded = llamaCpp.load(path, desiredConfig)
            if (!loaded) {
                Log.e(TAG, "Failed to load model from $path")
                return false
            }
        }
        applySamplingParams(desiredConfig)
        return true
    }

    /** Push the sampling knobs into Llamatik. Cheap; no model reload. */
    private fun applySamplingParams(config: LocalLlmRuntimeConfig) {
        LlamaBridge.updateGenerateParams(
            temperature   = config.temperature,
            maxTokens     = config.maxTokens,
            topP          = config.topP,
            topK          = config.topK,
            repeatPenalty = config.repeatPenalty,
        )
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
     * Format messages into a model-appropriate prompt string.
     *
     * Dispatches by the loaded GGUF's filename: Gemma family uses
     * `<start_of_turn>/<end_of_turn>` with `user`/`model` roles; everything
     * else falls back to Qwen2.5 ChatML (`<|im_start|>/<|im_end|>` with
     * `system`/`user`/`assistant` roles). This is a heuristic — when no
     * GGUF is loaded yet (path null), Qwen ChatML is the safe default since
     * the bundled model is Qwen2.5-1.5B-Instruct.
     *
     * Future: read `tokenizer.chat_template` from GGUF metadata via Llamatik
     * and apply it via llama.cpp's `llama_chat_apply_template`. For now the
     * filename heuristic covers the two model families we ship with.
     */
    internal fun formatChatML(request: MessagesRequest): String {
        val loadedPath = llamaCpp.loadedModelPath?.lowercase().orEmpty()
        return if ("gemma" in loadedPath) formatGemma(request)
        else formatQwenChatML(request)
    }

    /** Qwen2.5 ChatML format: `<|im_start|>role\n...<|im_end|>`. */
    private fun formatQwenChatML(request: MessagesRequest): String {
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

    /**
     * Gemma 2 / 3 / 3n chat format. Differences vs Qwen ChatML:
     *  - Turn delimiters are `<start_of_turn>role\n...<end_of_turn>`.
     *  - Roles are `user` and `model` (no `assistant`, no `system`).
     *  - There is no system role — the system prompt + tool schemas are folded
     *    into the first user turn.
     *  - `<bos>` is added by the tokenizer (we don't emit it literally).
     */
    private fun formatGemma(request: MessagesRequest): String {
        // Build the system+tools preamble that will prefix the first user turn.
        val preamble = buildString {
            if (!request.system.isNullOrBlank()) {
                append(request.system).append("\n\n")
            }
            val tools = request.tools
            if (!tools.isNullOrEmpty()) {
                append("# Tools\n")
                append("You have these tools:\n\n")
                for (tool in tools) {
                    val name = tool["name"]?.jsonPrimitive?.content ?: continue
                    val desc = tool["description"]?.jsonPrimitive?.content ?: ""
                    append("## $name\n")
                    append("$desc\n")
                    val schema = tool["input_schema"]?.jsonObject
                    val props = schema?.get("properties")?.jsonObject
                    if (props != null && props.isNotEmpty()) {
                        append("Parameters: ")
                        append(props.keys.joinToString(", "))
                        append("\n")
                    }
                    append("\n")
                }
                append("To use a tool, respond ONLY with:\n")
                append("<tool_call>\n")
                append("{\"name\": \"tool_name\", \"arguments\": {\"param\": \"value\"}}\n")
                append("</tool_call>\n\n")
            }
        }

        val sb = StringBuilder()
        val messages = request.messages
        for ((idx, msg) in messages.withIndex()) {
            val role = if (msg.role == "assistant") "model" else "user"
            sb.append("<start_of_turn>").append(role).append("\n")
            // Prepend preamble to the first user turn so the model gets the
            // system instructions + tool schemas in-band.
            if (idx == 0 && role == "user" && preamble.isNotEmpty()) {
                sb.append(preamble)
            }
            sb.append(extractText(msg.content))
            sb.append("<end_of_turn>\n")
        }
        // Prompt for the model to respond.
        sb.append("<start_of_turn>model\n")
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
