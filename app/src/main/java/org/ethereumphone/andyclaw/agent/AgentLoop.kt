package org.ethereumphone.andyclaw.agent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.LlmClient
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.llm.ToolResultContent
import org.ethereumphone.andyclaw.llm.MessageContent
import org.ethereumphone.andyclaw.llm.MessagesRequest
import org.ethereumphone.andyclaw.llm.MessagesResponse
import org.ethereumphone.andyclaw.llm.StreamingCallback
import org.ethereumphone.andyclaw.llm.Verbosity
import org.ethereumphone.andyclaw.memory.MemoryManager
import org.ethereumphone.andyclaw.safety.SafetyLayer
import org.ethereumphone.andyclaw.skills.MessageClassifier
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.PromptAssembler
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.RoutingResult
import org.ethereumphone.andyclaw.skills.RoutingBudget
import org.ethereumphone.andyclaw.skills.SmartRouter
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolSearchService

class AgentLoop(
    private val client: LlmClient,
    private val skillRegistry: NativeSkillRegistry,
    private val tier: Tier,
    private val enabledSkillIds: Set<String> = emptySet(),
    private val model: AnthropicModels = AnthropicModels.MINIMAX_M25,
    private val aiName: String? = null,
    private val userStory: String? = null,
    private val soulContent: String? = null,
    private val memoryManager: MemoryManager? = null,
    private val safetyLayer: SafetyLayer? = null,
    private val smartRouter: SmartRouter? = null,
    private val toolSearchService: ToolSearchService? = null,
    private val budgetConfig: BudgetConfig? = null,
    private val responseLanguage: String? = null,
) {
    companion object {
        private const val TAG = "AgentLoop"
        private const val MAX_ITERATIONS = 100
        private const val DEFAULT_SUBAGENT_ITERATIONS = 30
        private const val MAX_SUBAGENT_ITERATIONS = 50
        private const val KEEP_RECENT_IMAGES = 2
        private const val MEMORY_CONTEXT_MAX_RESULTS = 3
        private const val MEMORY_CONTEXT_MIN_SCORE = 0.25f
        internal const val SPAWN_SUBAGENT_TOOL_NAME = "spawn_subagent"
        internal const val ASK_USER_TOOL_NAME = "ask_user"

        /**
         * Builds the spawn_subagent tool JSON for the LLM tool list.
         * The model calls this tool when it decides to delegate a subtask to
         * a focused sub-agent with its own routing and tool set.
         */
        fun buildSpawnSubagentToolJson(): JsonObject = buildJsonObject {
            put("name", SPAWN_SUBAGENT_TOOL_NAME)
            put("description", buildString {
                // Core purpose
                append("Delegate a subtask to a focused sub-agent with its own tools. ")
                append("Each sub-agent gets independently routed tools and an isolated context. ")
                append("Call multiple times in one response to run sub-agents in parallel.\n\n")
                // When to use — two valid triggers
                append("USE when EITHER condition is met:\n\n")
                append("1. PARALLEL INDEPENDENT TASKS: The request contains 2+ independent tasks needing ")
                append("different skill domains that can run in parallel (no task depends on another's result) ")
                append("and you do NOT already have the tools needed for both.\n\n")
                append("2. CONTEXT-HEAVY TASKS: A task will generate large intermediate data (screenshots, ")
                append("UI trees, long documents) that would pollute your context window. The sub-agent ")
                append("handles the heavy work and returns only the final result. ")
                append("Prime example: virtual screen / agent_display tasks — navigating apps produces ")
                append("many screenshots and UI dumps that you don't need in your conversation history.\n\n")
                // When NOT to use — critical negative guidance
                append("NEVER use when:\n")
                append("- You can handle it with your current tools in a few calls\n")
                append("- Tasks are sequential steps of one workflow (e.g. look up contact then send SMS)\n")
                append("- Tasks share the same skill set (use parallel tool calls instead)\n")
                append("- The task is simple and won't generate heavy intermediate context\n")
                append("- You are unsure whether to use it (default: do NOT use it)\n\n")
                // Cost awareness
                append("Sub-agents are expensive (extra LLM calls + routing). ")
                append("Parallel tool calls in your own context are always cheaper and faster for lightweight tasks.")
            })
            putJsonObject("input_schema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("task") {
                        put("type", "string")
                        put("description", "Self-contained instruction with all needed context. The sub-agent cannot see your conversation history, so include names, numbers, and details explicitly.")
                    }
                    putJsonObject("max_iterations") {
                        put("type", "integer")
                        put("description", "Max tool-call rounds (default $DEFAULT_SUBAGENT_ITERATIONS, max $MAX_SUBAGENT_ITERATIONS). Increase for tasks that read large documents or require many sequential steps.")
                    }
                }
                putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("task")) }
            }
        }

        /**
         * Builds the ask_user tool JSON for the LLM tool list.
         * The model calls this tool when it needs clarification from the user
         * before proceeding — especially before irreversible actions.
         */
        fun buildAskUserToolJson(): JsonObject = buildJsonObject {
            put("name", ASK_USER_TOOL_NAME)
            put("description", buildString {
                append("Ask the user a clarifying question when you cannot proceed without their answer. ")
                append("Pauses execution until the user responds, then resumes so you can act on the answer ")
                append("in the same turn.\n\n")
                append("This is for BLOCKING ambiguity only — situations where you literally cannot complete ")
                append("the task without more information. If the task is already done or you can make a ")
                append("reasonable choice yourself, just respond with text normally.\n\n")
                append("USE when:\n")
                append("- A tool returned ambiguous results you cannot resolve (e.g. search_contacts returned ")
                append("multiple matches and you don't know which one the user meant)\n")
                append("- You're about to perform an irreversible action and a critical detail is missing ")
                append("(e.g. 'send ETH to Alice' but there are two Alices)\n")
                append("- You need information the user hasn't provided and cannot be looked up with tools\n\n")
                append("NEVER use when:\n")
                append("- The task is already complete ('Anything else?' — just say it in text)\n")
                append("- You can resolve the ambiguity yourself (e.g. only one contact matches)\n")
                append("- The question is optional or nice-to-have, not required to finish the task\n")
                append("- You want confirmation after the work is done — just report the result\n")
                append("- You're running as a background agent (heartbeat) — the tool will return a fallback\n")
            })
            putJsonObject("input_schema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("question") {
                        put("type", "string")
                        put("description", "The question to ask the user. Be specific and concise. If there are a few options, list them.")
                    }
                }
                putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("question")) }
            }
        }

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
        /**
         * Called when the agent needs user clarification mid-loop.
         * Returns the user's answer, or null if unavailable (headless/background).
         * When null is returned, the agent receives a fallback message telling it
         * to proceed with its best judgment.
         */
        suspend fun onAskUser(question: String): String?
        fun onComplete(fullText: String)
        fun onError(error: Throwable)
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
        // Two paths: ToolSearchService (new) or SmartRouter (legacy).
        val routerBudget: RoutingBudget?
        val modelIdOverride: String?
        val maxTokensOverride: Int?
        val skills: List<org.ethereumphone.andyclaw.skills.AndyClawSkill>
        val allowedTools: Set<String>?
        val useToolSearch = toolSearchService != null

        if (useToolSearch) {
            // ── ToolSearch path: model discovers tools on demand ──
            // No budget/model classification — let the model use full defaults.
            // Model tier routing can be added later as a separate concern.
            routerBudget = null
            modelIdOverride = null
            maxTokensOverride = null
            skills = skillRegistry.getEnabled(enabledSkillIds) // all skills for system prompt
            allowedTools = null // not used in ToolSearch path
            Log.d(TAG, "TokenStats | ToolSearch mode: " +
                "discovered=${toolSearchService.getDiscoveredToolNames().size} tools")
        } else {
            // ── Legacy SmartRouter path ──
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
            allowedTools = routingResult?.allowedTools
            routerBudget = routingResult?.budget
            modelIdOverride = routingResult?.modelIdOverride
            maxTokensOverride = routingResult?.maxTokensOverride
            skills = skillRegistry.getEnabled(routedSkillIds)
            Log.d(TAG, "TokenStats | SmartRouter: routed ${routedSkillIds.size}/${enabledSkillIds.size} skills" +
                (allowedTools?.let { ", ${it.size} tools filtered" } ?: ", no tool filtering"))
        }

        // Warm up the search index eagerly so first search doesn't stall
        if (useToolSearch) toolSearchService!!.warmUp()

        // Search memory for relevant context to inject into the system prompt.
        val memoryContext = fetchMemoryContext(userMessage)

        val budget = budgetConfig
        val systemPrompt = buildString {
            // When using ToolSearch, only pass CORE skills for full tool docs in the
            // system prompt. The catalog summary provides a compact one-liner per
            // discoverable skill category — avoids bloating context with 198 tool
            // descriptions the model can't call until it searches.
            val promptSkills = if (useToolSearch) {
                // Include skills that own CORE tools or always-on tools.
                // These get full tool docs in the system prompt; everything else
                // is described via the compact catalog summary.
                val alwaysOnSkillIds = toolSearchService!!.getAlwaysOnSkillIds()
                skillRegistry.getEnabled(alwaysOnSkillIds)
            } else {
                skills
            }
            append(PromptAssembler.assembleSystemPrompt(
                promptSkills, tier, aiName, userStory,
                soulContent = soulContent,
                safetyEnabled = safety?.config?.enabled == true,
                sessionNonce = safety?.sessionNonce,
                concisePrompt = budget?.preset?.concisePrompt == true,
                parallelToolCalls = budget?.preset?.parallelToolCalls == true,
                noPreambleToolCalls = budget?.preset?.noPreambleToolCalls == true,
                responseLanguage = responseLanguage,
            ))
            // Add meta-tool descriptions and catalog summary when using ToolSearch
            if (useToolSearch) {
                appendLine()
                appendLine("### Tool Discovery")
                appendLine("`search_available_tools` — Search for tools you don't have yet. " +
                    "Call this when you need a capability that isn't in your current tool set. " +
                    "Discovered tools remain available for the rest of the conversation.")
                appendLine()
                appendLine("### Sub-Agent Delegation")
                appendLine("`spawn_subagent` — Delegate a subtask to a focused sub-agent with its own tools and context. " +
                    "Use for parallel independent tasks or context-heavy work (e.g. virtual display navigation) " +
                    "that would pollute your conversation history.")
                appendLine()
                appendLine("### User Clarification")
                appendLine("`ask_user` — Ask the user a blocking clarifying question mid-turn when you cannot " +
                    "proceed without their answer (e.g. multiple contact matches, missing critical detail for an " +
                    "irreversible action). Pauses execution and resumes when they answer, so you can act immediately. " +
                    "Only for blocking ambiguity — if the task is done or the question is optional, use normal text.")
                appendLine()
                append(toolSearchService!!.buildCatalogSummary())
            }
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

        // Build tool list: ToolSearch (CORE + discovered + search tool) or SmartRouter (filtered)
        var allToolsJson = if (useToolSearch) {
            toolSearchService!!.buildToolList(nameResolver).toMutableList()
        } else {
            PromptAssembler.assembleTools(skills, tier, nameResolver, allowedTools).toMutableList()
        }
        // Add meta-tools: spawn_subagent, ask_user
        if (smartRouter != null || useToolSearch) {
            allToolsJson.add(buildSpawnSubagentToolJson())
        }
        allToolsJson.add(buildAskUserToolJson())
        var toolsJson = if (client.maxToolCount > 0 && allToolsJson.size > client.maxToolCount) {
            Log.d(TAG, "Trimming tools from ${allToolsJson.size} to ${client.maxToolCount} for constrained provider")
            allToolsJson.take(client.maxToolCount)
        } else {
            allToolsJson.toList()
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

                // Handle search_available_tools calls (ToolSearch mode only)
                val searchCalls = if (useToolSearch) {
                    toolUseBlocks.filter { toolSearchService!!.isSearchTool(it.name) }
                } else emptyList()
                val nonSearchCalls = if (useToolSearch) {
                    toolUseBlocks.filter { !toolSearchService!!.isSearchTool(it.name) }
                } else toolUseBlocks

                // Execute search tool calls first — they expand the available tool set
                val searchResults = mutableListOf<ContentBlock>()
                for (call in searchCalls) {
                    val result = toolSearchService!!.executeSearch(call.input)
                    searchResults.add(ContentBlock.ToolResult(
                        toolUseId = call.id,
                        content = result,
                        isError = false,
                    ))
                }
                // If tools were discovered, rebuild the tool list for subsequent iterations
                if (searchCalls.isNotEmpty() && useToolSearch) {
                    allToolsJson = toolSearchService!!.buildToolList(nameResolver).toMutableList()
                    allToolsJson.add(buildSpawnSubagentToolJson())
                    allToolsJson.add(buildAskUserToolJson())
                    toolsJson = if (client.maxToolCount > 0 && allToolsJson.size > client.maxToolCount) {
                        allToolsJson.take(client.maxToolCount)
                    } else {
                        allToolsJson.toList()
                    }
                    Log.i(TAG, "ToolSearch: rebuilt tool list, now ${toolsJson.size} tools")
                }

                // Separate meta-tool calls from regular tool calls
                val askUserCalls = nonSearchCalls.filter { it.name == ASK_USER_TOOL_NAME }
                val afterAskUser = nonSearchCalls.filter { it.name != ASK_USER_TOOL_NAME }
                val subagentCalls = afterAskUser.filter { it.name == SPAWN_SUBAGENT_TOOL_NAME }
                val regularCalls = afterAskUser.filter { it.name != SPAWN_SUBAGENT_TOOL_NAME }

                // Collect all tool results in order
                val allToolResults = mutableListOf<ContentBlock>()
                // Add search results first (they were already executed)
                allToolResults.addAll(searchResults)

                // Handle ask_user calls (blocks on user input, execute before other tools)
                for (call in askUserCalls) {
                    val question = call.input["question"]?.jsonPrimitive?.contentOrNull ?: "Can you clarify?"
                    Log.i(TAG, "ask_user: '$question'")
                    callbacks.onToolExecution(ASK_USER_TOOL_NAME)
                    val answer = callbacks.onAskUser(question)
                    val resultText = answer
                        ?: "User is not available (background/headless mode). Proceed with your best judgment or skip this action."
                    allToolResults.add(ContentBlock.ToolResult(
                        toolUseId = call.id,
                        content = resultText,
                        isError = false,
                    ))
                    Log.i(TAG, "ask_user response: ${resultText.take(200)}")
                }

                coroutineScope {
                    // Regular tools via ExecutionEngine
                    val regularResultsDeferred = if (regularCalls.isNotEmpty()) {
                        async {
                            val engine = ExecutionEngineFactory.create(
                                skillRegistry = skillRegistry,
                                tier = tier,
                                enabledSkillIds = enabledSkillIds,
                                safetyLayer = safety,
                                agentCallbacks = callbacks,
                                budgetConfig = budget,
                            )
                            val engineCalls = ExecutionEngineFactory.toToolCalls(regularCalls)
                            val batchResult = engine.executeBatch(engineCalls)
                            val engineMetrics = batchResult.metrics
                            Log.i(TAG, "ExecutionEngine | ${engineMetrics.executedCount} executed, " +
                                "${engineMetrics.blockedCount} blocked, ${engineMetrics.errorCount} errors, " +
                                "${engineMetrics.totalDurationMs}ms total (max tool: ${engineMetrics.maxToolDurationMs}ms)")
                            ExecutionEngineFactory.toContentBlocks(batchResult.results)
                        }
                    } else null

                    // Sub-agent calls — each runs its own routing + mini agent loop
                    val subagentResultsDeferreds = subagentCalls.map { call ->
                        async {
                            val taskDesc = call.input["task"]?.jsonPrimitive?.contentOrNull ?: "unknown task"
                            val iterLimit = call.input["max_iterations"]?.jsonPrimitive?.contentOrNull
                                ?.toIntOrNull()
                                ?.coerceIn(1, MAX_SUBAGENT_ITERATIONS)
                                ?: DEFAULT_SUBAGENT_ITERATIONS
                            Log.i(TAG, "spawn_subagent: '${taskDesc.take(80)}' (id=${call.id}, maxIter=$iterLimit)")
                            try {
                                val result = runSubagent(taskDesc, conversationHistory, callbacks, iterLimit)
                                ContentBlock.ToolResult(
                                    toolUseId = call.id,
                                    content = result,
                                    isError = false,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "spawn_subagent failed for '${taskDesc.take(60)}': ${e.message}", e)
                                ContentBlock.ToolResult(
                                    toolUseId = call.id,
                                    content = "Sub-agent error: ${e.message}",
                                    isError = true,
                                )
                            }
                        }
                    }

                    // Await all results
                    regularResultsDeferred?.await()?.let { allToolResults.addAll(it) }
                    subagentResultsDeferreds.awaitAll().let { allToolResults.addAll(it) }
                }

                // Add tool results as user message
                val imageCount = allToolResults.count { (it as? ContentBlock.ToolResult)?.contentBlocks != null }
                val totalBase64 = allToolResults.sumOf { block ->
                    (block as? ContentBlock.ToolResult)?.contentBlocks
                        ?.filterIsInstance<ToolResultContent.Image>()
                        ?.sumOf { it.source.data.length } ?: 0
                }
                Log.i("AGENT_VIRTUAL_SCREEN", "AgentLoop: adding ${allToolResults.size} tool results as user message, imageCount=$imageCount, totalBase64Chars=$totalBase64")
                messages.add(Message("user", MessageContent.Blocks(allToolResults)))
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

    // ── Sub-agent execution (model-driven delegation) ──────────────

    /**
     * Runs a sub-agent for a delegated task. The sub-agent gets its own routing
     * via [SmartRouter] or [ToolSearchService] (so it discovers exactly the tools
     * it needs), its own system prompt, and a mini agent loop capped at
     * [maxIterations].
     *
     * Called when the main model invokes the `spawn_subagent` tool.
     */
    private suspend fun runSubagent(
        taskDescription: String,
        conversationHistory: List<Message>,
        callbacks: Callbacks,
        maxIterations: Int = DEFAULT_SUBAGENT_ITERATIONS,
    ): String {
        val nameResolver: (String, String) -> String = { skillId, name ->
            skillRegistry.getEffectiveName(skillId, name)
        }

        val effectiveModelId: String
        val baseMaxTokens: Int
        val subagentSkills: List<org.ethereumphone.andyclaw.skills.AndyClawSkill>
        var subagentToolsJson: List<JsonObject>

        // Sub-agent ToolSearch instance (fresh session, discovers its own tools)
        val subagentToolSearch: ToolSearchService?

        if (toolSearchService != null) {
            // ── ToolSearch path: sub-agent discovers tools independently ──
            subagentToolSearch = ToolSearchService(
                skillRegistry = skillRegistry,
                tier = tier,
                enabledSkillIds = enabledSkillIds,
                presetProvider = null, // sub-agents use default CORE
            )
            effectiveModelId = model.modelId
            baseMaxTokens = model.maxTokens
            subagentSkills = skillRegistry.getEnabled(enabledSkillIds)
            subagentToolsJson = subagentToolSearch.buildToolList(nameResolver)
            Log.i(TAG, "Subagent (ToolSearch): '${taskDescription.take(60)}' -> ${subagentToolsJson.size} tools")
        } else {
            // ── Legacy SmartRouter path ──
            subagentToolSearch = null
            val subagentRouting = smartRouter?.routeSkillsWithLlm(
                taskDescription, enabledSkillIds, tier, emptySet(),
            )
            val subagentSkillIds = subagentRouting?.skillIds ?: enabledSkillIds
            val subagentAllowedTools = subagentRouting?.allowedTools
            subagentSkills = skillRegistry.getEnabled(subagentSkillIds)
            effectiveModelId = subagentRouting?.modelIdOverride ?: model.modelId
            baseMaxTokens = subagentRouting?.maxTokensOverride ?: model.maxTokens
            subagentToolsJson = PromptAssembler.assembleTools(subagentSkills, tier, nameResolver, subagentAllowedTools)
            Log.i(TAG, "Subagent (SmartRouter): '${taskDescription.take(60)}' -> ${subagentSkillIds.size} skills" +
                (subagentAllowedTools?.let { ", ${it.size} tools" } ?: ", all tools"))
        }

        val systemPrompt = buildString {
            append(PromptAssembler.assembleSystemPrompt(
                subagentSkills, tier, aiName, userStory,
                safetyEnabled = safetyLayer?.config?.enabled == true,
                sessionNonce = safetyLayer?.sessionNonce,
                concisePrompt = true,
                parallelToolCalls = true,
                noPreambleToolCalls = true,
                responseLanguage = responseLanguage,
            ))
            if (subagentToolSearch != null) {
                appendLine()
                append(subagentToolSearch.buildCatalogSummary())
            }
            append("\n\nIMPORTANT: Complete ONLY this specific task. Be brief and direct.")
        }

        // Sub-agent gets a copy of conversation history for context + its focused task
        val messages = conversationHistory.toMutableList()
        messages.add(Message.user(taskDescription))

        val fullText = StringBuilder()
        val subagentMaxTokens = (baseMaxTokens / 4).coerceAtLeast(512)

        for (iteration in 1..maxIterations) {
            val request = MessagesRequest(
                model = effectiveModelId,
                maxTokens = subagentMaxTokens,
                system = systemPrompt,
                messages = messages,
                tools = subagentToolsJson.takeIf { it.isNotEmpty() },
                stream = true,
                parallelToolCalls = true,
            )

            val responseBlocks = mutableListOf<ContentBlock>()
            var streamError: Throwable? = null
            client.streamMessage(request, object : StreamingCallback {
                override fun onToken(text: String) { /* buffered, not streamed to user */ }
                override fun onToolUse(id: String, name: String, input: JsonObject) {}
                override fun onComplete(response: MessagesResponse) {
                    responseBlocks.addAll(response.content)
                }
                override fun onError(error: Throwable) { streamError = error }
            })
            streamError?.let { throw it }

            if (responseBlocks.isNotEmpty()) {
                messages.add(Message.assistant(responseBlocks))
            }

            // Collect text output
            responseBlocks.filterIsInstance<ContentBlock.TextBlock>().forEach {
                fullText.append(it.text)
            }

            // Check for tool calls (sub-agents cannot spawn further sub-agents)
            val toolUseBlocks = responseBlocks.filterIsInstance<ContentBlock.ToolUseBlock>()
            if (toolUseBlocks.isEmpty()) break

            Log.i(TAG, "Subagent iteration $iteration: ${toolUseBlocks.size} tool call(s): ${toolUseBlocks.joinToString { it.name }}")

            // Handle search_available_tools in sub-agent context
            val searchResults = mutableListOf<ContentBlock>()
            val execCalls = mutableListOf<ContentBlock.ToolUseBlock>()
            for (call in toolUseBlocks) {
                if (subagentToolSearch?.isSearchTool(call.name) == true) {
                    val result = subagentToolSearch.executeSearch(call.input)
                    searchResults.add(ContentBlock.ToolResult(
                        toolUseId = call.id, content = result, isError = false,
                    ))
                    // Rebuild tool list with newly discovered tools
                    subagentToolsJson = subagentToolSearch.buildToolList(nameResolver)
                } else {
                    execCalls.add(call)
                }
            }

            // Execute regular tools via the standard engine
            val toolResults = mutableListOf<ContentBlock>()
            toolResults.addAll(searchResults)
            if (execCalls.isNotEmpty()) {
                val engine = ExecutionEngineFactory.create(
                    skillRegistry = skillRegistry,
                    tier = tier,
                    enabledSkillIds = enabledSkillIds,
                    safetyLayer = safetyLayer,
                    agentCallbacks = callbacks,
                    budgetConfig = budgetConfig,
                )
                val engineCalls = ExecutionEngineFactory.toToolCalls(execCalls)
                val batchResult = engine.executeBatch(engineCalls)
                toolResults.addAll(ExecutionEngineFactory.toContentBlocks(batchResult.results))
            }

            messages.add(Message("user", MessageContent.Blocks(toolResults)))
        }

        Log.i(TAG, "Subagent complete for '${taskDescription.take(60)}': ${fullText.length} chars")
        return fullText.toString()
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
