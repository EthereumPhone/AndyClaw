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
import org.ethereumphone.andyclaw.llm.AnthropicApiException
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.CannotRetryException
import org.ethereumphone.andyclaw.llm.LlmClient
import org.ethereumphone.andyclaw.llm.LocalLlmClient
import org.ethereumphone.andyclaw.llm.withRetry
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

/**
 * Token usage snapshot returned at the end of an agent loop run.
 * [lastInputTokens] is the prompt token count from the final API call —
 * it represents the current conversation size inside the context window.
 * [totalInputTokens] / [totalOutputTokens] are cumulative across all iterations.
 */
data class TokenUsageSnapshot(
    val lastInputTokens: Int,
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
)

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
    private val compactionConfig: CompactionConfig? = null,
    /** Optional LLM-based memory reranker (opt-in, costs ~500 tokens per query). */
    private val memoryReranker: MemoryReranker? = null,
    /** Raw model id to use in MessagesRequest.model — takes precedence over
     *  [model].modelId and [routingResult.modelIdOverride]. Set this when the
     *  selected model id isn't an entry in [AnthropicModels] (e.g. CUSTOM
     *  provider hitting a self-hosted Ollama/LM Studio backend whose model
     *  ids — `gpt-oss:20b`, `llama3.2:latest`, … — aren't enum-resolvable). */
    private val customModelIdOverride: String? = null,
) {
    companion object {
        private const val TAG = "AgentLoop"
        private const val MAX_ITERATIONS = 100
        private const val DEFAULT_SUBAGENT_ITERATIONS = 30
        private const val MAX_SUBAGENT_ITERATIONS = 50
        private const val KEEP_RECENT_IMAGES = 2
        private const val MEMORY_CONTEXT_MAX_RESULTS = 3
        /** When AI reranking is enabled, fetch more candidates for the LLM to filter. */
        private const val MEMORY_RERANK_CANDIDATE_POOL = 8
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
                append("Ask the user one or more clarifying questions when you cannot proceed without their answer. ")
                append("Supports single-select (pick one), multi-select (pick many), and ranked-choice (order by preference). ")
                append("Pauses execution until the user responds, then resumes so you can act on the answers.\n\n")
                append("This is for BLOCKING ambiguity only — situations where you literally cannot complete ")
                append("the task without more information. If the task is already done or you can make a ")
                append("reasonable choice yourself, just respond with text normally.\n\n")
                append("USE when:\n")
                append("- A tool returned ambiguous results you cannot resolve (e.g. multiple contact matches)\n")
                append("- You're about to perform an irreversible action and a critical detail is missing\n")
                append("- You need information the user hasn't provided and cannot be looked up with tools\n\n")
                append("NEVER use when:\n")
                append("- The task is already complete ('Anything else?' — just say it in text)\n")
                append("- You can resolve the ambiguity yourself (e.g. only one contact matches)\n")
                append("- The question is optional or nice-to-have, not required to finish the task\n")
                append("- You're running as a background agent (heartbeat) — the tool will return a fallback\n")
            })
            putJsonObject("input_schema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("questions") {
                        put("type", "array")
                        put("description", "One or more questions to ask. The user sees all questions and can navigate between them before submitting.")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("question") {
                                    put("type", "string")
                                    put("description", "The question text.")
                                }
                                putJsonObject("type") {
                                    put("type", "string")
                                    putJsonArray("enum") {
                                        add(kotlinx.serialization.json.JsonPrimitive("single_select"))
                                        add(kotlinx.serialization.json.JsonPrimitive("multi_select"))
                                        add(kotlinx.serialization.json.JsonPrimitive("ranked_choice"))
                                    }
                                    put("description", "single_select: pick one option or type custom. multi_select: pick multiple + optional custom entry. ranked_choice: reorder options by preference.")
                                }
                                putJsonObject("options") {
                                    put("type", "array")
                                    putJsonObject("items") { put("type", "string") }
                                    put("description", "Available options. For single_select, the user can also type a custom answer. For multi_select, the user can also add a custom entry.")
                                }
                            }
                            putJsonArray("required") {
                                add(kotlinx.serialization.json.JsonPrimitive("question"))
                            }
                        }
                    }
                }
                putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("questions")) }
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
         * Called when the agent displays questions to the user via ask_user.
         * The tool returns immediately (the turn ends), and the user's answer
         * arrives as the next user message in a new turn.
         */
        fun onAskUserDisplayed(request: AskUserRequest) {}
        fun onComplete(fullText: String, tokenUsage: TokenUsageSnapshot? = null)
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

        // Memory injection: only on first turn (cold start) or after compaction
        // (context loss). Mid-conversation, everything is already in the prompt —
        // the model can call memory_search explicitly if it needs more context.
        val isFirstTurn = conversationHistory.isEmpty()
        val isPostCompaction = conversationHistory.any { msg ->
            val text = extractText(msg)
            text.startsWith("<context_summary>")
        }
        val memoryContext = if (isFirstTurn || isPostCompaction) {
            fetchMemoryContext(userMessage, conversationHistory, isFirstTurn = isFirstTurn)
        } else {
            Log.d(TAG, "Skipping memory injection (turn ${conversationHistory.size / 2 + 1}, not first/post-compact)")
            ""
        }

        val budget = budgetConfig
        val isLocalModel = client is LocalLlmClient
        val systemPrompt = if (isLocalModel) {
            // Minimal prompt for local 1.5B models — tool schemas injected by LocalLlmClient
            buildString {
                append(PromptAssembler.assembleLocalSystemPrompt(aiName))
                // Add compact ToolSearch info so the model knows it can discover more tools
                if (useToolSearch) {
                    appendLine("You have a few tools loaded. If you need a tool you don't have, use search_available_tools to find it.")
                    appendLine("Once discovered, new tools stay available for the rest of the conversation.")
                    appendLine()
                    append(toolSearchService!!.buildCatalogSummary())
                }
            }
        } else {
            buildString {
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
                    parallelToolCalls = true,
                    noPreambleToolCalls = budget?.preset?.noPreambleToolCalls == true,
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
                // Structured memory section: behavioral instructions + relevant results
                val memorySection = MemoryPromptBuilder.buildMemorySection(
                    memoryManager, memoryContext, hasMemoryTools = true,
                )
                if (memorySection.isNotBlank()) {
                    appendLine()
                    append(memorySection)
                }
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
        // Add meta-tools: spawn_subagent, ask_user (skip for local models — too complex for 1.5B)
        if (!isLocalModel) {
            if (smartRouter != null || useToolSearch) {
                allToolsJson.add(buildSpawnSubagentToolJson())
            }
            allToolsJson.add(buildAskUserToolJson())
        }
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
        var lastInputTokens = 0
        var totalTokensSavedByMaxTokens = 0
        var totalCharsTruncated = 0
        var truncationCount = 0

        // Resolve effective model ID and max tokens (model routing may override).
        // customModelIdOverride takes top priority — used when the user's
        // configured id isn't in the AnthropicModels enum (CUSTOM provider).
        val effectiveModelId = customModelIdOverride ?: modelIdOverride ?: model.modelId
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

            // Reactive compaction: handles prompt-too-long errors with automatic compaction + circuit breaker
            val compactTracking = AutoCompactTrackingState()
            val reactiveCompaction = if (client !is LocalLlmClient && compactionConfig != null) {
                val restoration = PostCompactRestoration(toolSearchService)
                ReactiveCompaction(
                    ContextCompactor(client, compactionConfig, restoration),
                    effectiveModelId,
                )
            } else null

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
                    parallelToolCalls = true,
                    verbosity = verbosity,
                )

                val responseBlocks = mutableListOf<ContentBlock>()
                val streamText = StringBuilder()

                // Streaming tool executor: starts executing tools as they arrive from the stream
                val streamingExecutor = StreamingToolExecutor(
                    executeToolCall = { block ->
                        callbacks.onToolExecution(block.name)
                        val engine = ExecutionEngineFactory.create(
                            skillRegistry = skillRegistry,
                            tier = tier,
                            enabledSkillIds = enabledSkillIds,
                            safetyLayer = safety,
                            agentCallbacks = callbacks,
                            budgetConfig = budget,
                        )
                        val calls = ExecutionEngineFactory.toToolCalls(listOf(block))
                        val batchResult = engine.executeBatch(calls)
                        ExecutionEngineFactory.toContentBlocks(batchResult.results).firstOrNull()
                            ?: ContentBlock.ToolResult(block.id, "No result", isError = true)
                    },
                    isConcurrencySafe = StreamingToolExecutor::defaultIsConcurrencySafe,
                    scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
                )

                val streamCallback = object : StreamingCallback {
                    override fun onToken(text: String) {
                        streamText.append(text)
                        fullText.append(text)
                        callbacks.onToken(text)
                    }

                    override fun onToolUse(id: String, name: String, input: JsonObject) {
                        // Start executing regular tools immediately (not search/ask_user/subagent)
                        if (name != ToolSearchService.TOOL_NAME && name != ASK_USER_TOOL_NAME && name != SPAWN_SUBAGENT_TOOL_NAME) {
                            streamingExecutor.addTool(ContentBlock.ToolUseBlock(id, name, input))
                        }
                    }

                    override fun onComplete(response: MessagesResponse) {
                        responseBlocks.addAll(response.content)
                        response.usage?.let { u ->
                            totalInputTokens += u.inputTokens
                            totalOutputTokens += u.outputTokens
                            totalCacheReadTokens += u.cacheReadTokens
                            totalCacheWriteTokens += u.cacheWriteTokens
                            // Full prompt size = non-cached + cached tokens
                            lastInputTokens = u.inputTokens + u.cacheReadTokens + u.cacheWriteTokens
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
                try {
                    withRetry { attempt ->
                        if (attempt > 0) {
                            // Reset accumulators on retry so we don't double-count
                            responseBlocks.clear()
                            streamText.clear()
                        }
                        client.streamMessage(request, streamCallback)
                    }
                } catch (e: CannotRetryException) {
                    val apiEx = e.originalError as? AnthropicApiException
                    if (apiEx != null && reactiveCompaction?.isPromptTooLong(apiEx) == true) {
                        Log.w(TAG, "Prompt too long (HTTP ${apiEx.statusCode}), attempting reactive compaction...")
                        val compactResult = reactiveCompaction.tryReactiveCompact(messages, compactTracking)
                        if (compactResult != null) {
                            // Replace history with compacted version and retry this iteration
                            messages.clear()
                            messages.addAll(compactResult.compactedHistory)
                            Log.i(TAG, "Reactive compaction succeeded, retrying with ${messages.size} messages")
                            iterations-- // don't count this as an iteration
                            continue
                        }
                        Log.e(TAG, "Reactive compaction failed or circuit breaker tripped, propagating error")
                    }
                    throw e.originalError
                }
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
                    callbacks.onComplete(fullText.toString(), TokenUsageSnapshot(
                        lastInputTokens = lastInputTokens,
                        totalInputTokens = totalInputTokens,
                        totalOutputTokens = totalOutputTokens,
                        cacheReadTokens = totalCacheReadTokens,
                        cacheWriteTokens = totalCacheWriteTokens,
                    ))
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

                // Handle ask_user calls — returns immediately, user answers in next turn
                for (call in askUserCalls) {
                    val request = parseAskUserInput(call.input)
                    Log.i(TAG, "ask_user: ${request.questions.size} question(s): ${request.questions.joinToString { "'${it.question.take(50)}'" }}")
                    callbacks.onToolExecution(ASK_USER_TOOL_NAME)
                    callbacks.onAskUserDisplayed(request)
                    val summary = request.questions.joinToString("; ") { q ->
                        if (q.options.isNotEmpty()) "${q.question} [${q.options.joinToString(", ")}]"
                        else q.question
                    }
                    allToolResults.add(ContentBlock.ToolResult(
                        toolUseId = call.id,
                        content = "Questions displayed to user: $summary. " +
                            "Your turn is complete. The user's answer will arrive as their next message.",
                        isError = false,
                    ))
                    Log.i(TAG, "ask_user displayed: $summary")
                }

                coroutineScope {
                    // Regular tools: already executing via StreamingToolExecutor.
                    // Any regular tools NOT caught by the streaming callback (e.g. if
                    // onToolUse wasn't fired) get executed here as fallback.
                    val regularNotInExecutor = regularCalls.filter { call ->
                        !streamingExecutor.hasTools ||
                            call.name == ToolSearchService.TOOL_NAME // search already handled above
                    }
                    if (regularNotInExecutor.isNotEmpty()) {
                        val engine = ExecutionEngineFactory.create(
                            skillRegistry = skillRegistry,
                            tier = tier,
                            enabledSkillIds = enabledSkillIds,
                            safetyLayer = safety,
                            agentCallbacks = callbacks,
                            budgetConfig = budget,
                        )
                        val engineCalls = ExecutionEngineFactory.toToolCalls(regularNotInExecutor)
                        val batchResult = engine.executeBatch(engineCalls)
                        val engineMetrics = batchResult.metrics
                        Log.i(TAG, "ExecutionEngine (fallback) | ${engineMetrics.executedCount} executed, " +
                            "${engineMetrics.blockedCount} blocked, ${engineMetrics.errorCount} errors, " +
                            "${engineMetrics.totalDurationMs}ms total")
                        allToolResults.addAll(ExecutionEngineFactory.toContentBlocks(batchResult.results))
                    }

                    // Streaming executor results (tools that started during streaming)
                    if (streamingExecutor.hasTools) {
                        val streamedResults = streamingExecutor.awaitAll()
                        Log.i(TAG, "StreamingToolExecutor | ${streamedResults.size} results collected")
                        allToolResults.addAll(streamedResults)
                        streamingExecutor.reset()
                    }

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

                    // Await sub-agent results
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
            callbacks.onComplete(fullText.toString(), TokenUsageSnapshot(
                lastInputTokens = lastInputTokens,
                totalInputTokens = totalInputTokens,
                totalOutputTokens = totalOutputTokens,
                cacheReadTokens = totalCacheReadTokens,
                cacheWriteTokens = totalCacheWriteTokens,
            ))
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
            effectiveModelId = customModelIdOverride ?: subagentRouting?.modelIdOverride ?: model.modelId
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
    /**
     * Conversation-aware multi-query memory retrieval.
     *
     * Instead of searching with just the raw user message (which fails for
     * "yes", "do it", "same as before"), this builds multiple search queries
     * from conversation context and deduplicates the results.
     *
     * Queries (in priority order):
     * 1. User's message (if substantive — >30 chars after stop word removal)
     * 2. Keywords from the last assistant message (captures the actual topic)
     * 3. Combined: user message + last assistant summary (broadest recall)
     *
     * Results are boosted by recency (newer memories score higher).
     */
    private suspend fun fetchMemoryContext(
        userMessage: String,
        conversationHistory: List<Message>,
        isFirstTurn: Boolean = false,
    ): String {
        val manager = memoryManager ?: run {
            Log.d(TAG, "fetchMemoryContext: no MemoryManager set, skipping")
            return ""
        }

        return try {
            val sections = mutableListOf<String>()

            // ── First turn: always load USER + FEEDBACK memories (query-independent) ──
            // These describe WHO the user is and HOW they want to be helped.
            // Relevant regardless of what the user's first message says.
            if (isFirstTurn) {
                val profileMemories = fetchProfileMemories(manager)
                if (profileMemories.isNotBlank()) {
                    sections.add(profileMemories)
                }
            }

            // ── Query-based search (topic-specific memories) ──
            val queryResults = fetchQueryMemories(manager, userMessage, conversationHistory)
            if (queryResults.isNotBlank()) {
                sections.add(queryResults)
            }

            sections.joinToString("\n")
        } catch (e: Exception) {
            Log.w(TAG, "fetchMemoryContext: search failed: ${e.message}", e)
            ""
        }
    }

    /**
     * Fetches USER and FEEDBACK memories regardless of query content.
     * These are "profile" memories that inform how the model should behave.
     */
    private suspend fun fetchProfileMemories(manager: MemoryManager): String {
        val userMemories = manager.list(limit = 3, type = org.ethereumphone.andyclaw.memory.model.MemoryType.USER)
        val feedbackMemories = manager.list(limit = 3, type = org.ethereumphone.andyclaw.memory.model.MemoryType.FEEDBACK)
        val profileEntries = (userMemories + feedbackMemories)
            .sortedByDescending { it.updatedAt }
            .take(MEMORY_CONTEXT_MAX_RESULTS)

        if (profileEntries.isEmpty()) return ""

        Log.i(TAG, "fetchMemoryContext: injecting ${profileEntries.size} profile memories (USER/FEEDBACK)")
        return profileEntries.joinToString("\n") { entry ->
            MemoryPromptBuilder.formatSearchResult(entry)
        }
    }

    /**
     * Fetches query-matched memories using multi-query hybrid search + optional reranking.
     */
    private suspend fun fetchQueryMemories(
        manager: MemoryManager,
        userMessage: String,
        conversationHistory: List<Message>,
    ): String {
        // Extract recent conversation context for query building
        val lastAssistantText = conversationHistory.lastOrNull { it.role == "assistant" }
            ?.let { extractText(it) }
            ?.take(200)
            ?: ""

        // Build multiple search queries
        val queries = buildSearchQueries(userMessage, lastAssistantText)
        if (queries.isEmpty()) return ""
        Log.d(TAG, "fetchMemoryContext: ${queries.size} queries: ${queries.map { "\"${it.take(60)}\"" }}")

        // When reranking, fetch a wider candidate pool for the LLM to filter
        val fetchLimit = if (memoryReranker != null) MEMORY_RERANK_CANDIDATE_POOL else MEMORY_CONTEXT_MAX_RESULTS

        // Execute all queries and deduplicate by memory ID
        val seenIds = mutableSetOf<String>()
        val allResults = mutableListOf<Pair<org.ethereumphone.andyclaw.memory.model.MemorySearchResult, Float>>()

        for (query in queries) {
            val results = manager.search(
                query = query,
                maxResults = fetchLimit,
                minScore = MEMORY_CONTEXT_MIN_SCORE,
            )
            for (result in results) {
                if (result.memoryId !in seenIds) {
                    seenIds.add(result.memoryId)
                    allResults.add(result to result.score)
                }
            }
        }

        if (allResults.isEmpty()) {
            Log.d(TAG, "fetchMemoryContext: no query-matched memories across ${queries.size} queries")
            return ""
        }

        // Apply recency boost
        val boosted = allResults.mapNotNull { (result, score) ->
            val entry = manager.get(result.memoryId) ?: return@mapNotNull null
            val age = MemoryPromptBuilder.daysSince(entry.updatedAt)
            val recencyMultiplier = when {
                age == 0 -> 1.3f
                age <= 3 -> 1.1f
                age <= 7 -> 1.0f
                age <= 30 -> 0.9f
                else -> 0.8f
            }
            Triple(entry, score * recencyMultiplier, result)
        }
            .sortedByDescending { it.second }
            .take(if (memoryReranker != null) MEMORY_RERANK_CANDIDATE_POOL else MEMORY_CONTEXT_MAX_RESULTS)

        // Optional LLM reranking
        val finalEntries = if (memoryReranker != null && boosted.size > 1) {
            val candidateEntries = boosted.map { it.first }
            val reranked = memoryReranker.rerank(
                candidates = candidateEntries,
                userMessage = userMessage,
                conversationContext = lastAssistantText,
                maxResults = MEMORY_CONTEXT_MAX_RESULTS,
            )
            Log.i(TAG, "fetchMemoryContext: AI reranked ${candidateEntries.size} → ${reranked.size}")
            reranked
        } else {
            boosted.map { it.first }
        }

        if (finalEntries.isEmpty()) return ""

        Log.i(TAG, "fetchMemoryContext: injecting ${finalEntries.size} query-matched memories")
        return finalEntries.joinToString("\n") { entry ->
            MemoryPromptBuilder.formatSearchResult(entry)
        }
    }

    /**
     * Builds 1-3 search queries from the user message and conversation context.
     * Handles short/vague messages by falling back to assistant context.
     */
    private fun buildSearchQueries(userMessage: String, lastAssistantText: String): List<String> {
        val queries = mutableListOf<String>()
        val cleanedUser = removeStopWords(userMessage).trim()

        // Query 1: User's message (if it has substance after stop word removal)
        if (cleanedUser.length >= 15) {
            queries.add(cleanedUser)
        }

        // Query 2: Keywords from the last assistant message
        // This is the key insight — when user says "yes do it", the assistant's
        // previous message contains the actual topic.
        if (lastAssistantText.isNotBlank()) {
            val assistantKeywords = removeStopWords(lastAssistantText).trim()
            if (assistantKeywords.length >= 15 && assistantKeywords != cleanedUser) {
                queries.add(assistantKeywords)
            }
        }

        // Query 3: Combined (broadest recall, catches cross-references)
        if (cleanedUser.isNotBlank() && lastAssistantText.isNotBlank()) {
            val combined = "${cleanedUser.take(100)} ${removeStopWords(lastAssistantText).take(100)}".trim()
            if (combined.length >= 20 && combined !in queries) {
                queries.add(combined)
            }
        }

        // Fallback: if all queries were too short, use raw user message
        if (queries.isEmpty() && userMessage.length >= 10) {
            queries.add(userMessage)
        }

        return queries
    }

    /** Common English stop words that add noise to FTS queries. */
    private val STOP_WORDS = setOf(
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "can", "shall", "to", "of", "in", "for",
        "on", "with", "at", "by", "from", "as", "into", "about", "like",
        "through", "after", "over", "between", "out", "up", "down", "off",
        "and", "but", "or", "nor", "not", "so", "yet", "both", "either",
        "that", "this", "these", "those", "it", "its", "i", "me", "my",
        "we", "our", "you", "your", "he", "she", "they", "them", "their",
        "what", "which", "who", "whom", "how", "when", "where", "why",
        "if", "then", "else", "just", "also", "very", "too", "quite",
        "please", "yes", "no", "ok", "okay", "sure", "thanks", "hey",
        "hi", "hello", "um", "uh",
    )

    private fun removeStopWords(text: String): String =
        text.split(Regex("\\s+"))
            .filter { it.lowercase() !in STOP_WORDS && it.length > 1 }
            .joinToString(" ")

    /** Extracts plain text from a Message (handles both Text and Blocks content). */
    private fun extractText(msg: Message): String = when (val content = msg.content) {
        is MessageContent.Text -> content.value
        is MessageContent.Blocks -> content.blocks
            .filterIsInstance<ContentBlock.TextBlock>()
            .joinToString("\n") { it.text }
    }
}
