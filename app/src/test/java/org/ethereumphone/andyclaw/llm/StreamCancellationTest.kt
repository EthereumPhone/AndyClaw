package org.ethereumphone.andyclaw.llm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Cancel must close the socket. A blocking read never looks at the coroutine, so a cancelled
 * turn used to keep streaming (and billing) until the model finished on its own.
 */
class StreamCancellationTest {

    /** Serves [head] and then holds the connection open without sending anything more. */
    private fun hangingServer(head: String): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    // Read the request headers and body length, then answer.
                    var contentLength = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(":").trim().toInt()
                        }
                    }
                    repeat(contentLength) { input.read() }
                    socket.getOutputStream().apply {
                        write(head.toByteArray())
                        flush()
                    }
                    Thread.sleep(60_000)
                }
            } catch (_: Exception) {
            }
        }
        return server
    }

    private val sseHead =
        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" +
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"model\":\"x\"}}\n\n" +
            "event: content_block_start\ndata: {\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
            "event: content_block_delta\ndata: {\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n"

    @Test
    fun `cancelling a stream closes the connection promptly`() = runBlocking {
        val server = hangingServer(sseHead)
        val client = AnthropicClient(apiKey = { "k" }, baseUrl = "http://127.0.0.1:${server.localPort}/")
        val firstToken = CompletableDeferred<Unit>()
        var failure: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                client.streamMessage(
                    MessagesRequest(model = "x", maxTokens = 8, messages = listOf(Message.user("hi"))),
                    object : StreamingCallback {
                        override fun onToken(text: String) { firstToken.complete(Unit) }
                        override fun onToolUse(id: String, name: String, input: JsonObject) {}
                        override fun onComplete(response: MessagesResponse) {}
                        override fun onError(error: Throwable) {}
                    },
                )
            } catch (e: Throwable) {
                failure = e
                throw e
            }
        }
        withTimeout(5_000) { firstToken.await() }
        // The read timeout is 120 s; only closing the socket ends this in time.
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue("cancel must surface as cancellation, not a retryable IO error: $failure",
            failure is kotlinx.coroutines.CancellationException)
        server.close()
    }

    @Test
    fun `a stream that ends before message_stop is an IO failure`() = runBlocking {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                var contentLength = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) contentLength = line.substringAfter(":").trim().toInt()
                }
                repeat(contentLength) { input.read() }
                socket.getOutputStream().write(sseHead.toByteArray())
            }
        }
        val client = AnthropicClient(apiKey = { "k" }, baseUrl = "http://127.0.0.1:${server.localPort}/")
        var tokens = ""
        val e = try {
            client.streamMessage(
                MessagesRequest(model = "x", maxTokens = 8, messages = listOf(Message.user("hi"))),
                object : StreamingCallback {
                    override fun onToken(text: String) { tokens += text }
                    override fun onToolUse(id: String, name: String, input: JsonObject) {}
                    override fun onComplete(response: MessagesResponse) { throw AssertionError("not complete") }
                    override fun onError(error: Throwable) {}
                },
            )
            null
        } catch (t: Throwable) {
            t
        }
        assertEquals("Hi", tokens)
        assertTrue("expected IOException, got $e", e is IOException)
        server.close()
    }
}
