package org.ethereumphone.andyclaw.extensions

import kotlinx.serialization.json.JsonObject
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition

/**
 * Bridges an extension into the existing AndyClaw skill system.
 *
 * Each adapter wraps a single [ExtensionDescriptor] and delegates execution
 * to the [ExtensionEngine], converting between [ExtensionResult] and [SkillResult].
 *
 * ## Usage
 *
 * ```kotlin
 * val adapters = engine.toSkillAdapters()
 * for (adapter in adapters) {
 *     nativeSkillRegistry.register(adapter)
 * }
 * ```
 *
 * This makes every extension function available as a tool that agents can
 * discover and invoke through the standard skill pipeline.
 */
class ExtensionSkillAdapter(
    private val descriptor: ExtensionDescriptor,
    private val engine: ExtensionEngine,
) : AndyClawSkill {

    override val id: String = "ext:${descriptor.id}"

    override val name: String = descriptor.name

    override val baseManifest: SkillManifest = SkillManifest(
        description = buildString {
            append("Extension: ${descriptor.name}")
            append(" [${descriptor.type.name}]")
            if (descriptor.version > 1) append(" v${descriptor.version}")
        },
        tools = descriptor.functions.map { fn ->
            ToolDefinition(
                name = fn.name,
                description = fn.description,
                inputSchema = fn.inputSchema,
                requiresApproval = fn.requiresApproval,
                requiredPermissions = fn.requiredPermissions,
            )
        },
        permissions = descriptor.functions
            .flatMap { it.requiredPermissions }
            .distinct(),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        val result = engine.executeOnExtension(descriptor.id, tool, params)

        return when (result) {
            is ExtensionResult.Success ->
                SkillResult.Success(result.data)

            is ExtensionResult.Error ->
                SkillResult.Error(result.message)

            is ExtensionResult.PermissionDenied ->
                SkillResult.Error("Permission denied: ${result.reason}")

            is ExtensionResult.Timeout ->
                SkillResult.Error("Extension timed out after ${result.millis}ms")

            is ExtensionResult.ApprovalRequired ->
                SkillResult.RequiresApproval(result.description)
        }
    }
}

/**
 * Create [ExtensionSkillAdapter]s for all currently registered extensions.
 *
 * Returns one adapter per extension. Register these with [NativeSkillRegistry]
 * to make extension functions visible to agents.
 */
fun ExtensionEngine.toSkillAdapters(): List<ExtensionSkillAdapter> {
    return registry.getAll().map { descriptor ->
        ExtensionSkillAdapter(descriptor, this)
    }
}
