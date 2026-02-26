package org.ethereumphone.andyclaw.cli

import android.util.Log
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.agent.AgentLoop
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.memory.model.MemorySource
import org.ethereumphone.andyclaw.sessions.model.MessageRole
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities

class CliApiServer(
    private val app: NodeApp,
    private val port: Int,
    private val bearerToken: String,
) {
    companion object {
        private const val TAG = "CliApiServer"
        const val DEFAULT_PORT = 8642
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return

        server = embeddedServer(CIO, port = port) {
            install(WebSockets)

            routing {
                get("/api/health") {
                    call.respondText(
                        """{"status":"ok"}""",
                        io.ktor.http.ContentType.Application.Json,
                    )
                }

                get("/api/sessions") {
                    if (!checkAuth(call)) return@get
                    val sessions = app.sessionManager.getSessions()
                    val payload = buildJsonArray {
                        for (s in sessions) {
                            add(buildJsonObject {
                                put("id", s.id)
                                put("title", s.title)
                                put("model", s.model)
                                put("created_at", s.createdAt)
                                put("updated_at", s.updatedAt)
                            })
                        }
                    }
                    call.respondText(payload.toString(), io.ktor.http.ContentType.Application.Json)
                }

                get("/api/sessions/{id}") {
                    if (!checkAuth(call)) return@get
                    val id = call.parameters["id"]
                        ?: return@get call.respondText(
                            """{"error":"missing id"}""",
                            io.ktor.http.ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                    val messages = app.sessionManager.getMessages(id)
                    val payload = buildJsonArray {
                        for (m in messages) {
                            add(buildJsonObject {
                                put("role", m.role.name.lowercase())
                                put("content", m.content)
                                m.toolName?.let { put("tool_name", it) }
                                put("timestamp", m.timestamp)
                            })
                        }
                    }
                    call.respondText(payload.toString(), io.ktor.http.ContentType.Application.Json)
                }

                post("/api/chat") {
                    if (!checkAuth(call)) return@post
                    val body = try {
                        json.parseToJsonElement(call.receiveText()).jsonObject
                    } catch (_: Exception) {
                        return@post call.respondText(
                            """{"error":"invalid json body"}""",
                            io.ktor.http.ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                    }
                    val message = body["message"]?.jsonPrimitive?.contentOrNull
                        ?: return@post call.respondText(
                            """{"error":"missing 'message' field"}""",
                            io.ktor.http.ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                    val sessionId = body["session_id"]?.jsonPrimitive?.contentOrNull

                    val result = runAgentOneShotCollect(message, sessionId)
                    call.respondText(result.toString(), io.ktor.http.ContentType.Application.Json)
                }

                webSocket("/ws") {
                    val token = call.request.header("Authorization")
                        ?.removePrefix("Bearer ")?.trim()
                        ?: call.request.queryParameters["token"]
                    if (token != bearerToken) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                        return@webSocket
                    }

                    Log.i(TAG, "CLI WebSocket client connected")
                    val outgoing = Channel<String>(Channel.BUFFERED)
                    var approvalDeferred: CompletableDeferred<Boolean>? = null

                    val sendJob = launch {
                        outgoing.consumeEach { msg ->
                            send(Frame.Text(msg))
                        }
                    }

                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val text = frame.readText()
                            val msg = try {
                                json.parseToJsonElement(text).jsonObject
                            } catch (_: Exception) {
                                outgoing.send(errorJson("invalid json"))
                                continue
                            }

                            when (msg["type"]?.jsonPrimitive?.contentOrNull) {
                                "chat" -> {
                                    val userMessage = msg["message"]?.jsonPrimitive?.contentOrNull
                                    if (userMessage.isNullOrBlank()) {
                                        outgoing.send(errorJson("missing 'message' field"))
                                        continue
                                    }
                                    val sessionId = msg["session_id"]?.jsonPrimitive?.contentOrNull
                                    launch {
                                        runAgentStreaming(userMessage, sessionId, outgoing) { deferred ->
                                            approvalDeferred = deferred
                                        }
                                    }
                                }
                                "approval" -> {
                                    val approved = msg["approved"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                                    approvalDeferred?.complete(approved)
                                    approvalDeferred = null
                                }
                                else -> outgoing.send(errorJson("unknown message type"))
                            }
                        }
                    } catch (_: CancellationException) {
                        // Normal close
                    } catch (e: Exception) {
                        Log.w(TAG, "WebSocket error: ${e.message}", e)
                    } finally {
                        outgoing.close()
                        sendJob.cancel()
                        Log.i(TAG, "CLI WebSocket client disconnected")
                    }
                }
            }
        }

        server?.start(wait = false)
        Log.i(TAG, "CLI API server started on port $port")
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        Log.i(TAG, "CLI API server stopped")
    }

    val isRunning: Boolean get() = server != null

    private suspend fun checkAuth(call: io.ktor.server.application.ApplicationCall): Boolean {
        val token = call.request.header("Authorization")
            ?.removePrefix("Bearer ")?.trim()
        if (token != bearerToken) {
            call.respondText(
                """{"error":"unauthorized"}""",
                io.ktor.http.ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
            return false
        }
        return true
    }

    private suspend fun runAgentOneShotCollect(
        userMessage: String,
        existingSessionId: String?,
    ): JsonObject {
        val sid = existingSessionId ?: app.sessionManager.createSession(
            model = app.securePrefs.selectedModel.value,
        ).id

        app.sessionManager.addMessage(sid, MessageRole.USER, userMessage)

        val conversationHistory = buildConversationHistory(sid)
        val agentLoop = buildAgentLoop()

        val fullText = StringBuilder()
        val toolCalls = mutableListOf<JsonObject>()
        val errorRef = arrayOfNulls<String>(1)

        agentLoop.run(userMessage, conversationHistory, object : AgentLoop.Callbacks {
            override fun onToken(text: String) { fullText.append(text) }
            override fun onToolExecution(toolName: String) {}
            override fun onToolResult(toolName: String, result: SkillResult) {
                val data = when (result) {
                    is SkillResult.Success -> result.data
                    is SkillResult.Error -> "Error: ${result.message}"
                    is SkillResult.RequiresApproval -> "Requires approval: ${result.description}"
                }
                toolCalls.add(buildJsonObject {
                    put("name", toolName)
                    put("result", data)
                })
            }
            override suspend fun onApprovalNeeded(description: String): Boolean {
                return app.securePrefs.yoloMode.value
            }
            override suspend fun onPermissionsNeeded(permissions: List<String>): Boolean {
                val requester = app.permissionRequester ?: return false
                return try {
                    requester.requestIfMissing(permissions).values.all { it }
                } catch (_: Exception) { false }
            }
            override fun onComplete(fullText: String) {}
            override fun onError(error: Throwable) { errorRef[0] = error.message }
        })

        val response = fullText.toString()
        if (response.isNotBlank()) {
            app.sessionManager.addMessage(sid, MessageRole.ASSISTANT, response)
        }
        autoStoreMemory(userMessage)

        return buildJsonObject {
            put("session_id", sid)
            put("response", response)
            if (toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray { toolCalls.forEach { add(it) } })
            }
            errorRef[0]?.let { put("error", it) }
        }
    }

    private suspend fun runAgentStreaming(
        userMessage: String,
        existingSessionId: String?,
        outgoing: Channel<String>,
        onApprovalDeferred: (CompletableDeferred<Boolean>) -> Unit,
    ) {
        val sid = existingSessionId ?: app.sessionManager.createSession(
            model = app.securePrefs.selectedModel.value,
        ).id

        outgoing.send(buildJsonObject { put("type", "session"); put("session_id", sid) }.toString())

        app.sessionManager.addMessage(sid, MessageRole.USER, userMessage)

        val conversationHistory = buildConversationHistory(sid)
        val agentLoop = buildAgentLoop()
        val fullText = StringBuilder()

        agentLoop.run(userMessage, conversationHistory, object : AgentLoop.Callbacks {
            override fun onToken(text: String) {
                fullText.append(text)
                outgoing.trySend(buildJsonObject {
                    put("type", "token")
                    put("text", text)
                }.toString())
            }

            override fun onToolExecution(toolName: String) {
                outgoing.trySend(buildJsonObject {
                    put("type", "tool_start")
                    put("name", toolName)
                }.toString())
            }

            override fun onToolResult(toolName: String, result: SkillResult) {
                val (data, isError) = when (result) {
                    is SkillResult.Success -> result.data to false
                    is SkillResult.Error -> result.message to true
                    is SkillResult.RequiresApproval -> result.description to false
                }
                app.sessionManager.let { /* tool results persisted by agent loop context */ }
                outgoing.trySend(buildJsonObject {
                    put("type", "tool_result")
                    put("name", toolName)
                    put("data", data)
                    put("is_error", isError)
                }.toString())
            }

            override suspend fun onApprovalNeeded(description: String): Boolean {
                if (app.securePrefs.yoloMode.value) return true
                val deferred = CompletableDeferred<Boolean>()
                onApprovalDeferred(deferred)
                outgoing.send(buildJsonObject {
                    put("type", "approval_needed")
                    put("description", description)
                }.toString())
                return deferred.await()
            }

            override suspend fun onPermissionsNeeded(permissions: List<String>): Boolean {
                val requester = app.permissionRequester ?: return false
                return try {
                    requester.requestIfMissing(permissions).values.all { it }
                } catch (_: Exception) { false }
            }

            override fun onComplete(fullText: String) {
                outgoing.trySend(buildJsonObject {
                    put("type", "complete")
                    put("full_text", fullText)
                }.toString())
            }

            override fun onError(error: Throwable) {
                outgoing.trySend(buildJsonObject {
                    put("type", "error")
                    put("message", error.message ?: "unknown error")
                }.toString())
            }
        })

        val response = fullText.toString()
        if (response.isNotBlank()) {
            app.sessionManager.addMessage(sid, MessageRole.ASSISTANT, response)
        }
        autoStoreMemory(userMessage)
    }

    private fun buildAgentLoop(): AgentLoop {
        val modelId = app.securePrefs.selectedModel.value
        val model = AnthropicModels.fromModelId(modelId) ?: AnthropicModels.MINIMAX_M25
        return AgentLoop(
            client = app.getLlmClient(),
            skillRegistry = app.nativeSkillRegistry,
            tier = OsCapabilities.currentTier(),
            enabledSkillIds = if (app.securePrefs.yoloMode.value) {
                app.nativeSkillRegistry.getAll().map { it.id }.toSet()
            } else {
                app.securePrefs.enabledSkills.value
            },
            model = model,
            aiName = app.userStoryManager.getAiName(),
            userStory = app.userStoryManager.read(),
            memoryManager = app.memoryManager,
        )
    }

    private suspend fun buildConversationHistory(sessionId: String): List<Message> {
        val messages = app.sessionManager.getMessages(sessionId)
        return messages.dropLast(1).mapNotNull { msg ->
            when (msg.role) {
                MessageRole.USER -> Message.user(msg.content)
                MessageRole.ASSISTANT -> Message.assistant(listOf(ContentBlock.TextBlock(msg.content)))
                else -> null
            }
        }
    }

    private fun autoStoreMemory(userText: String) {
        val autoStoreEnabled = app.securePrefs.getString("memory.autoStore") != "false"
        if (!autoStoreEnabled || userText.length < 20) return
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                app.memoryManager.store(
                    content = userText.take(500),
                    source = MemorySource.CONVERSATION,
                    tags = listOf("conversation", "cli"),
                    importance = 0.3f,
                )
            } catch (_: Exception) {}
        }
    }

    private fun errorJson(message: String): String {
        return buildJsonObject {
            put("type", "error")
            put("message", message)
        }.toString()
    }
}
