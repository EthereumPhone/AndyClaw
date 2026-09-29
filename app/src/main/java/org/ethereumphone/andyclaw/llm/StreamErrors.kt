package org.ethereumphone.andyclaw.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Response
import java.io.IOException

/**
 * How a transport failure becomes an exception the rest of the app understands.
 *
 * Every consumer keys off the thrown type, not a callback: [withRetry] retries
 * [AnthropicApiException] by status and [IOException] as a dropped connection,
 * [ZeroBalanceFallbackClient] and the chat's top-up prompt match a 403 whose body says
 * "Insufficient balance", and reactive compaction matches a 413. A failure reported through
 * `onError` and a normal return reaches none of them — the turn went on as an empty,
 * "successful" reply.
 */
object StreamErrors {

    /** The Go bridge formats a non-2xx as `HTTP <code>: <body>` (bridge.go). */
    private val BRIDGE_HTTP = Regex("^HTTP (\\d{3}): (.*)$", RegexOption.DOT_MATCHES_ALL)

    /**
     * An error the tinfoil Go bridge returned. A status line becomes the API error it is;
     * anything else (dial, TLS, EHBP, a read that broke mid-stream) is a transport failure,
     * which [withRetry] treats as retryable and AgentLoop's "tools already started" guard
     * refuses to retry once a tool is running.
     */
    fun fromBridgeError(message: String?, provider: String, cause: Throwable? = null): Exception {
        val text = message?.trim().orEmpty()
        BRIDGE_HTTP.find(text)?.let { m ->
            return AnthropicApiException(m.groupValues[1].toInt(), m.groupValues[2], provider = provider)
        }
        return IOException(text.ifEmpty { "$provider bridge error" }, cause)
    }

    /**
     * An error the server reported inside an otherwise healthy stream: Anthropic's
     * `event: error` or an OpenAI-style `{"error":{…}}` chunk. It is a failed request, not
     * the end of a reply, so it becomes the status the same failure would have had up front.
     */
    fun fromStreamError(error: kotlinx.serialization.json.JsonElement?, provider: String): AnthropicApiException {
        val obj = error as? JsonObject
        val message = when {
            obj != null -> (obj["message"] as? JsonPrimitive)?.contentOrNull ?: obj.toString()
            error is JsonPrimitive -> error.contentOrNull ?: "stream error"
            else -> "stream error"
        }
        val type = (obj?.get("type") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val code = (obj?.get("code") as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
        val status = when {
            code != null && code in 400..599 -> code
            type.contains("overloaded") -> 529
            type.contains("rate_limit") -> 429
            type.contains("request_too_large") -> 413
            type.contains("invalid_request") -> 400
            type.contains("authentication") -> 401
            type.contains("permission") -> 403
            type.contains("not_found") -> 404
            // An upstream fault reported mid-stream (api_error, a provider's 5xx): retryable.
            else -> 500
        }
        return AnthropicApiException(status, "$type: $message".removePrefix(": "), provider = provider)
    }
}

/**
 * Tool arguments as the model streamed them.
 *
 * A reply cut off by `max_tokens`, or a model that emitted broken JSON, used to run the tool
 * with `{}` — a call nobody asked for, with every parameter at its default. The block is still
 * kept (the assistant turn must carry every tool_use so each gets a tool_result), but its input
 * is marked, and AgentLoop answers it with an error instead of executing it.
 */
object ToolArguments {
    /** The marker key. Sent back to the model with its own call, which is what it should see. */
    const val INVALID_KEY = "__invalid_arguments"

    const val INVALID_MESSAGE =
        "The arguments for this tool call were truncated or were not valid JSON, so it was not run. " +
            "Retry the call with complete, valid JSON arguments."

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String?): JsonObject {
        val text = raw?.trim().orEmpty()
        // A tool that takes no parameters legitimately streams nothing, "{}" or "null".
        if (text.isEmpty()) return JsonObject(emptyMap())
        val parsed = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            null
        }
        return when (parsed) {
            is JsonObject -> parsed
            JsonNull -> JsonObject(emptyMap())
            else -> JsonObject(mapOf(INVALID_KEY to JsonPrimitive(text.take(200))))
        }
    }

    fun isInvalid(input: JsonObject): Boolean = input.containsKey(INVALID_KEY)

    /** The tool_result for a call that must not run, or null when it may. */
    fun errorResultOrNull(block: ContentBlock.ToolUseBlock): ContentBlock.ToolResult? =
        if (isInvalid(block.input)) ContentBlock.ToolResult(block.id, INVALID_MESSAGE, isError = true) else null
}

/**
 * Runs a blocking OkHttp call so that cancelling the coroutine closes the socket.
 *
 * A blocking read does not look at the coroutine: the chat's Cancel used to leave the stream
 * running (and billing) until the model finished, feeding tokens to a turn nobody was waiting
 * for. A watcher child cancels the call the moment the scope is cancelled; the read then fails
 * and is reported as the cancellation it is, not as a dropped connection to retry.
 */
internal suspend fun <T> Call.executeCancellable(block: (Response) -> T): T = coroutineScope {
    val call = this@executeCancellable
    // UNDISPATCHED: armed before execute() starts. A dispatched watcher cancelled before it
    // first ran would skip its finally and leave the socket open.
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        call.execute().use(block)
    } catch (e: IOException) {
        // Only the watcher cancels the call, and only when this scope is being cancelled.
        if (call.isCanceled()) throw CancellationException("LLM stream cancelled").apply { initCause(e) }
        throw e
    } finally {
        watcher.cancel()
    }
}
