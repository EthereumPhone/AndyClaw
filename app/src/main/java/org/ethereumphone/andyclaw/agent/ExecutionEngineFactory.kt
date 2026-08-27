package org.ethereumphone.andyclaw.agent

import android.util.Log
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.ethereumphone.andyclaw.ExecutionEngine.*
import org.ethereumphone.andyclaw.ledger.LedgerAction
import org.ethereumphone.andyclaw.ledger.LedgerDraft
import org.ethereumphone.andyclaw.ledger.LedgerKind
import org.ethereumphone.andyclaw.ledger.LedgerOutcome
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.ImageSource
import org.ethereumphone.andyclaw.llm.ToolResultContent
import org.ethereumphone.andyclaw.safety.ProvenanceGate
import org.ethereumphone.andyclaw.safety.SafetyLayer
import org.ethereumphone.andyclaw.safety.ToolEffects
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.skills.ToolRoutes

/**
 * Builds a [ParallelExecutionEngine] from AgentLoop's existing dependencies.
 * Bridges between the engine's generic types and the app's concrete types
 * (SafetyLayer, NativeSkillRegistry, AgentLoop.Callbacks, etc.)
 */
object ExecutionEngineFactory {

    private const val TAG = "ExecEngineFactory"

    /**
     * Create an engine wired to the given skill registry, safety layer, and agent callbacks.
     *
     * [provenance] is where the content that triggered this run came from, and
     * [triggerConversationId] is the conversation it arrived on (an XMTP sender
     * address, a Telegram chat id) so an untrusted run can be confined to replying
     * there. [enforceProvenance] false runs the gate in log-only mode: every verdict
     * is logged, none is applied.
     */
    fun create(
        skillRegistry: NativeSkillRegistry,
        tier: Tier,
        enabledSkillIds: Set<String>,
        safetyLayer: SafetyLayer?,
        agentCallbacks: AgentLoop.Callbacks,
        budgetConfig: BudgetConfig?,
        provenance: Provenance = Provenance.USER,
        triggerConversationId: String? = null,
        enforceProvenance: Boolean = true,
        /**
         * Where this run writes its steps down, or null when nothing is recording.
         *
         * Defaulted and trailing so every existing call site compiles unchanged, which is
         * the same rule `ToolDefinition.rung` followed in Phase 2 and for the same reason:
         * this is called once per tool call from three places and a required parameter here
         * is a required parameter in all of them.
         */
        ledger: AgentLedger? = null,
        /** What the run was asked to do. Carried onto every step so a row reads on its own. */
        intent: String = "",
    ): ParallelExecutionEngine {
        // `create` is called once per tool call inside the hot loop, and every check
        // that needs a tool definition used to re-walk the whole registry. Resolve
        // the tool list at most once per engine and share it.
        val toolsByName: Map<String, ToolDefinition> by lazy {
            // First-wins, matching the `getTools(tier).find { it.name == ... }` this
            // replaces: a skill that declares the same tool in both its base and its
            // privileged manifest still resolves to the base one.
            val byName = LinkedHashMap<String, ToolDefinition>()
            for (tool in skillRegistry.getTools(tier)) byName.putIfAbsent(tool.name, tool)
            byName
        }

        // How long each tool actually took, so a ledger row can say. Name-keyed, matching
        // `ExecutionMetrics.perToolMs`, which resolves the same way for the same reason: the
        // executor is handed a name and parameters, never the call id.
        val toolDurations = java.util.concurrent.ConcurrentHashMap<String, Long>()

        val builder = EngineBuilder()
            .executor(createExecutor(skillRegistry, tier, provenance, triggerConversationId, toolDurations))
            .callbacks(createCallbacks(agentCallbacks, safetyLayer, ledger, provenance, intent, toolDurations))

        // Pre-flight checks (order matters — matches original AgentLoop order).
        // The provenance gate runs FIRST, before a rate-limit slot is spent or an
        // approval prompt is raised, so an untrusted run is stopped at the door.
        builder.addPreflightCheck(
            provenanceCheck(provenance, triggerConversationId, enforceProvenance) { toolsByName }
        )
        if (safetyLayer != null) {
            builder.addPreflightCheck(rateLimitCheck(safetyLayer))
            builder.addPreflightCheck(paramValidationCheck(safetyLayer))
        }
        builder.addPreflightCheck(permissionsCheck { toolsByName })
        builder.addPreflightCheck(approvalCheck { toolsByName })
        builder.addPreflightCheck(skillEnabledCheck(skillRegistry, tier, enabledSkillIds))
        // Last, because a route only matters for a call that was going to happen: no
        // point telling the model about a cheaper route to a tool it may not use.
        builder.addPreflightCheck(routeGateCheck { toolsByName })

        // Post-processors
        if (safetyLayer != null) {
            builder.addPostProcessor(safetySanitizationProcessor(safetyLayer))
        }
        if (budgetConfig != null) {
            builder.addPostProcessor(truncationProcessor(budgetConfig))
        }
        // Last in the chain, and it must stay last. A processor that blocks breaks the
        // chain, so anything after it never runs — which is exactly right here: a blocked
        // call is recorded by `onToolBlocked` instead, and running both would write the
        // step twice. Exactly one row per tool call, whichever way the call ends.
        if (ledger != null) {
            builder.addPostProcessor(
                ledgerProcessor(ledger, provenance, intent, toolDurations) { toolsByName }
            )
        }

        return builder.build()
    }

