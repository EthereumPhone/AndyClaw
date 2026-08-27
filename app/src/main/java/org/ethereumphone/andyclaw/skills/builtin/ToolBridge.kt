package org.ethereumphone.andyclaw.skills.builtin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.safety.ProvenanceGate
import org.ethereumphone.andyclaw.safety.ToolEffects
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier

/**
 * Bridge object injected into BeanShell as `tools`, enabling code to call
 * AndyClaw tools programmatically without LLM round-trips.
 *
 * Usage from BeanShell:
 * ```java
 * String result = tools.call("resolve_ens", Map.of("name", "alice.eth"));
 * String price = tools.call("get_token_price", Map.of("symbol", "ETH"));
 * ```
 *
 * Thread safety: [call] dispatches tool execution to [Dispatchers.IO] via
 * [runBlocking], blocking the BeanShell thread. The outer timeout from
 * [CodeExecutionSkill] still applies as the overall deadline.
 *
 * **This class is a second front door to every tool.** It reaches the registry
 * directly, so none of the execution engine's pre-flight checks see the call, and
 * the tool list the model was shown is irrelevant to what it can invoke here. That
 * makes it the one place where filtering a tool out of a prompt is provably not
 * enforcement — so the provenance gate is applied again, in [enforceProvenance],
 * with [provenance] captured from the run that started this `execute_code`.
 */
