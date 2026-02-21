package org.ethereumphone.andyclaw.llm

import android.util.Log
import com.llamatik.library.platform.GenStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [LlmClient] that runs inference locally via Llamatik (llama.cpp wrapper).
 *
 * Formats messages using the ChatML template expected by Qwen3, then calls
 * [LlamaCpp] (which delegates to Llamatik) for generation.
 *
 * Automatically loads the model on first use if the GGUF file is downloaded
 * but not yet in memory.
 *
 * Small models (Qwen2.5-1.5B) have limited tool-calling capability,
 * so [maxToolCount] is set to 5.
 */
class LocalLlmClient(
    private val llamaCpp: LlamaCpp,
    private val modelDownloadManager: ModelDownloadManager,
) : LlmClient {

    companion object {
        private const val TAG = "LocalLlmClient"
    }

    override val maxToolCount: Int = 5

    /**
     * Ensures the model is loaded into memory, auto-loading from disk if needed.
     * Returns true if the model is ready, false otherwise.
     */
    private fun ensureModelLoaded(): Boolean {
        if (llamaCpp.isModelLoaded) return true

        if (!modelDownloadManager.isModelDownloaded) {
            Log.e(TAG, "Model file not downloaded — cannot load")
            return false
        }

        val path = modelDownloadManager.modelFile.absolutePath
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
        buildResponse(answer, request.model)
    }

    override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) = withContext(Dispatchers.IO) {
        if (!ensureModelLoaded()) {
            callback.onError(IllegalStateException("Local model not loaded. Download and load the model first."))
            return@withContext
        }

        val prompt = formatChatML(request)
        Log.d(TAG, "streamMessage: model=${request.model}, prompt=${prompt.length} chars")

        val fullText = StringBuilder()

        llamaCpp.generateStream(prompt, object : GenStream {
            override fun onDelta(text: String) {
                fullText.append(text)
                callback.onToken(text)
            }

            override fun onComplete() {
                val answer = stripThinking(fullText.toString())
                callback.onComplete(buildResponse(answer, request.model))
            }

            override fun onError(message: String) {
                callback.onError(RuntimeException("Local inference error: $message"))
            }
        })
    }

    /**
     * Format messages into a ChatML prompt string for Qwen3.
     *
     * Adds `/no_think` to the system prompt to disable the thinking mode
     * (faster responses, avoids `<think>` tags in output).
     */
    private fun formatChatML(request: MessagesRequest): String {
        val sb = StringBuilder()

        // System prompt
        sb.append("<|im_start|>system\n")
        sb.append("/no_think\n")
        if (!request.system.isNullOrBlank()) {
            sb.append(request.system)
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
                is ContentBlock.ToolUseBlock -> "[tool_use: ${block.name}]"
                is ContentBlock.ToolResult -> block.content
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

    /** Build a [MessagesResponse] from plain text content. */
    private fun buildResponse(text: String, model: String): MessagesResponse {
        return MessagesResponse(
            id = "local-${System.currentTimeMillis()}",
            type = "message",
            role = "assistant",
            content = listOf(ContentBlock.TextBlock(text = text)),
            model = model,
            stopReason = "end_turn",
        )
    }
}