    // ═══════════════════════════════════════════
    // ToolExecutor
    // ═══════════════════════════════════════════

    private fun createExecutor(
        registry: NativeSkillRegistry,
        tier: Tier,
        provenance: Provenance,
        triggerConversationId: String?,
        durations: MutableMap<String, Long>,
    ): ToolExecutor =
        ToolExecutor { toolName, params ->
            // Publish the provenance into the coroutine context so code the engine
            // cannot see applies the same gate — `execute_code` runs BeanShell whose
            // `tools.call(name, params)` bridge reaches the registry directly.
            val startedMs = System.currentTimeMillis()
            try {
                withContext(ProvenanceContext(provenance, triggerConversationId)) {
                    when (val result = registry.executeTool(toolName, params, tier)) {
                        is SkillResult.Success -> ToolExecResult.Success(result.data)
                        is SkillResult.ImageSuccess -> ToolExecResult.ImageSuccess(result.text, result.base64, result.mediaType)
                        is SkillResult.Error -> ToolExecResult.Error(result.message)
                        is SkillResult.RequiresApproval -> ToolExecResult.RequiresApproval(result.description)
                    }
                }
            } finally {
                durations[toolName] = System.currentTimeMillis() - startedMs
            }
        }

    // ═══════════════════════════════════════════
    // Pre-flight checks
    // ═══════════════════════════════════════════

    private fun rateLimitCheck(safety: SafetyLayer) = PreflightCheck { call ->
        val msg = safety.checkRateLimit(call.name)
        if (msg != null) PreflightVerdict.Block(msg)
        else PreflightVerdict.Pass
    }

    private fun paramValidationCheck(safety: SafetyLayer) = PreflightCheck { call ->
        val validation = safety.validator.validateToolParams(call.input)
        if (!validation.isValid) {
            val reasons = validation.errors.joinToString("; ") { it.message }
            PreflightVerdict.Block(
                "[Safety] Tool '${call.name}' parameters rejected: $reasons. " +
                    "Disable safety mode in Settings to bypass this check."
            )
        } else {
            PreflightVerdict.Pass
        }
    }