class ToolBridge(
    private val registry: NativeSkillRegistry,
    private val tier: Tier,
    private val enabledSkillIds: Set<String>,
    /**
     * Provenance of the agent run that invoked `execute_code`. Defaults closed:
     * a bridge built without one cannot reach anything irreversible.
     */
    private val provenance: Provenance = Provenance.UNTRUSTED,
    /** false makes the provenance gate log-only here too, matching the engine. */
    private val enforceProvenance: Boolean = true,
) {
    companion object {
        private const val TAG = "ToolBridge"
    }

    /**
     * Refuse a tool this run is not allowed to reach unattended.
     *
     * There is no way to ask the user from inside BeanShell — the interpreter is
     * running on its own executor thread behind a blocking `future.get`. So anything
     * the gate does not outright pass is a refusal, and the message tells the model
     * to call the tool directly instead, where an approval card can actually be
     * raised.
     */
    private fun enforceProvenance(toolName: String) {
        val skill = registry.findSkillForTool(toolName, tier)
        val toolDef = skill?.baseManifest?.tools?.find { it.name == toolName }
            ?: skill?.privilegedManifest?.tools?.find { it.name == toolName }
        val effect = ToolEffects.of(toolName, toolDef)
        if (ProvenanceGate.allowsUnattended(provenance, effect)) return

        Log.w(TAG, "Provenance gate: refusing '$toolName' ($effect) under $provenance" +
            if (enforceProvenance) "" else " [LOG-ONLY, not enforced]")
        if (!enforceProvenance) return

        throw RuntimeException(
            "Tool '$toolName' is $effect and this code is running for a request that " +
                "came from untrusted content, so it cannot be called from code. " +
                "Call it as a direct tool call instead — that path can ask the user."
        )
    }

    data class ToolCallRecord(
        val toolName: String,
        val durationMs: Long,
        val success: Boolean,
    )

    /** Log of all tool calls made during this execution. */
    val callLog = mutableListOf<ToolCallRecord>()

    /**
     * Call an AndyClaw tool synchronously from BeanShell code.
     *
     * @param toolName the tool name (e.g. "resolve_ens", "web_search")
     * @param params a Java Map of parameter name → value
     * @return the tool result as a string
     * @throws RuntimeException if the tool fails, is not found, or requires approval
     */
    fun call(toolName: String, params: Map<String, Any?>): String {
        val startMs = System.currentTimeMillis()

        enforceProvenance(toolName)

        // Validate tool exists and skill is enabled
        val skill = registry.findSkillForTool(toolName, tier)
            ?: throw RuntimeException("Tool '$toolName' not found or not available")

        if (skill.id !in enabledSkillIds) {
            throw RuntimeException("Tool '$toolName' belongs to disabled skill '${skill.id}'")
        }

        // Check if tool requires approval (can't approve from within code)
        val toolDef = skill.baseManifest.tools.find { it.name == toolName }
            ?: skill.privilegedManifest?.tools?.find { it.name == toolName }
        if (toolDef?.requiresApproval == true) {
            throw RuntimeException(
                "Tool '$toolName' requires user approval and cannot be called from code. " +
                "Use it as a direct tool call instead."
            )
        }

        // Convert Java Map to JsonObject
        val jsonParams = mapToJsonObject(params)

        Log.d(TAG, "Programmatic call: $toolName(${jsonParams.toString().take(100)})")

        // Execute on IO dispatcher to avoid blocking the BeanShell executor thread
        val result = runBlocking(Dispatchers.IO) {
            registry.executeTool(toolName, jsonParams, tier)
        }

        val durationMs = System.currentTimeMillis() - startMs

        return when (result) {
            is SkillResult.Success -> {
                callLog.add(ToolCallRecord(toolName, durationMs, true))
                Log.d(TAG, "Programmatic call $toolName completed in ${durationMs}ms")
                result.data
            }
            is SkillResult.ImageSuccess -> {
                callLog.add(ToolCallRecord(toolName, durationMs, true))
                Log.d(TAG, "Programmatic call $toolName (image) completed in ${durationMs}ms")
                result.text // Return text portion; base64 image can't be used in BeanShell
            }
            is SkillResult.Error -> {
                callLog.add(ToolCallRecord(toolName, durationMs, false))
                Log.w(TAG, "Programmatic call $toolName failed in ${durationMs}ms: ${result.message}")
                throw RuntimeException("Tool '$toolName' failed: ${result.message}")
            }
            is SkillResult.RequiresApproval -> {
                callLog.add(ToolCallRecord(toolName, durationMs, false))
                throw RuntimeException(
                    "Tool '$toolName' requires approval: ${result.description}. " +
                    "Use it as a direct tool call instead."
                )
            }
        }
    }

    /**
     * Call the same tool multiple times in parallel from BeanShell code.
     * All calls execute concurrently and results are returned in the same order.
     *
     * @param toolName the tool name (e.g. "resolve_ens")
     * @param paramsList a Java List of Maps, one per call
     * @return a List of result strings in the same order as paramsList
     * @throws RuntimeException if any call fails (partial results are lost)
     */
    fun callParallel(toolName: String, paramsList: List<Map<String, Any?>>): List<String> {
        if (paramsList.isEmpty()) return emptyList()

        enforceProvenance(toolName)

        // Validate tool exists and is callable before dispatching
        val skill = registry.findSkillForTool(toolName, tier)
            ?: throw RuntimeException("Tool '$toolName' not found or not available")
        if (skill.id !in enabledSkillIds) {
            throw RuntimeException("Tool '$toolName' belongs to disabled skill '${skill.id}'")
        }
        val toolDef = skill.baseManifest.tools.find { it.name == toolName }
            ?: skill.privilegedManifest?.tools?.find { it.name == toolName }
        if (toolDef?.requiresApproval == true) {
            throw RuntimeException(
                "Tool '$toolName' requires user approval and cannot be called from code."
            )
        }

        Log.d(TAG, "Programmatic callParallel: $toolName x${paramsList.size}")
        val startMs = System.currentTimeMillis()

        val results = runBlocking(Dispatchers.IO) {
            paramsList.map { params ->
                async {
                    val jsonParams = mapToJsonObject(params)
                    registry.executeTool(toolName, jsonParams, tier)
                }
            }.awaitAll()
        }

        val durationMs = System.currentTimeMillis() - startMs
        Log.i(TAG, "Programmatic callParallel $toolName x${paramsList.size} completed in ${durationMs}ms")

        // Process results — collect successes, throw on first error
        return results.mapIndexed { index, result ->
            when (result) {
                is SkillResult.Success -> {
                    callLog.add(ToolCallRecord(toolName, durationMs / paramsList.size, true))
                    result.data
                }
                is SkillResult.ImageSuccess -> {
                    callLog.add(ToolCallRecord(toolName, durationMs / paramsList.size, true))
                    result.text
                }
                is SkillResult.Error -> {
                    callLog.add(ToolCallRecord(toolName, durationMs / paramsList.size, false))
                    throw RuntimeException(
                        "Tool '$toolName' failed on call ${index + 1}/${paramsList.size}: ${result.message}"
                    )
                }
                is SkillResult.RequiresApproval -> {
                    callLog.add(ToolCallRecord(toolName, durationMs / paramsList.size, false))
                    throw RuntimeException("Tool '$toolName' requires approval.")
                }
            }
        }
    }

    /**
     * Build a summary of all tool calls made during this execution,
     * appended to the code execution output for Claude's context.
     */
    fun buildCallSummary(): String? {
        if (callLog.isEmpty()) return null
        val sb = StringBuilder()
        sb.appendLine("\n--- Programmatic tool calls ---")
        for (record in callLog) {
            val status = if (record.success) "ok" else "FAILED"
            sb.appendLine("  ${record.toolName}: ${record.durationMs}ms [$status]")
        }
        sb.append("Total: ${callLog.size} call(s), ${callLog.sumOf { it.durationMs }}ms")
        return sb.toString()
    }

    // ── Java Map → JsonObject conversion ─────────────────────────────

    private fun mapToJsonObject(map: Map<String, Any?>): JsonObject {
        return JsonObject(map.mapValues { (_, v) -> toJsonElement(v) })
    }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toDouble())
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            mapToJsonObject(value as Map<String, Any?>)
        }
        is List<*> -> JsonArray(value.map { toJsonElement(it) })
        is Array<*> -> JsonArray(value.map { toJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }
}
