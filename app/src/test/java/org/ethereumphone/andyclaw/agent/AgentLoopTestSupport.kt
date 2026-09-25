package org.ethereumphone.andyclaw.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.LlmClient
import org.ethereumphone.andyclaw.llm.MessagesRequest
import org.ethereumphone.andyclaw.llm.MessagesResponse
import org.ethereumphone.andyclaw.llm.StreamingCallback
import org.ethereumphone.andyclaw.llm.Usage
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.skills.ToolEffect

/**
 * A model that plays back one scripted response per call, streaming each tool call through
 * `onToolUse` before `onComplete` the way the real clients do — which is what puts a tool on
 * the streaming executor rather than the fallback path. Past the script it answers "done".
 */
class ScriptedLlmClient(private val responses: List<List<ContentBlock>>) : LlmClient {
    val requests = java.util.Collections.synchronizedList(mutableListOf<MessagesRequest>())

    override suspend fun sendMessage(request: MessagesRequest): MessagesResponse = error("streaming only")

    override suspend fun streamMessage(request: MessagesRequest, callback: StreamingCallback) {
        val content = responses.getOrElse(requests.size) { listOf(ContentBlock.TextBlock("done")) }
        requests += request
        for (block in content) {
            when (block) {
                is ContentBlock.TextBlock -> callback.onToken(block.text)
                is ContentBlock.ToolUseBlock -> callback.onToolUse(block.id, block.name, block.input)
                else -> Unit
            }
        }
        callback.onComplete(
            MessagesResponse(
                id = "msg_${requests.size}",
                type = "message",
                role = "assistant",
                content = content,
                model = request.model,
                stopReason = if (content.any { it is ContentBlock.ToolUseBlock }) "tool_use" else "end_turn",
                usage = Usage(inputTokens = 10, outputTokens = 10),
            )
        )
    }
}

fun toolUse(id: String, name: String, input: JsonObject = JsonObject(emptyMap())) =
    ContentBlock.ToolUseBlock(id, name, input)

fun testTool(name: String, effect: ToolEffect = ToolEffect.READ) = ToolDefinition(
    name = name,
    description = "test tool",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    },
    effect = effect,
)

/** A skill whose every tool runs [run]. */
fun testSkill(
    id: String,
    vararg tools: ToolDefinition,
    run: suspend (tool: String, params: JsonObject) -> SkillResult,
) = object : AndyClawSkill {
    override val id = id
    override val name = id
    override val baseManifest = SkillManifest(description = id, tools = tools.toList())
    override val privilegedManifest: SkillManifest? = null
    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult = run(tool, params)
}

/** Records what a run reported, so a test can assert on how the turn ended. */
open class RecordingCallbacks : AgentLoop.Callbacks {
    val text = StringBuilder()
    val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
    @Volatile var completed: String? = null

    override fun onToken(text: String) { this.text.append(text) }
    override fun onToolExecution(toolName: String) {}
    override fun onToolResult(toolName: String, result: SkillResult, input: JsonObject?) {}
    override suspend fun onApprovalNeeded(description: String, toolName: String?, toolInput: JsonObject?) = true
    override suspend fun onPermissionsNeeded(permissions: List<String>) = true
    override fun onComplete(fullText: String, tokenUsage: TokenUsageSnapshot?) { completed = fullText }
    override fun onError(error: Throwable) { errors += error }
}
