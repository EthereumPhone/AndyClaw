package org.ethereumphone.andyclaw.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * [LlmClient] that talks to OpenAI's Codex Responses backend
 * (`https://chatgpt.com/backend-api/codex/responses`) using a ChatGPT account
 * access token from [ChatGptOauthTokenManager].
 *
 * The Responses API differs from Chat Completions in a few ways that matter:
 *  - `system` prompt is the top-level `instructions` field, not a message.
 *  - The conversation is the `input` array (stateless — no `previous_response_id`
 *    is supported on this endpoint; replay full history each call).
 *  - The server only speaks streaming (SSE). Non-streaming callers get the
 *    stream collected into a single response on the client side.
 *  - `max_output_tokens` is silently rejected; we omit it.
 *  - Required headers: `Authorization: Bearer <token>`, `chatgpt-account-id`,
 *    `OpenAI-Beta: responses=experimental`.
 *
 * **V1 limitations** (deferred):
 *  - No tool use. If a request includes tools, this client throws — the
 *    Responses API's tool format differs from chat completions and from
 *    Anthropic's; mapping it properly is a follow-up.
 *  - Only `text` content parts are translated (no images, no audio).
 *  - No reasoning_content / thinking-block surfacing.
 *
 * See [ChatGptOauthTokenManager] for the OAuth + JWT extraction details and
 * ToS caveats.
 */
class ChatGptOauthClient(
    private val tokenManager: ChatGptOauthTokenManager,
) : LlmClient {

    companion object {
        private const val TAG = "ChatGptOauthClient"
        private const val ENDPOINT = "https://chatgpt.com/backend-api/codex/responses"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)  // Codex responses can run several minutes
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun buildRequest(body: String, accessToken: String, accountId: String): Request =
        Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("chatgpt-account-id", accountId)
            .addHeader("OpenAI-Beta", "responses=experimental")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

    override suspend fun sendMessage(request: MessagesRequest): MessagesResponse =
        withContext(Dispatchers.IO) {
            if (!request.tools.isNullOrEmpty()) {
                throw ChatGptOauthException(
                    "ChatGPT OAuth provider does not yet support tool use (Codex Responses tool format unimplemented in v1).",
                )
            }
            val (token, accountId) = tokenManager.getValidAuth()
            val body = ResponsesFormatAdapter.toRequestJson(request, stream = true)
            Log.d(TAG, "sendMessage: model=${request.model}, messages=${request.messages.size}")

            val httpReq = buildRequest(body, token, accountId)
            val response = httpClient.newCall(httpReq).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "sendMessage: HTTP ${response.code} from ChatGPT, error=$errorBody")
                throw ChatGptOauthException("ChatGPT API error (${response.code}): $errorBody")
            }

            val accumulator = ResponsesSseAccumulator()
            BufferedReader(InputStreamReader(response.body!!.byteStream())).use { reader ->
                reader.forEachLine { line ->
                    if (line.startsWith("data: ")) {
                        accumulator.onData(line.removePrefix("data: "))
                    }
                }
            }
            accumulator.toMessagesResponse(request.model)
        }

    override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) =
        withContext(Dispatchers.IO) {
            if (!request.tools.isNullOrEmpty()) {
                callback.onError(
                    ChatGptOauthException(
                        "ChatGPT OAuth provider does not yet support tool use (Codex Responses tool format unimplemented in v1).",
                    ),
                )
                return@withContext
            }

            val (token, accountId) = try {
                tokenManager.getValidAuth()
            } catch (e: Exception) {
                callback.onError(e); return@withContext
            }
            val body = ResponsesFormatAdapter.toRequestJson(request, stream = true)
            Log.d(TAG, "streamMessage: model=${request.model}, messages=${request.messages.size}")

            val httpReq = buildRequest(body, token, accountId)
            val response = httpClient.newCall(httpReq).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "streamMessage: HTTP ${response.code} from ChatGPT, error=$errorBody")
                callback.onError(ChatGptOauthException("ChatGPT API error (${response.code}): $errorBody"))
                return@withContext
            }

            val accumulator = ResponsesSseAccumulator()
            val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
            try {
                reader.forEachLine { line ->
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ")
                        // Forward live text deltas to the caller for token streaming UX.
                        accumulator.onData(data) { delta -> callback.onToken(delta) }
                    }
                }
                callback.onComplete(accumulator.toMessagesResponse(request.model))
            } catch (e: Exception) {
                Log.e(TAG, "streamMessage: SSE error", e)
                callback.onError(e)
            } finally {
                reader.close()
                response.close()
            }
        }
}

