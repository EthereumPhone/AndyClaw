package org.ethereumphone.andyclaw.skills.customtools

import android.content.Context
import bsh.EvalError
import bsh.Interpreter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.skills.SkillResult
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class CustomToolExecutor(private val context: Context) {

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MAX_TIMEOUT_MS = 120_000L
        private const val MAX_OUTPUT_CHARS = 50_000
    }

    private val executor = Executors.newSingleThreadExecutor()

    fun execute(code: String, params: JsonObject, timeoutMs: Long = DEFAULT_TIMEOUT_MS): SkillResult {
        val effectiveTimeout = timeoutMs.coerceIn(1000, MAX_TIMEOUT_MS)

        val outputStream = ByteArrayOutputStream()
        val printStream = PrintStream(outputStream)

        val startTime = System.currentTimeMillis()

        val future = executor.submit<Any?> {
            val interpreter = Interpreter(null, printStream, printStream, false)
            // Pre-bind Android context variables (same as CodeExecutionSkill)
            interpreter.set("context", context)
            interpreter.set("packageManager", context.packageManager)
            interpreter.set("contentResolver", context.contentResolver)
            interpreter.set("filesDir", context.filesDir)

            // Bind tool parameters as named variables
            for ((key, element) in params) {
                val value = resolveParamValue(element)
                interpreter.set(key, value)
            }

            interpreter.eval(code)
        }

        return try {
            val returnValue = future.get(effectiveTimeout, TimeUnit.MILLISECONDS)
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
                put("error_message", "Code execution timed out after ${effectiveTimeout}ms")
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

    private fun resolveParamValue(element: kotlinx.serialization.json.JsonElement): Any? {
        if (element is kotlinx.serialization.json.JsonNull) return null
        if (element is kotlinx.serialization.json.JsonPrimitive) {
            element.booleanOrNull?.let { return it }
            element.intOrNull?.let { return it }
            element.doubleOrNull?.let { return it }
            element.contentOrNull?.let { return it }
            return element.toString()
        }
        // For arrays and objects, pass as JSON string
        return element.toString()
    }
}
