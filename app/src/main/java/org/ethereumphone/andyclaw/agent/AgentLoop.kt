package org.ethereumphone.andyclaw.agent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.LlmClient
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.llm.MessageContent
import org.ethereumphone.andyclaw.llm.MessagesRequest
import org.ethereumphone.andyclaw.llm.MessagesResponse
import org.ethereumphone.andyclaw.llm.StreamingCallback
import org.ethereumphone.andyclaw.memory.MemoryManager
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.PromptAssembler
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier

class AgentLoop(
    private val client: LlmClient,
    private val skillRegistry: NativeSkillRegistry,
    private val tier: Tier,
    private val enabledSkillIds: Set<String> = emptySet(),
    private val model: AnthropicModels = AnthropicModels.MINIMAX_M25,
    private val aiName: String? = null,
    private val userStory: String? = null,
    private val memoryManager: MemoryManager? = null,
) {
    companion object {
        private const val TAG = "AgentLoop"
        private const val MAX_ITERATIONS = 20
        private const val MEMORY_CONTEXT_MAX_RESULTS = 3
        private const val MEMORY_CONTEXT_MIN_SCORE = 0.25f
    }

    interface Callbacks {
        fun onToken(text: String)
        fun onToolExecution(toolName: String)
        fun onToolResult(toolName: String, result: SkillResult)
        suspend fun onApprovalNeeded(description: String): Boolean
        suspend fun onPermissionsNeeded(permissions: List<String>): Boolean
        fun onComplete(fullText: String)
        fun onError(error: Throwable)
    }

    suspend fun run(userMessage: String, conversationHistory: List<Message>, callbacks: Callbacks) {
        val skills = skillRegistry.getEnabled(enabledSkillIds)

        // Search memory for relevant context to inject into the system prompt.
        // This mirrors OpenClaw's approach of pre-fetching memory context before
        // the agent turn, giving the model background knowledge without requiring
        // an explicit tool call.
        val memoryContext = fetchMemoryContext(userMessage)

        val systemPrompt = buildString {
            append(PromptAssembler.assembleSystemPrompt(skills, tier, aiName, userStory))
            if (memoryContext.isNotBlank()) {
                appendLine()
                appendLine("## Relevant Memories")
                appendLine("The following context was retrieved from long-term memory and may be relevant:")
                appendLine()
                append(memoryContext)
                appendLine()
            }
        }

        val allToolsJson = PromptAssembler.assembleTools(skills, tier)
        val toolsJson = if (client.maxToolCount > 0 && allToolsJson.size > client.maxToolCount) {
            Log.d(TAG, "Trimming tools from ${allToolsJson.size} to ${client.maxToolCount} for constrained provider")
            allToolsJson.take(client.maxToolCount)
        } else {
            allToolsJson
        }

        val messages = conversationHistory.toMutableList()
        messages.add(Message.user(userMessage))

        var iterations = 0
        val fullText = StringBuilder()

        try {
            while (iterations < MAX_ITERATIONS) {
                iterations++

                val request = MessagesRequest(
                    model = model.modelId,
                    maxTokens = model.maxTokens,
                    system = systemPrompt,
                    messages = messages,
                    tools = toolsJson.takeIf { it.isNotEmpty() },
                    stream = true,
                )

                val responseBlocks = mutableListOf<ContentBlock>()
                val streamText = StringBuilder()

                val streamCallback = object : StreamingCallback {
                    override fun onToken(text: String) {
                        streamText.append(text)
                        fullText.append(text)
                        callbacks.onToken(text)
                    }

                    override fun onToolUse(id: String, name: String, input: JsonObject) {
                        // Collected via onComplete
                    }

                    override fun onComplete(response: MessagesResponse) {
                        responseBlocks.addAll(response.content)
                    }

                    override fun onError(error: Throwable) {
                        callbacks.onError(error)
                    }
                }

                client.streamMessage(request, streamCallback)

                // Add assistant message to conversation
                if (responseBlocks.isNotEmpty()) {
                    messages.add(Message.assistant(responseBlocks))
                }

                // Check for tool_use blocks
                val toolUseBlocks = responseBlocks.filterIsInstance<ContentBlock.ToolUseBlock>()
                if (toolUseBlocks.isEmpty()) {
                    // No tool calls - we're done
                    callbacks.onComplete(fullText.toString())
                    return
                }

                // Execute each tool call
                val toolResults = mutableListOf<ContentBlock>()
                for (toolUse in toolUseBlocks) {
                    callbacks.onToolExecution(toolUse.name)

                    val toolDef = skillRegistry.getTools(tier).find { it.name == toolUse.name }

                    // Check if tool requires Android runtime permissions
                    if (toolDef != null && toolDef.requiredPermissions.isNotEmpty()) {
                        val granted = callbacks.onPermissionsNeeded(toolDef.requiredPermissions)
                        if (!granted) {
                            toolResults.add(
                                ContentBlock.ToolResult(
                                    toolUseId = toolUse.id,
                                    content = "Required Android permissions were not granted: ${toolDef.requiredPermissions.joinToString()}. Ask the user to grant them in device settings.",
                                    isError = true,
                                )
                            )
                            callbacks.onToolResult(toolUse.name, SkillResult.Error("Permissions not granted"))
                            continue
                        }
                    }

                    // Check if tool requires approval
                    if (toolDef?.requiresApproval == true) {
                        val approved = callbacks.onApprovalNeeded(
                            "Tool '${toolUse.name}' requires your approval to execute."
                        )
                        if (!approved) {
                            toolResults.add(
                                ContentBlock.ToolResult(
                                    toolUseId = toolUse.id,
                                    content = "User denied permission to execute this tool.",
                                    isError = true,
                                )
                            )
                            continue
                        }
                    }

                    // Guard: verify the tool's owning skill is enabled
                    val owningSkill = skillRegistry.findSkillForTool(toolUse.name, tier)
                    if (owningSkill != null && owningSkill.id !in enabledSkillIds) {
                        val disabledResult = SkillResult.Error(
                            "Skill '${owningSkill.name}' is disabled. The user must enable it in Settings."
                        )
                        callbacks.onToolResult(toolUse.name, disabledResult)
                        toolResults.add(
                            ContentBlock.ToolResult(
                                toolUseId = toolUse.id,
                                content = disabledResult.message,
                                isError = true,
                            )
                        )
                        continue
                    }

                    val result = skillRegistry.executeTool(toolUse.name, toolUse.input, tier)
                    callbacks.onToolResult(toolUse.name, result)

                    val (content, isError) = when (result) {
                        is SkillResult.Success -> result.data to false
                        is SkillResult.Error -> result.message to true
                        is SkillResult.RequiresApproval -> {
                            val approved = callbacks.onApprovalNeeded(result.description)
                            if (approved) {
                                val retryResult = skillRegistry.executeTool(toolUse.name, toolUse.input, tier)
                                when (retryResult) {
                                    is SkillResult.Success -> retryResult.data to false
                                    is SkillResult.Error -> retryResult.message to true
                                    is SkillResult.RequiresApproval -> "Approval required but not granted." to true
                                }
                            } else {
                                "User denied approval." to true
                            }
                        }
                    }

                    toolResults.add(
                        ContentBlock.ToolResult(
                            toolUseId = toolUse.id,
                            content = content,
                            isError = isError,
                        )
                    )
                }

                // Add tool results as user message
                messages.add(Message("user", MessageContent.Blocks(toolResults)))
            }

            // Max iterations reached
            callbacks.onComplete(fullText.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            callbacks.onError(e)
        }
    }

    /**
     * Search long-term memory for snippets relevant to the user's message.
     *
     * Returns a formatted string suitable for injection into the system prompt,
     * or blank if no relevant memories are found (or no memory manager is set).
     */
    private suspend fun fetchMemoryContext(userMessage: String): String {
        val manager = memoryManager ?: run {
            Log.d(TAG, "fetchMemoryContext: no MemoryManager set, skipping")
            return ""
        }

        return try {
            Log.d(TAG, "fetchMemoryContext: searching for context, query=\"${userMessage.take(80)}\"")
            val results = manager.search(
                query = userMessage,
                maxResults = MEMORY_CONTEXT_MAX_RESULTS,
                minScore = MEMORY_CONTEXT_MIN_SCORE,
            )
            if (results.isEmpty()) {
                Log.d(TAG, "fetchMemoryContext: no relevant memories found")
                return ""
            }

            Log.i(TAG, "fetchMemoryContext: injecting ${results.size} memory/memories into system prompt")
            results.joinToString("\n") { result ->
                buildString {
                    append("- ${result.snippet}")
                    if (result.tags.isNotEmpty()) {
                        append(" [${result.tags.joinToString(", ")}]")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchMemoryContext: search failed: ${e.message}", e)
            ""
        }
    }
}