/**
 * Translates AndyClaw's internal Anthropic-shaped [MessagesRequest] into a
 * Codex Responses API request body.
 *
 * Output shape (v1, text-only, no tools):
 * ```
 * {
 *   "model": "<id>",
 *   "instructions": "<system prompt or empty>",
 *   "input": [
 *     { "role": "user",       "content": [{ "type": "input_text",  "text": "..." }] },
 *     { "role": "assistant",  "content": [{ "type": "output_text", "text": "..." }] }
 *   ],
 *   "stream": true,
 *   "store": false
 * }
 * ```
 *
 * `temperature` / `top_p` carry through. `max_output_tokens` is deliberately
 * omitted — the Codex backend rejects it (see openai-oauth's
 * `normalizeCodexResponsesBody` which strips it).
 */
internal object ResponsesFormatAdapter {

    fun toRequestJson(request: MessagesRequest, stream: Boolean): String = buildJsonObject {
        put("model", request.model)
        put("instructions", request.system ?: "")
        put("input", buildJsonArray {
            for (msg in request.messages) {
                add(messageToInputItem(msg))
            }
        })
        put("stream", stream)
        put("store", false)
        request.temperature?.let { put("temperature", it) }
    }.toString()

    private fun messageToInputItem(msg: Message): JsonObject = buildJsonObject {
        put("role", msg.role)
        // Responses API expects content as either a string OR an array of typed
        // parts. We always emit the array form for forward-compat.
        val partType = if (msg.role == "assistant") "output_text" else "input_text"
        put("content", buildJsonArray {
            for (text in extractTexts(msg.content)) {
                add(buildJsonObject {
                    put("type", partType)
                    put("text", text)
                })
            }
        })
    }

    private fun extractTexts(content: MessageContent): List<String> = when (content) {
        is MessageContent.Text -> listOf(content.value)
        is MessageContent.Blocks -> content.blocks.mapNotNull { block ->
            when (block) {
                is ContentBlock.TextBlock           -> block.text
                is ContentBlock.ThinkingBlock       -> block.thinking
                is ContentBlock.RedactedThinkingBlock -> null
                is ContentBlock.ToolUseBlock        -> null // v1: no tools
                is ContentBlock.ToolResult          -> null // v1: no tools
            }
        }
    }
}

/**
 * Collects deltas from the Codex Responses SSE stream and produces a final
 * [MessagesResponse]. Designed for both `sendMessage` (no token callback) and
 * `streamMessage` (token callback fires on each `output_text.delta`).
 *
 * Recognized event types (best-effort, ignored if absent):
 *  - `response.output_text.delta` — { delta: "..." } per chunk.
 *  - `response.completed` — full final response object (used for usage stats).
 *  - `response.failed` / `response.error` — error.
 *
 * Anything else is ignored. The `data: [DONE]` line that some SSE servers emit
 * is also tolerated.
 */
private class ResponsesSseAccumulator {
    private val json = Json { ignoreUnknownKeys = true }
    private val text = StringBuilder()
    private var stopReason: String? = null
    private var inputTokens = 0
    private var outputTokens = 0
    private var responseId: String = ""

    fun onData(data: String, onTextDelta: (String) -> Unit = {}) {
        if (data == "[DONE]") return
        val obj = try {
            json.parseToJsonElement(data).jsonObject
        } catch (_: Exception) { return }

        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "response.output_text.delta" -> {
                val delta = obj["delta"]?.jsonPrimitive?.contentOrNull
                if (!delta.isNullOrEmpty()) {
                    text.append(delta)
                    onTextDelta(delta)
                }
            }
            "response.completed" -> {
                val resp = obj["response"]?.jsonObject
                responseId = resp?.get("id")?.jsonPrimitive?.contentOrNull ?: ""
                resp?.get("usage")?.jsonObject?.let { u ->
                    inputTokens  = u["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                    outputTokens = u["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                }
                stopReason = "end_turn"
            }
            "response.failed", "response.error" -> {
                val err = obj["response"]?.jsonObject?.get("error")?.jsonObject
                    ?: obj["error"]?.jsonObject
                val msg = err?.get("message")?.jsonPrimitive?.contentOrNull ?: "Codex response failed"
                throw ChatGptOauthException("Codex error: $msg")
            }
        }
    }

    fun toMessagesResponse(model: String): MessagesResponse {
        val finalText = text.toString().trim()
        return MessagesResponse(
            id = responseId.ifBlank { "chatgpt-oauth-${System.currentTimeMillis()}" },
            type = "message",
            role = "assistant",
            content = if (finalText.isEmpty()) emptyList()
                      else listOf(ContentBlock.TextBlock(finalText)),
            model = model,
            stopReason = stopReason ?: "end_turn",
            usage = Usage(inputTokens = inputTokens, outputTokens = outputTokens),
        )
    }
}