    /**
     * The trust boundary. Runs before every other check so an untrusted trigger
     * cannot spend a rate-limit slot or raise an approval prompt on its way to a
     * tool it was never allowed to reach.
     */
    private fun provenanceCheck(
        provenance: Provenance,
        triggerConversationId: String?,
        enforce: Boolean,
        tools: () -> Map<String, ToolDefinition>,
    ) = PreflightCheck { call ->
        val toolDef = tools()[call.name]
        val effect = ToolEffects.of(call.name, toolDef)
        val verdict = ProvenanceGate.evaluate(call, provenance, triggerConversationId, toolDef)

        if (verdict is PreflightVerdict.Pass) {
            PreflightVerdict.Pass
        } else {
            val outcome = when (verdict) {
                is PreflightVerdict.Block -> "BLOCK"
                is PreflightVerdict.NeedsApproval -> "NEEDS_APPROVAL"
                else -> verdict::class.simpleName ?: "?"
            }
            val unclassified = if (ToolEffects.isClassified(call.name, toolDef)) "" else " (unclassified -> fail-closed)"
            val mode = if (enforce) "" else " [LOG-ONLY, not enforced]"
            Log.w(TAG, "provenance $provenance + $effect on '${call.name}' -> $outcome$unclassified$mode")
            if (enforce) verdict else PreflightVerdict.Pass
        }
    }

    private fun permissionsCheck(tools: () -> Map<String, ToolDefinition>) = PreflightCheck { call ->
        val toolDef = tools()[call.name]
        if (toolDef != null && toolDef.requiredPermissions.isNotEmpty()) {
            PreflightVerdict.NeedsPermissions(toolDef.requiredPermissions)
        } else {
            PreflightVerdict.Pass
        }
    }

    private fun approvalCheck(tools: () -> Map<String, ToolDefinition>) = PreflightCheck { call ->
        val toolDef = tools()[call.name]
        if (toolDef?.requiresApproval == true) {
            PreflightVerdict.NeedsApproval("Tool '${call.name}' requires your approval to execute.")
        } else {
            PreflightVerdict.Pass
        }
    }

