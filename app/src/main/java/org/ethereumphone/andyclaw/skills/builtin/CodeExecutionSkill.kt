package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import bsh.EvalError
import bsh.Interpreter
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.ExecutionEngine.currentProvenance
import org.ethereumphone.andyclaw.ExecutionEngine.rethrowIfCancelled
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.skills.ToolEffect
import java.io.PrintStream
import java.util.concurrent.TimeoutException
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

class CodeExecutionSkill(
    private val context: Context,
    private val registryProvider: (() -> org.ethereumphone.andyclaw.skills.NativeSkillRegistry)? = null,
    private val tierProvider: (() -> Tier)? = null,
    private val enabledSkillIdsProvider: (() -> Set<String>)? = null,
    /** Reads the provenance-enforcement setting; log-only when it returns false. */
    private val enforceProvenanceProvider: (() -> Boolean)? = null,
) : AndyClawSkill {
    override val id = "code_execution"
    override val name = "Code Execution"

    companion object {
        private const val TAG = "CodeExecutionSkill"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MAX_TIMEOUT_MS = 120_000L
        private const val MAX_OUTPUT_CHARS = 50_000
    }

    override val baseManifest = SkillManifest(
        description = buildString {
            appendLine("Execute Java/BeanShell code directly on the Android device.")
            appendLine("BeanShell is a lightweight Java interpreter running in-process with full access to the Android classpath.")
            appendLine()
            appendLine("Use this when you need to:")
            appendLine("- Access Android APIs directly (ContentResolver, PackageManager, TelephonyManager, etc.)")
            appendLine("- Perform complex data processing that would be awkward in shell commands")
            appendLine("- Interact with system services, databases, or device hardware programmatically")
            appendLine("- Run computations or algorithms that need a real programming language")
            appendLine()
            appendLine("Pre-bound variables (only in runs the user started; background runs get just `tools`):")
            appendLine("- context: android.content.Context (the application context)")
            appendLine("- packageManager: android.content.pm.PackageManager")
            appendLine("- contentResolver: android.content.ContentResolver")
            appendLine("- filesDir: java.io.File (app sandbox directory)")
            appendLine()
            appendLine("Programmatic tool calling — call other tools from your code:")
            appendLine("  HashMap params = new HashMap();")
            appendLine("  params.put(\"name\", \"alice.eth\");")
            appendLine("  String result = tools.call(\"resolve_ens\", params);")
            appendLine()
            appendLine("  // Parallel batch (all calls run concurrently):")
            appendLine("  ArrayList paramsList = new ArrayList();")
            appendLine("  for (String name : names) {")
            appendLine("    HashMap p = new HashMap(); p.put(\"name\", name);")
            appendLine("    paramsList.add(p);")
            appendLine("  }")
            appendLine("  List results = tools.callParallel(\"resolve_ens\", paramsList);")
            appendLine()
            appendLine("- Use tools.call() when results feed into the next step")
            appendLine("- Use tools.callParallel() when calls are independent")
            appendLine("- Combine both for multi-stage pipelines")
            appendLine("- Tools requiring user approval cannot be called from code")
            appendLine()
            appendLine("IMPORTANT — BeanShell syntax rules:")
            appendLine("- BeanShell is Java 1.5 — do NOT use Map.of(), List.of(), var, or lambda expressions")
            appendLine("- Use new HashMap() and new ArrayList() instead")
            appendLine("- Use print() or System.out.println() for output")
            appendLine("- The last expression's value is returned as return_value")
            appendLine("- Each execution runs in a fresh interpreter (no state persists between calls)")
            appendLine("- Import any class on the Android classpath: import android.os.Build;")
        },
        tools = listOf(
            ToolDefinition(
                name = "execute_code",
                description = "Execute Java/BeanShell code on the device (Java 1.5 syntax — NO Map.of/List.of/var/lambdas, use new HashMap()/ArrayList()). Pre-bound in user-started runs: context, packageManager, contentResolver, filesDir. Call other tools: tools.call(name, hashMap) or tools.callParallel(name, arrayList) for batch. Use for multi-tool pipelines in one shot.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "code" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Java/BeanShell code to execute"),
                        )),
                        "timeout_ms" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Timeout in milliseconds (default 30000, max 120000)"),
                        )),
                    )),
                    "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("code"))),
                )),
                requiresApproval = true,
                // Also reaches every other tool through ToolBridge — see ToolBridge.call().
                effect = ToolEffect.IRREVERSIBLE,
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            // Capture provenance here, while still on the agent's coroutine. Below
            // this point execution moves to a BeanShell executor thread and
            // `runBlocking`, neither of which inherits the coroutine context — so the
            // run's context goes along explicitly too: its token (the display lease,
            // STOP), the provenance the tools re-check, the autopilot's handle on the run.
            "execute_code" -> executeCode(
                params,
                currentProvenance(),
                currentCoroutineContext().minusKey(Job).minusKey(ContinuationInterceptor),
            )
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private suspend fun executeCode(params: JsonObject, provenance: Provenance, runContext: CoroutineContext): SkillResult {
        val code = params["code"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: code")
        val timeoutMs = params["timeout_ms"]?.jsonPrimitive?.intOrNull?.toLong()
            ?.coerceIn(1000, MAX_TIMEOUT_MS) ?: DEFAULT_TIMEOUT_MS

        // Bytes; UTF-8 needs at most 4 a char, so MAX_OUTPUT_CHARS always fits.
        val outputStream = BoundedOutputStream(MAX_OUTPUT_CHARS * 4)
        val printStream = PrintStream(outputStream, true, "UTF-8")

        val startTime = System.currentTimeMillis()

        // Create ToolBridge for programmatic tool calling (if registry is available)
        val toolBridge = if (registryProvider != null && tierProvider != null && enabledSkillIdsProvider != null) {
            ToolBridge(
                registry = registryProvider.invoke(),
                tier = tierProvider.invoke(),
                enabledSkillIds = enabledSkillIdsProvider.invoke(),
                provenance = provenance,
                enforceProvenance = enforceProvenanceProvider?.invoke() ?: true,
                runContext = runContext,
            )
        } else null

        // The raw Android handles bypass every gate ToolBridge applies (provenance, privacy,
        // egress): with `filesDir` a stranger-triggered run could rewrite
        // trigger_provenance.json, with `contentResolver` read SMS for a reply to someone else.
        // So only a run the user started gets them; everything else reaches the device through
        // `tools` alone.
        // Residual: BeanShell sees the whole classpath, so determined code can still reach a
        // Context reflectively (ActivityThread.currentApplication()). This removes the handed-over
        // capability, not the sandbox escape; ProvenanceGate classifying execute_code as
        // IRREVERSIBLE (approval for background runs) is what actually bounds it.
        val bindAndroidHandles = provenance == Provenance.USER
        val future = SandboxThread.start("execute_code") {
            val interpreter = Interpreter(null, printStream, printStream, false)
            if (bindAndroidHandles) {
                interpreter.set("context", context)
                interpreter.set("packageManager", context.packageManager)
                interpreter.set("contentResolver", context.contentResolver)
                interpreter.set("filesDir", context.filesDir)
            }
            if (toolBridge != null) {
                interpreter.set("tools", toolBridge)
            }
            interpreter.eval(code)
        }

        return try {
            val returnValue = SandboxThread.await(future, timeoutMs)
            val executionTimeMs = System.currentTimeMillis() - startTime
            var output = outputStream.toString()
            // Append programmatic call summary if tools were called
            toolBridge?.buildCallSummary()?.let { summary ->
                output += summary
                android.util.Log.i(TAG, "Programmatic tool calls: ${toolBridge.callLog.size} call(s), " +
                    "${toolBridge.callLog.sumOf { it.durationMs }}ms total")
            }
            val truncated = output.length > MAX_OUTPUT_CHARS || outputStream.overflowed

            val result = buildJsonObject {
                if (returnValue != null) {
                    put("return_value", returnValue.toString())
                    put("return_type", returnValue.javaClass.name)
                } else {
                    put("return_value", null as String?)
                    put("return_type", "void")
                }
                put("output", output.take(MAX_OUTPUT_CHARS))
                if (truncated) put("truncated", true)
                put("execution_time_ms", executionTimeMs)
                if (toolBridge != null && toolBridge.callLog.isNotEmpty()) {
                    put("tool_calls", toolBridge.callLog.size)
                }
            }
            SkillResult.Success(result.toString())
        } catch (e: TimeoutException) {
            // SandboxThread.await already interrupted it; a loop that ignores interrupts keeps
            // its own thread and no longer blocks the next call.
            val executionTimeMs = System.currentTimeMillis() - startTime
            val output = outputStream.toString()
            val result = buildJsonObject {
                put("error_type", "timeout")
                put("error_message", "Code execution timed out after ${timeoutMs}ms")
                put("output", output.take(MAX_OUTPUT_CHARS))
                put("execution_time_ms", executionTimeMs)
            }
            SkillResult.Error(result.toString())
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            val executionTimeMs = System.currentTimeMillis() - startTime
            val output = outputStream.toString()
            val cause = e.cause
            val (errorType, errorMessage) = when (cause) {
                is EvalError -> "eval_error" to (cause.message ?: "BeanShell evaluation error")
                else -> "execution_error" to (cause?.message ?: e.message ?: "Unknown error")
            }
            val result = buildJsonObject {
                put("error_type", errorType)
                put("error_message", errorMessage)
                put("output", output.take(MAX_OUTPUT_CHARS))
                put("execution_time_ms", executionTimeMs)
            }
            SkillResult.Error(result.toString())
        }
    }
}
