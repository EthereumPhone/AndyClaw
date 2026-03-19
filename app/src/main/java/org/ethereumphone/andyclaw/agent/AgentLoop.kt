package org.ethereumphone.andyclaw.agent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.LlmClient
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.ImageSource
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.llm.ToolResultContent
import org.ethereumphone.andyclaw.llm.MessageContent
import org.ethereumphone.andyclaw.llm.MessagesRequest
import org.ethereumphone.andyclaw.llm.MessagesResponse
import org.ethereumphone.andyclaw.llm.StreamingCallback
import org.ethereumphone.andyclaw.llm.Verbosity
import org.ethereumphone.andyclaw.memory.MemoryManager
import org.ethereumphone.andyclaw.safety.SafetyLayer
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.PromptAssembler
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.RoutingBudget
import org.ethereumphone.andyclaw.skills.RoutingResult
import org.ethereumphone.andyclaw.skills.SmartRouter
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
    private val safetyLayer: SafetyLayer? = null,
    private val smartRouter: SmartRouter? = null,
    private val budgetConfig: BudgetConfig? = null,
) {
    companion object {
        private const val TAG = "AgentLoop"
        private const val MAX_ITERATIONS = 100
        private const val KEEP_RECENT_IMAGES = 2
        private const val MEMORY_CONTEXT_MAX_RESULTS = 3
        private const val MEMORY_CONTEXT_MIN_SCORE = 0.25f

        /**
         * Strip image data from older tool results, keeping only the [keep] most
         * recent image-bearing results. Older images are replaced with a text-only
         * placeholder so the LLM still knows a screenshot was taken.
         *
         * Mirrors Anthropic's `only_n_most_recent_images` strategy from their
         * computer-use reference implementation.
         */
        fun pruneOldImages(messages: MutableList<Message>, keep: Int = KEEP_RECENT_IMAGES) {
            // Collect indices of messages that contain image-bearing tool results.
            data class ImageLocation(val msgIndex: Int, val blockIndex: Int)

            val locations = mutableListOf<ImageLocation>()
            for ((mi, msg) in messages.withIndex()) {
                val blocks = (msg.content as? MessageContent.Blocks)?.blocks ?: continue
                for ((bi, block) in blocks.withIndex()) {
                    if (block is ContentBlock.ToolResult && block.contentBlocks != null) {
                        locations.add(ImageLocation(mi, bi))
                    }
                }
            }

            Log.d("AGENT_VIRTUAL_SCREEN", "pruneOldImages: found ${locations.size} image-bearing tool results, keep=$keep")
            if (locations.size <= keep) {
                Log.d("AGENT_VIRTUAL_SCREEN", "pruneOldImages: nothing to strip (${locations.size} <= $keep)")
                return
            }

            // Keep the last `keep` entries, strip the rest.
            val toStrip = locations.dropLast(keep)
            Log.i("AGENT_VIRTUAL_SCREEN", "pruneOldImages: STRIPPING ${toStrip.size} old image(s), keeping $keep most recent")
            // Group by message index so we rebuild each affected message once.
            for ((msgIndex, locs) in toStrip.groupBy { it.msgIndex }) {
                val msg = messages[msgIndex]
                val blocks = (msg.content as MessageContent.Blocks).blocks.toMutableList()
                val stripSet = locs.map { it.blockIndex }.toSet()
                for (bi in stripSet) {
                    val tr = blocks[bi] as ContentBlock.ToolResult
                    val base64Len = tr.contentBlocks?.filterIsInstance<ToolResultContent.Image>()?.sumOf { it.source.data.length } ?: 0
                    Log.d("AGENT_VIRTUAL_SCREEN", "pruneOldImages: stripping image at msg[$msgIndex] block[$bi] base64Len=$base64Len")
                    blocks[bi] = tr.copy(
                        content = tr.content + " [image removed from history to save bandwidth]",
                        contentBlocks = null,
                    )
                }
                messages[msgIndex] = msg.copy(content = MessageContent.Blocks(blocks))
            }
            Log.d(TAG, "pruneOldImages: stripped ${toStrip.size} old image(s), kept $keep most recent")
        }
    }

    interface Callbacks {
        fun onToken(text: String)
        fun onToolExecution(toolName: String)
        fun onToolResult(toolName: String, result: SkillResult, input: JsonObject? = null)
        fun onSecurityBlock(toolName: String, reason: String) {}
        suspend fun onApprovalNeeded(
            description: String,
            toolName: String? = null,
            toolInput: JsonObject? = null,
        ): Boolean
        suspend fun onPermissionsNeeded(permissions: List<String>): Boolean
        fun onComplete(fullText: String)
        fun onError(error: Throwable)
    }

    /**
     * Wraps tool output for the LLM, using untrusted-content boundaries
     * for web-sourced tools when safety is enabled.
     */
    private fun wrapOutput(
        safety: SafetyLayer,
        toolName: String,
        content: String,
        wasSanitized: Boolean,
        toolInput: JsonObject,
    ): String {
        return if (safety.isUntrustedTool(toolName)) {
            val sourceUrl = toolInput["url"]?.jsonPrimitive?.contentOrNull
                ?: toolInput["query"]?.jsonPrimitive?.contentOrNull
                ?: "unknown"
            safety.wrapUntrustedForLlm(toolName, content, sourceUrl)
        } else {
            safety.wrapForLlm(toolName, content, wasSanitized)
        }
    }

    suspend fun run(userMessage: String, conversationHistory: List<Message>, callbacks: Callbacks) {
        val safety = safetyLayer

        // Scan inbound message for secrets when safety is enabled
        if (safety != null) {
            val inboundCheck = safety.scanInboundForSecrets(userMessage)
            if (inboundCheck.isFailure) {
                callbacks.onError(inboundCheck.exceptionOrNull()!!)
                return
            }
        }

        // Route to minimal skill set based on user message + conversation context.
        // The runtime tool guard (below) still uses the full enabledSkillIds so
        // multi-turn tool calls from previously-routed skills still work.
        val previousToolNames = conversationHistory
            .filter { it.role == "assistant" }
            .takeLast(3)
            .flatMap { msg ->
                (msg.content as? MessageContent.Blocks)?.blocks
                    ?.filterIsInstance<ContentBlock.ToolUseBlock>()
                    ?.map { it.name }
                    ?: emptyList()
            }.toSet()
        val routingResult = smartRouter?.routeSkillsWithLlm(userMessage, enabledSkillIds, tier, previousToolNames)
        val routedSkillIds = routingResult?.skillIds ?: enabledSkillIds
        val allowedTools = routingResult?.allowedTools
        val routerBudget = routingResult?.budget
        val modelIdOverride = routingResult?.modelIdOverride
        val maxTokensOverride = routingResult?.maxTokensOverride
        val skills = skillRegistry.getEnabled(routedSkillIds)
        Log.d(TAG, "TokenStats | routed ${routedSkillIds.size}/${enabledSkillIds.size} skills" +
            (allowedTools?.let { ", ${it.size} tools filtered" } ?: ", no tool filtering"))

        // Search memory for relevant context to inject into the system prompt.
        val memoryContext = fetchMemoryContext(userMessage)

        val budget = budgetConfig
        val systemPrompt = buildString {
            append(PromptAssembler.assembleSystemPrompt(
                skills, tier, aiName, userStory,
                safetyEnabled = safety?.config?.enabled == true,
                sessionNonce = safety?.sessionNonce,
                concisePrompt = budget?.preset?.concisePrompt == true,
                parallelToolCalls = budget?.preset?.parallelToolCalls == true,
                noPreambleToolCalls = budget?.preset?.noPreambleToolCalls == true,
            ))
            if (memoryContext.isNotBlank()) {
                appendLine()
                appendLine("## Relevant Memories")
                appendLine("The following context was retrieved from long-term memory and may be relevant:")
                appendLine()
                append(memoryContext)
                appendLine()
            }
        }

        val nameResolver: (String, String) -> String = { skillId, name ->
            skillRegistry.getEffectiveName(skillId, name)
        }
        Log.d(TAG, "TokenStats | systemPrompt=${systemPrompt.length} chars, ~${systemPrompt.length / 4} tokens (est)")
        val allToolsJson = PromptAssembler.assembleTools(skills, tier, nameResolver, allowedTools)
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
        var totalInputTokens = 0
        var totalOutputTokens = 0
        var totalCacheReadTokens = 0
        var totalCacheWriteTokens = 0
        var totalTokensSavedByMaxTokens = 0
        var totalCharsTruncated = 0
        var truncationCount = 0

        // Resolve effective model ID and max tokens (model routing may override)
        val effectiveModelId = modelIdOverride ?: model.modelId
        val baseMaxTokens = maxTokensOverride ?: model.maxTokens
        if (modelIdOverride != null) {
            Log.i(TAG, "ModelRouting | override active: $modelIdOverride (default was ${model.modelId}), maxTokens=$baseMaxTokens (default was ${model.maxTokens})")
        }

        try {
            Log.i(TAG, "=== AgentLoop.run starting === model=$effectiveModelId" +
                (if (modelIdOverride != null) " [ROUTED from ${model.modelId}]" else "") +
                ", maxTokens=$baseMaxTokens, toolsJson=${toolsJson.size}, historySize=${conversationHistory.size}")
            if (budget != null) {
                val p = budget.preset
                val active = listOfNotNull(
                    if (p.dynamicMaxTokens) "dynamicMaxTokens" else null,
                    if (p.concisePrompt) "concisePrompt" else null,
                    if (p.parallelToolCalls) "parallelToolCalls" else null,
                    if (p.noPreambleToolCalls) "noPreambleToolCalls" else null,
                    if (p.historySummarization) "historySummarization" else null,
                    if (p.thinkingBudget) "thinkingBudget" else null,
                    if (p.toolResultTruncation) "toolResultTruncation(${p.toolResultMaxChars})" else null,
                )
                Log.i(TAG, "BudgetMode | preset=${p.name} (${p.id}), routerBudget=$routerBudget, active=[${active.joinToString()}]")
            } else {
                Log.i(TAG, "BudgetMode | disabled")
            }

            while (iterations < MAX_ITERATIONS) {
                iterations++
                Log.i(TAG, "--- AgentLoop iteration $iterations/$MAX_ITERATIONS ---")

                pruneOldImages(messages)

                val effectiveMaxTokens = budget?.effectiveMaxTokens(
                    modelDefault = baseMaxTokens,
                    iteration = iterations,
                    routerBudget = routerBudget,
                ) ?: baseMaxTokens
                if (effectiveMaxTokens < baseMaxTokens) {
                    totalTokensSavedByMaxTokens += baseMaxTokens - effectiveMaxTokens
                }

                // Map budget concisePrompt to verbosity parameter
                val verbosity = if (budget?.preset?.concisePrompt == true) {
                    Verbosity.LOW
                } else null

                val request = MessagesRequest(
                    model = effectiveModelId,
                    maxTokens = effectiveMaxTokens,
                    system = systemPrompt,
                    messages = messages,
                    tools = toolsJson.takeIf { it.isNotEmpty() },
                    stream = true,
                    parallelToolCalls = budget?.preset?.parallelToolCalls != false,
                    verbosity = verbosity,
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
                        response.usage?.let { u ->
                            totalInputTokens += u.inputTokens
                            totalOutputTokens += u.outputTokens
                            totalCacheReadTokens += u.cacheReadTokens
                            totalCacheWriteTokens += u.cacheWriteTokens
                            val cacheInfo = if (u.cacheReadTokens > 0 || u.cacheWriteTokens > 0) {
                                " cache_read=${u.cacheReadTokens} cache_write=${u.cacheWriteTokens}"
                            } else ""
                            Log.d(TAG, "TokenStats | input=${u.inputTokens} output=${u.outputTokens} total=${u.inputTokens + u.outputTokens} iteration=$iterations tools=${toolsJson.size} maxTokens=$effectiveMaxTokens$cacheInfo")
                        }
                    }

                    override fun onError(error: Throwable) {
                        callbacks.onError(error)
                    }
                }

                Log.i(TAG, "Sending streaming request to LLM (iteration $iterations, messages=${messages.size})...")
                val iterStartMs = System.currentTimeMillis()
                client.streamMessage(request, streamCallback)
                val iterElapsedMs = System.currentTimeMillis() - iterStartMs
                Log.i(TAG, "LLM stream complete (iteration $iterations): ${iterElapsedMs}ms, ${streamText.length} chars streamed, ${responseBlocks.size} content blocks")

                // Scan LLM response for leaked secrets before displaying
                if (safety != null && streamText.isNotEmpty()) {
                    val responseCheck = safety.scanLlmResponse(streamText.toString())
                    if (responseCheck.isBlocked) {
                        Log.w(TAG, "LLM response blocked by safety: ${responseCheck.blockedReason}")
                    }
                    for (warning in responseCheck.warnings) {
                        Log.w(TAG, "Safety warning in LLM response: $warning")
                    }
                }

                // Add assistant message to conversation
                if (responseBlocks.isNotEmpty()) {
                    messages.add(Message.assistant(responseBlocks))
                }

                // Check for tool_use blocks
                val toolUseBlocks = responseBlocks.filterIsInstance<ContentBlock.ToolUseBlock>()
                if (toolUseBlocks.isEmpty()) {
                    Log.i(TAG, "No tool calls in response, agent loop complete after $iterations iteration(s). Total text: ${fullText.length} chars")
                    logRunSummary(iterations, totalInputTokens, totalOutputTokens, totalCacheReadTokens, totalCacheWriteTokens, totalTokensSavedByMaxTokens, totalCharsTruncated, truncationCount, budget)
                    callbacks.onComplete(fullText.toString())
                    return
                }
                Log.i(TAG, "LLM requested ${toolUseBlocks.size} tool call(s): ${toolUseBlocks.joinToString { it.name }}")

                // Execute each tool call
                val toolResults = mutableListOf<ContentBlock>()
                for (toolUse in toolUseBlocks) {
                    callbacks.onToolExecution(toolUse.name)

                    // Rate limit check (when safety is enabled)
                    if (safety != null) {
                        val rateLimitMsg = safety.checkRateLimit(toolUse.name)
                        if (rateLimitMsg != null) {
                            callbacks.onSecurityBlock(toolUse.name, rateLimitMsg)
                            toolResults.add(
                                ContentBlock.ToolResult(
                                    toolUseId = toolUse.id,
                                    content = rateLimitMsg,
                                    isError = true,
                                )
                            )
                            continue
                        }
                    }

                    // Validate tool parameters (when safety is enabled)
                    if (safety != null) {
                        val paramValidation = safety.validator.validateToolParams(toolUse.input)
                        if (!paramValidation.isValid) {
                            val reasons = paramValidation.errors.joinToString("; ") { it.message }
                            val msg = "[Safety] Tool '${toolUse.name}' parameters rejected: $reasons. " +
                                    "Disable safety mode in Settings to bypass this check."
                            callbacks.onSecurityBlock(toolUse.name, msg)
                            toolResults.add(
                                ContentBlock.ToolResult(
                                    toolUseId = toolUse.id,
                                    content = msg,
                                    isError = true,
                                )
                            )
                            continue
                        }
                    }

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
                            callbacks.onToolResult(toolUse.name, SkillResult.Error("Permissions not granted"), toolUse.input)
                            continue
                        }
                    }

                    // Check if tool requires approval
                    if (toolDef?.requiresApproval == true) {
                        val approved = callbacks.onApprovalNeeded(
                            description = "Tool '${toolUse.name}' requires your approval to execute.",
                            toolName = toolUse.name,
                            toolInput = toolUse.input,
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
                        callbacks.onToolResult(toolUse.name, disabledResult, toolUse.input)
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

                    val toolResult = when (result) {
                        is SkillResult.Success -> {
                            Log.d("AGENT_VIRTUAL_SCREEN", "AgentLoop: tool=${toolUse.name} → Success, dataLen=${result.data.length}")
                            val safetyResult = safety?.sanitizeToolOutput(toolUse.name, result.data)
                            if (safetyResult != null && safetyResult.isBlocked) {
                                callbacks.onSecurityBlock(toolUse.name, safetyResult.blockedReason ?: safetyResult.output)
                                ContentBlock.ToolResult(
                                    toolUseId = toolUse.id,
                                    content = safetyResult.output,
                                    isError = true,
                                )
                            } else {
                                val finalContent = if (safetyResult != null) {
                                    for (w in safetyResult.warnings) Log.w(TAG, "Safety warning for '${toolUse.name}': $w")
                                    wrapOutput(safety, toolUse.name, safetyResult.output, safetyResult.wasModified, toolUse.input)
                                } else {
                                    result.data
                                }
                                callbacks.onToolResult(toolUse.name, SkillResult.Success(finalContent), toolUse.input)
                                ContentBlock.ToolResult(
                                    toolUseId = toolUse.id,
                                    content = finalContent,
                                    isError = false,
                                )
                            }
                        }
                        is SkillResult.ImageSuccess -> {
                            Log.i("AGENT_VIRTUAL_SCREEN", "AgentLoop: tool=${toolUse.name} → ImageSuccess, base64Len=${result.base64.length}, mediaType=${result.mediaType}, textLen=${result.text.length}")
                            val safetyResult = safety?.sanitizeToolOutput(toolUse.name, result.text)
                            if (safetyResult != null && safetyResult.isBlocked) {
                                callbacks.onSecurityBlock(toolUse.name, safetyResult.blockedReason ?: safetyResult.output)
                                ContentBlock.ToolResult(
                                    toolUseId = toolUse.id,
                                    content = safetyResult.output,
                                    isError = true,
                                )
                            } else {
                                val finalText = if (safetyResult != null) {
                                    for (w in safetyResult.warnings) Log.w(TAG, "Safety warning for '${toolUse.name}': $w")
                                    wrapOutput(safety, toolUse.name, safetyResult.output, safetyResult.wasModified, toolUse.input)
                                } else {
                                    result.text
                                }
                                callbacks.onToolResult(toolUse.name, result, toolUse.input)
                                ContentBlock.ToolResult(
                                    toolUseId = toolUse.id,
                                    content = finalText,
                                    isError = false,
                                    contentBlocks = listOf(
                                        ToolResultContent.Text(finalText),
                                        ToolResultContent.Image(ImageSource(
                                            mediaType = result.mediaType,
                                            data = result.base64,
                                        )),
                                    ),
                                )
                            }
                        }
                        is SkillResult.Error -> {
                            if (result.message.startsWith("[Safety]")) {
                                callbacks.onSecurityBlock(toolUse.name, result.message)
                            } else {
                                callbacks.onToolResult(toolUse.name, result, toolUse.input)
                            }
                            ContentBlock.ToolResult(
                                toolUseId = toolUse.id,
                                content = result.message,
                                isError = true,
                            )
                        }
                        is SkillResult.RequiresApproval -> {
                            callbacks.onToolResult(toolUse.name, result, toolUse.input)
                            val approved = callbacks.onApprovalNeeded(
                                description = result.description,
                                toolName = toolUse.name,
                                toolInput = toolUse.input,
                            )
                            if (approved) {
                                val retryResult = skillRegistry.executeTool(toolUse.name, toolUse.input, tier)
                                when (retryResult) {
                                    is SkillResult.Success -> {
                                        val sr = safety?.sanitizeToolOutput(toolUse.name, retryResult.data)
                                        if (sr != null && sr.isBlocked) {
                                            callbacks.onSecurityBlock(toolUse.name, sr.blockedReason ?: sr.output)
                                            ContentBlock.ToolResult(toolUseId = toolUse.id, content = sr.output, isError = true)
                                        } else {
                                            val fc = if (sr != null) wrapOutput(safety, toolUse.name, sr.output, sr.wasModified, toolUse.input) else retryResult.data
                                            callbacks.onToolResult(toolUse.name, SkillResult.Success(fc), toolUse.input)
                                            ContentBlock.ToolResult(toolUseId = toolUse.id, content = fc, isError = false)
                                        }
                                    }
                                    is SkillResult.ImageSuccess -> {
                                        val sr = safety?.sanitizeToolOutput(toolUse.name, retryResult.text)
                                        if (sr != null && sr.isBlocked) {
                                            callbacks.onSecurityBlock(toolUse.name, sr.blockedReason ?: sr.output)
                                            ContentBlock.ToolResult(toolUseId = toolUse.id, content = sr.output, isError = true)
                                        } else {
                                            val ft = if (sr != null) wrapOutput(safety, toolUse.name, sr.output, sr.wasModified, toolUse.input) else retryResult.text
                                            callbacks.onToolResult(toolUse.name, retryResult, toolUse.input)
                                            ContentBlock.ToolResult(
                                                toolUseId = toolUse.id, content = ft, isError = false,
                                                contentBlocks = listOf(
                                                    ToolResultContent.Text(ft),
                                                    ToolResultContent.Image(ImageSource(mediaType = retryResult.mediaType, data = retryResult.base64)),
                                                ),
                                            )
                                        }
                                    }
                                    is SkillResult.Error -> {
                                        if (retryResult.message.startsWith("[Safety]")) {
                                            callbacks.onSecurityBlock(toolUse.name, retryResult.message)
                                        } else {
                                            callbacks.onToolResult(toolUse.name, retryResult, toolUse.input)
                                        }
                                        ContentBlock.ToolResult(toolUseId = toolUse.id, content = retryResult.message, isError = true)
                                    }
                                    is SkillResult.RequiresApproval -> ContentBlock.ToolResult(
                                        toolUseId = toolUse.id,
                                        content = "Approval required but not granted.",
                                        isError = true,
                                    )
                                }
                            } else {
                                ContentBlock.ToolResult(
                                    toolUseId = toolUse.id,
                                    content = "User denied approval.",
                                    isError = true,
                                )
                            }
                        }
                    }

                    toolResults.add(toolResult)
                }

                // Apply budget tool-result truncation (text only, not images)
                val finalToolResults = if (budget != null) {
                    toolResults.map { block ->
                        val tr = block as? ContentBlock.ToolResult ?: return@map block
                        if (tr.isError || tr.contentBlocks != null) return@map block
                        val originalLen = tr.content.length
                        val truncated = budget.truncateToolResult(tr.content)
                        if (truncated.length < originalLen) {
                            val saved = originalLen - truncated.length
                            totalCharsTruncated += saved
                            truncationCount++
                            Log.d(TAG, "BudgetMode | truncated tool result '${tr.toolUseId}': $originalLen -> ${truncated.length} chars (-$saved)")
                        }
                        tr.copy(content = truncated)
                    }
                } else {
                    toolResults
                }

                // Add tool results as user message
                val imageCount = finalToolResults.count { (it as? ContentBlock.ToolResult)?.contentBlocks != null }
                val totalBase64 = finalToolResults.sumOf { block ->
                    (block as? ContentBlock.ToolResult)?.contentBlocks
                        ?.filterIsInstance<ToolResultContent.Image>()
                        ?.sumOf { it.source.data.length } ?: 0
                }
                Log.i("AGENT_VIRTUAL_SCREEN", "AgentLoop: adding ${finalToolResults.size} tool results as user message, imageCount=$imageCount, totalBase64Chars=$totalBase64")
                messages.add(Message("user", MessageContent.Blocks(finalToolResults)))
            }

            // Max iterations reached
            logRunSummary(iterations, totalInputTokens, totalOutputTokens, totalCacheReadTokens, totalCacheWriteTokens, totalTokensSavedByMaxTokens, totalCharsTruncated, truncationCount, budget)
            callbacks.onComplete(fullText.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            callbacks.onError(e)
        } finally {
            // Release any resources skills may still hold (e.g. a virtual display
            // that the LLM never destroyed because of a crash or cancellation).
            try {
                skillRegistry.cleanupAll()
            } catch (e: Exception) {
                Log.w(TAG, "cleanupAll failed: ${e.message}", e)
            }
        }
    }

    private fun logRunSummary(
        iterations: Int,
        totalInput: Int,
        totalOutput: Int,
        cacheRead: Int,
        cacheWrite: Int,
        maxTokensSaved: Int,
        charsTruncated: Int,
        truncations: Int,
        budget: BudgetConfig?,
    ) {
        val total = totalInput + totalOutput
        Log.i(TAG, "=== AgentLoop SUMMARY === iterations=$iterations | input=$totalInput output=$totalOutput total=$total")
        if (cacheRead > 0 || cacheWrite > 0) {
            Log.i(TAG, "  cache: read=$cacheRead write=$cacheWrite (saved ~${(cacheRead * 0.9f).toInt()} input tokens at 90% discount)")
        }
        if (maxTokensSaved > 0) {
            Log.i(TAG, "  dynamicMaxTokens: reduced output budget by $maxTokensSaved tokens across $iterations iteration(s)")
        }
        if (truncations > 0) {
            Log.i(TAG, "  toolResultTruncation: truncated $truncations tool result(s), removed $charsTruncated chars (~${charsTruncated / 4} tokens est)")
        }
        if (budget != null) {
            val p = budget.preset
            val promptExtras = listOfNotNull(
                if (p.concisePrompt) "concisePrompt" else null,
                if (p.parallelToolCalls) "parallelToolCalls" else null,
                if (p.noPreambleToolCalls) "noPreambleToolCalls" else null,
            )
            if (promptExtras.isNotEmpty()) {
                Log.i(TAG, "  promptOptimizations: [${promptExtras.joinToString()}] (active in system prompt)")
            }
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