    /**
     * The execution ladder, made structural.
     *
     * `agent-os-design.md` §3: "Every intent resolves down this ladder. **Never** skip a
     * rung to reach a lower one." Until now that was a line in the system prompt, and a
     * line in the prompt is advice — the model picks, and it will sometimes pick the
     * shadow display when a native call or a compiled flow would have done the same
     * thing in milliseconds for nothing.
     *
     * So the block *is* the mechanism, and the message is the interesting half: it names
     * the better tool, as a tool result, which is the one channel the model cannot skim
     * past. It only ever fires when a lower-numbered rung is registered **for the same
     * package**, and a stale flow drops its `targetPackages` precisely so that a route
     * nobody is sure about stops standing in front of the fallback.
     */
    private fun routeGateCheck(tools: () -> Map<String, ToolDefinition>) = PreflightCheck { call ->
        val byName = tools()
        val rung = byName[call.name]?.rung ?: ToolRoutes.rungOf(call.name)
        if (rung == null || rung < ToolRoutes.RUNG_DISPLAY) {
            return@PreflightCheck PreflightVerdict.Pass
        }

        // Only a call that names the app it is about can be routed. `launch_intent`
        // carries a URI, and a bare tap carries nothing — those are mid-session steps,
        // not the door.
        val target = call.input["package_name"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: return@PreflightCheck PreflightVerdict.Pass

        val better = byName.values
            .mapNotNull { def ->
                val defRung = def.rung ?: return@mapNotNull null
                if (defRung < rung && target in def.targetPackages) def.name to defRung else null
            }
            .plus(
                ToolRoutes.routesInto(target)
                    .filter { it in byName }
                    .mapNotNull { name -> ToolRoutes.rungOf(name)?.let { name to it } }
            )
            .distinctBy { it.first }
            .sortedBy { it.second }

        when {
            better.isEmpty() -> PreflightVerdict.Pass

            // Already said once, recently. A better route existing does not mean it
            // covers *this* task — `gmail_send` is no help to someone changing a
            // setting inside Gmail — so the gate tells the model once and then gets out
            // of the way. Blocking forever would make a legitimate UI task impossible,
            // which is a worse failure than an occasional unnecessary display session.
            alreadyRouted(target) -> {
                Log.i(TAG, "route gate: ${call.name} -> $target allowed (already advised)")
                PreflightVerdict.Pass
            }

            else -> {
                noteRouted(target)
                val named = better.joinToString(", ") { "`${it.first}` (rung ${it.second})" }
                Log.i(TAG, "route gate: ${call.name} -> $target blocked in favour of $named")
                PreflightVerdict.Block(
                    "[Route] There is a cheaper, more reliable way into $target than driving its " +
                        "UI: $named. Rung 0 is a real API, rung 2 is a notification reply, rung 3 " +
                        "is a compiled flow — none of them costs a screenshot or a model call. Use " +
                        "one of those instead of '${call.name}'. If none of them actually covers " +
                        "this task, call '${call.name}' again and it will run."
                )
            }
        }
    }

    // ── Route-gate memory ─────────────────────────────────────────────

    /**
     * Packages the gate has already named a better route for, and when.
     *
     * Object-level because the engine is rebuilt for every tool call — there is nowhere
     * else for "I have already said this" to live. Bounded by the window rather than by
     * size: it holds one small entry per app the agent has tried to drive.
     */
    private val recentlyRouted = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private const val ROUTE_ADVICE_WINDOW_MS = 5 * 60_000L

    private fun alreadyRouted(packageName: String): Boolean {
        val at = recentlyRouted[packageName] ?: return false
        if (System.currentTimeMillis() - at <= ROUTE_ADVICE_WINDOW_MS) return true
        recentlyRouted.remove(packageName)
        return false
    }

    private fun noteRouted(packageName: String) {
        val now = System.currentTimeMillis()
        recentlyRouted[packageName] = now
        if (recentlyRouted.size > MAX_ROUTED_PACKAGES) {
            recentlyRouted.entries.removeIf { now - it.value > ROUTE_ADVICE_WINDOW_MS }
        }
    }

    private const val MAX_ROUTED_PACKAGES = 64

    /** Enough to say which gate fired and why; not enough to be a second copy of the prompt. */
    private const val MAX_BLOCK_NOTE_CHARS = 240

    /** Tests drive the gate repeatedly against the same package; they start from clean. */
    internal fun clearRouteMemory() = recentlyRouted.clear()

    private fun skillEnabledCheck(
        registry: NativeSkillRegistry,
        tier: Tier,
        enabledSkillIds: Set<String>,
    ) = PreflightCheck { call ->
        val owningSkill = registry.findSkillForTool(call.name, tier)
        if (owningSkill != null && owningSkill.id !in enabledSkillIds) {
            PreflightVerdict.Block(
                "Skill '${owningSkill.name}' is disabled. The user must enable it in Settings."
            )
        } else {
            PreflightVerdict.Pass
        }
    }

    // ═══════════════════════════════════════════
    // Post-processors
    // ═══════════════════════════════════════════

    private fun safetySanitizationProcessor(safety: SafetyLayer) = PostProcessor { call, result ->
        val rawContent = when (result) {
            is ToolExecResult.Success -> result.data
            is ToolExecResult.ImageSuccess -> result.text
            is ToolExecResult.Error -> return@PostProcessor PostProcessedResult(
                content = result.message,
                isError = true,
            )
            is ToolExecResult.RequiresApproval -> return@PostProcessor PostProcessedResult(
                content = result.description,
                isError = true,
            )
        }

        val safetyResult = safety.sanitizeToolOutput(call.name, rawContent)

        if (safetyResult.isBlocked) {
            return@PostProcessor PostProcessedResult(
                content = safetyResult.blockedReason ?: safetyResult.output,
                isError = true,
                blocked = true,
                blockedReason = safetyResult.blockedReason ?: safetyResult.output,
            )
        }

        val finalContent = if (safetyResult.wasModified || safety.isUntrustedTool(call.name)) {
            if (safety.isUntrustedTool(call.name)) {
                val sourceUrl = call.input["url"]?.jsonPrimitive?.contentOrNull
                    ?: call.input["query"]?.jsonPrimitive?.contentOrNull
                    ?: "unknown"
                safety.wrapUntrustedForLlm(call.name, safetyResult.output, sourceUrl)
            } else {
                safety.wrapForLlm(call.name, safetyResult.output, safetyResult.wasModified)
            }
        } else {
            safetyResult.output
        }

        val imageData = if (result is ToolExecResult.ImageSuccess) {
            ToolCallResult.ImageData(result.base64, result.mediaType)
        } else null

        PostProcessedResult(
            content = finalContent,
            isError = false,
            imageData = imageData,
            warnings = safetyResult.warnings,
        )
    }

    private fun truncationProcessor(budget: BudgetConfig) = PostProcessor { _, result ->
        val content = when (result) {
            is ToolExecResult.Success -> result.data
            is ToolExecResult.ImageSuccess -> return@PostProcessor PostProcessedResult(
                content = result.text,
                isError = false,
                imageData = ToolCallResult.ImageData(result.base64, result.mediaType),
            )
            is ToolExecResult.Error -> return@PostProcessor PostProcessedResult(
                content = result.message,
                isError = true,
            )
            is ToolExecResult.RequiresApproval -> return@PostProcessor PostProcessedResult(
                content = result.description,
                isError = true,
            )
        }

        val truncated = budget.truncateToolResult(content)
        PostProcessedResult(
            content = truncated,
            isError = false,
        )
    }

    // ═══════════════════════════════════════════
    // The ledger
    // ═══════════════════════════════════════════

    /**
     * One row per tool that ran.
     *
     * A `PostProcessor` is the right hook because it is the last thing that sees a call
     * before the result goes back to the model, so it sees what the model will see — the
     * sanitised, truncated content, and whether the whole thing counted as an error. It is
     * also, since Phase 1.1 fixed the chain, a hook that can be added without silently
     * discarding the work of the processors before it.
     *
     * The row carries the four things `agent-first-plan.md` Phase 3.1 asks for — provenance,
     * rung, outcome, cost — and nothing else. Tool inputs and outputs are deliberately
     * absent: they routinely contain message bodies, addresses and file contents, and this
     * is a store the user is invited to read and export.
     */
    private fun ledgerProcessor(
        ledger: AgentLedger,
        provenance: Provenance,
        intent: String,
        durations: Map<String, Long>,
        tools: () -> Map<String, ToolDefinition>,
    ) = PostProcessor { call, result ->
        val isError = result is ToolExecResult.Error || result is ToolExecResult.RequiresApproval
        runCatching {
            ledger.sink.record(
                LedgerDraft(
                    sessionId = ledger.sessionId,
                    kind = LedgerKind.TOOL,
                    intent = intent,
                    provenance = provenance.name,
                    outcome = if (isError) LedgerOutcome.ERROR else LedgerOutcome.OK,
                    routeRung = rungOf(call.name, tools()),
                    flowRef = ledger.flowRef(call.name),
                    actions = listOf(
                        LedgerAction(
                            tool = call.name,
                            ok = !isError,
                            durationMs = durations[call.name] ?: 0L,
                        )
                    ),
                    durationMs = durations[call.name] ?: 0L,
                )
            )
        }

        // Pass the result through untouched. This processor observes; it must never be the
        // reason a tool result changes, or turning the ledger on would change what the model
        // sees.
        passthrough(result)
    }

    /** The identity transform, in the shape the chain expects. */
    private fun passthrough(result: ToolExecResult): PostProcessedResult = when (result) {
        is ToolExecResult.Success -> PostProcessedResult(content = result.data, isError = false)
        is ToolExecResult.ImageSuccess -> PostProcessedResult(
            content = result.text,
            isError = false,
            imageData = ToolCallResult.ImageData(result.base64, result.mediaType),
        )
        is ToolExecResult.Error -> PostProcessedResult(content = result.message, isError = true)
        is ToolExecResult.RequiresApproval -> PostProcessedResult(content = result.description, isError = true)
    }

    private fun rungOf(toolName: String, tools: Map<String, ToolDefinition>): Int? =
        tools[toolName]?.rung ?: ToolRoutes.rungOf(toolName)

    // ═══════════════════════════════════════════
    // Callbacks bridge
    // ═══════════════════════════════════════════

    private fun createCallbacks(
        agentCallbacks: AgentLoop.Callbacks,
        safety: SafetyLayer?,
        ledger: AgentLedger?,
        provenance: Provenance,
        intent: String,
        durations: Map<String, Long>,
    ) = object : ExecutionCallbacks {

        override fun onToolStarted(toolName: String) {
            agentCallbacks.onToolExecution(toolName)
        }

        override fun onToolCompleted(toolName: String, result: ToolCallResult) {
            // Map back to SkillResult for the existing callback interface
            val img = result.imageData
            val skillResult = if (result.isError) {
                SkillResult.Error(result.content)
            } else if (img != null) {
                SkillResult.ImageSuccess(result.content, img.base64, img.mediaType)
            } else {
                SkillResult.Success(result.content)
            }
            agentCallbacks.onToolResult(toolName, skillResult)
        }

        override fun onToolBlocked(toolName: String, reason: String) {
            // The most interesting row in the ledger. A pre-flight block never reaches a
            // post-processor — the engine turns it into a result before execution — so
            // without this the record would show only the tools that were allowed to run,
            // which is precisely the half that needs no defending. The block reason is
            // written verbatim because it is this app's own text, not tool output.
            ledger?.let { l ->
                runCatching {
                    l.sink.record(
                        LedgerDraft(
                            sessionId = l.sessionId,
                            kind = LedgerKind.TOOL,
                            intent = intent,
                            provenance = provenance.name,
                            outcome = LedgerOutcome.BLOCKED,
                            actions = listOf(
                                LedgerAction(
                                    tool = toolName,
                                    ok = false,
                                    durationMs = durations[toolName] ?: 0L,
                                    note = reason.take(MAX_BLOCK_NOTE_CHARS),
                                )
                            ),
                        )
                    )
                }
            }
            if (reason.startsWith("[Safety]")) {
                agentCallbacks.onSecurityBlock(toolName, reason)
            }
        }

        override suspend fun onApprovalNeeded(
            description: String,
            toolName: String?,
            toolInput: JsonObject?,
        ): Boolean = agentCallbacks.onApprovalNeeded(description, toolName, toolInput)

        override suspend fun onPermissionsNeeded(permissions: List<String>): Boolean =
            agentCallbacks.onPermissionsNeeded(permissions)
    }

    // ═══════════════════════════════════════════
    // Result conversion: ToolCallResult → ContentBlock.ToolResult
    // ═══════════════════════════════════════════

    /**
     * Convert engine results back to LLM-compatible ContentBlocks.
     * Called by AgentLoop to pack results into the conversation.
     */
    fun toContentBlocks(results: List<ToolCallResult>): List<ContentBlock> {
        return results.map { r ->
            val img = r.imageData
            if (img != null && !r.isError) {
                ContentBlock.ToolResult(
                    toolUseId = r.toolCallId,
                    content = r.content,
                    isError = r.isError,
                    contentBlocks = listOf(
                        ToolResultContent.Text(r.content),
                        ToolResultContent.Image(
                            ImageSource(
                                mediaType = img.mediaType,
                                data = img.base64,
                            )
                        ),
                    ),
                )
            } else {
                ContentBlock.ToolResult(
                    toolUseId = r.toolCallId,
                    content = r.content,
                    isError = r.isError,
                )
            }
        }
    }

    /**
     * Convert LLM tool use blocks to engine ToolCalls.
     */
    fun toToolCalls(toolUseBlocks: List<ContentBlock.ToolUseBlock>): List<ToolCall> {
        return toolUseBlocks.map { ToolCall(id = it.id, name = it.name, input = it.input) }
    }
}
