package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import bsh.EvalError
import bsh.Interpreter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class CodeExecutionSkill(private val context: Context) : AndyClawSkill {
    override val id = "code_execution"
    override val name = "Code Execution"

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MAX_TIMEOUT_MS = 120_000L
        private const val MAX_OUTPUT_CHARS = 50_000
    }

    private val executor = Executors.newSingleThreadExecutor()

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
            appendLine("Pre-bound variables available in every execution:")
            appendLine("- context: android.content.Context (the application context)")
            appendLine("- packageManager: android.content.pm.PackageManager")
            appendLine("- contentResolver: android.content.ContentResolver")
            appendLine("- filesDir: java.io.File (app sandbox directory)")
            appendLine()
            appendLine("Notes:")
            appendLine("- Code is standard Java syntax (BeanShell supports Java 1.5+ with scripting extensions)")
            appendLine("- Use print() or System.out.println() for output")
            appendLine("- The last expression's value is returned as return_value")
            appendLine("- Each execution runs in a fresh interpreter (no state persists between calls)")
            appendLine("- Import any class on the Android classpath: import android.os.Build;")
        },
        tools = listOf(
            ToolDefinition(
                name = "execute_code",
                description = "Execute Java/BeanShell code on the device. The code runs in-process with full access to the Android classpath and pre-bound variables (context, packageManager, contentResolver, filesDir). Use print()/System.out.println() for output. The last expression value is captured as return_value.",
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
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "execute_code" -> executeCode(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun executeCode(params: JsonObject): SkillResult {
        val code = params["code"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: code")
        val timeoutMs = params["timeout_ms"]?.jsonPrimitive?.intOrNull?.toLong()
            ?.coerceIn(1000, MAX_TIMEOUT_MS) ?: DEFAULT_TIMEOUT_MS

        val outputStream = ByteArrayOutputStream()
        val printStream = PrintStream(outputStream)

        val startTime = System.currentTimeMillis()

        val future = executor.submit<Any?> {
            val interpreter = Interpreter(null, printStream, printStream, false)
            interpreter.set("context", context)
            interpreter.set("packageManager", context.packageManager)
            interpreter.set("contentResolver", context.contentResolver)
            interpreter.set("filesDir", context.filesDir)
            interpreter.eval(code)
        }

        return try {
            val returnValue = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            val executionTimeMs = System.currentTimeMillis() - startTime
            val output = outputStream.toString("UTF-8")
            val truncated = output.length > MAX_OUTPUT_CHARS

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
            }
            SkillResult.Success(result.toString())
        } catch (e: TimeoutException) {
            future.cancel(true)
            val executionTimeMs = System.currentTimeMillis() - startTime
            val output = outputStream.toString("UTF-8")
            val result = buildJsonObject {
                put("error_type", "timeout")
                put("error_message", "Code execution timed out after ${timeoutMs}ms")
                put("output", output.take(MAX_OUTPUT_CHARS))
                put("execution_time_ms", executionTimeMs)
            }
            SkillResult.Error(result.toString())
        } catch (e: Exception) {
            val executionTimeMs = System.currentTimeMillis() - startTime
            val output = outputStream.toString("UTF-8")
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
