package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class ShellSkill(private val context: Context) : AndyClawSkill {
    override val id = "shell"
    override val name = "Shell"

    private val workDir get() = context.filesDir

    companion object {
        private val BLOCKED_COMMANDS = setOf("rm -rf /", "mkfs", "dd if=/dev/zero")
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MAX_OUTPUT_CHARS = 50_000
    }

    override val baseManifest = SkillManifest(
        description = buildString {
            appendLine("Execute shell commands on the Android device.")
            appendLine("The working directory is the app sandbox: ${context.filesDir.absolutePath}")
            appendLine("Files created with write_file are in this directory and can be executed directly.")
            appendLine()
            appendLine("Common patterns:")
            appendLine("- Run a script you wrote: sh myscript.sh")
            appendLine("- Run Python (if installed): python3 myscript.py")
            appendLine("- Make executable and run: chmod +x myscript.sh && ./myscript.sh")
            appendLine("- Run inline code: echo 'Hello from Android!'")
            appendLine("- List sandbox files: ls -la")
            appendLine("- Check available tools: which python3 sh node dalvikvm")
            appendLine()
            appendLine("Android shell notes:")
            appendLine("- Standard POSIX shell (sh) is always available")
            appendLine("- The app runs as a normal user, not root (unless on privileged OS)")
            appendLine("- Common tools: ls, cat, cp, mv, mkdir, rm, chmod, echo, grep, sed, awk, wc, sort, head, tail, date, uname")
            appendLine("- Dalvik VM can run .dex files: dalvikvm -cp classes.dex ClassName")
        },
        tools = listOf(
            ToolDefinition(
                name = "run_shell_command",
                description = "Run a shell command in the app sandbox directory (${context.filesDir.absolutePath}). Files written with write_file are available here. Example: after writing 'hello.sh', run it with 'sh hello.sh'. On privileged OS, commands can run as root with as_root=true.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "command" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The shell command to execute (runs in app sandbox directory)"),
                        )),
                        "timeout_ms" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Timeout in milliseconds (default 30000, max 120000)"),
                        )),
                        "as_root" to JsonObject(mapOf(
                            "type" to JsonPrimitive("boolean"),
                            "description" to JsonPrimitive("Run as root (privileged OS only)"),
                        )),
                    )),
                    "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("command"))),
                )),
                requiresApproval = true,
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "run_shell_command" -> runCommand(params, tier)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun runCommand(params: JsonObject, tier: Tier): SkillResult {
        val command = params["command"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: command")
        val timeoutMs = params["timeout_ms"]?.jsonPrimitive?.intOrNull?.toLong()?.coerceIn(1000, 120_000)
            ?: DEFAULT_TIMEOUT_MS
        val asRoot = params["as_root"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        // Safety check
        for (blocked in BLOCKED_COMMANDS) {
            if (command.contains(blocked)) {
                return SkillResult.Error("Blocked dangerous command pattern: $blocked")
            }
        }

        if (asRoot && tier != Tier.PRIVILEGED) {
            return SkillResult.Error("Root access requires privileged OS tier.")
        }

        return try {
            val processBuilder = if (asRoot) {
                ProcessBuilder("su", "-c", command)
            } else {
                ProcessBuilder("sh", "-c", command)
            }
            processBuilder.directory(workDir)
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (output.length < MAX_OUTPUT_CHARS) {
                    output.appendLine(line)
                }
            }

            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                return SkillResult.Error("Command timed out after ${timeoutMs}ms")
            }

            val exitCode = process.exitValue()
            val result = buildJsonObject {
                put("exit_code", exitCode)
                put("output", output.toString().take(MAX_OUTPUT_CHARS))
                if (output.length > MAX_OUTPUT_CHARS) {
                    put("truncated", true)
                }
            }
            SkillResult.Success(result.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to execute command: ${e.message}")
        }
    }
}
