package org.ethereumphone.andyclaw.agent

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import org.ethereumphone.andyclaw.llm.AnthropicClient
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.llm.MessageContent
import org.ethereumphone.andyclaw.llm.MessagesRequest
import org.ethereumphone.andyclaw.llm.MessagesResponse
import org.ethereumphone.andyclaw.llm.StreamingCallback
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.PromptAssembler
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier

class AgentLoop(
    private val client: AnthropicClient,
    private val skillRegistry: NativeSkillRegistry,
    private val tier: Tier,
    private val model: AnthropicModels = AnthropicModels.SONNET_4,
) {
    companion object {
        private const val MAX_ITERATIONS = 20
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
        val skills = skillRegistry.getAll()
        val systemPrompt = PromptAssembler.assembleSystemPrompt(skills, tier)
        val toolsJson = PromptAssembler.assembleTools(skills, tier)

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
}
