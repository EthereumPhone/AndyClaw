package org.ethereumphone.andyclaw.agent

import android.util.Log
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.ethereumphone.andyclaw.ExecutionEngine.*
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

        val builder = EngineBuilder()
            .executor(createExecutor(skillRegistry, tier, provenance, triggerConversationId))
            .callbacks(createCallbacks(agentCallbacks, safetyLayer))

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

        // Post-processors
        if (safetyLayer != null) {
            builder.addPostProcessor(safetySanitizationProcessor(safetyLayer))
        }
        if (budgetConfig != null) {
            builder.addPostProcessor(truncationProcessor(budgetConfig))
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
    ): ToolExecutor =
        ToolExecutor { toolName, params ->
            // Publish the provenance into the coroutine context so code the engine
            // cannot see applies the same gate — `execute_code` runs BeanShell whose
            // `tools.call(name, params)` bridge reaches the registry directly.
            withContext(ProvenanceContext(provenance, triggerConversationId)) {
                when (val result = registry.executeTool(toolName, params, tier)) {
                    is SkillResult.Success -> ToolExecResult.Success(result.data)
                    is SkillResult.ImageSuccess -> ToolExecResult.ImageSuccess(result.text, result.base64, result.mediaType)
                    is SkillResult.Error -> ToolExecResult.Error(result.message)
                    is SkillResult.RequiresApproval -> ToolExecResult.RequiresApproval(result.description)
                }
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
    // Callbacks bridge
    // ═══════════════════════════════════════════

    private fun createCallbacks(
        agentCallbacks: AgentLoop.Callbacks,
        safety: SafetyLayer?,
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
