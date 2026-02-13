package org.ethereumphone.andyclaw.skills.builtin

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

class ShellSkill : AndyClawSkill {
    override val id = "shell"
    override val name = "Shell"

    companion object {
        private val BLOCKED_COMMANDS = setOf("rm -rf /", "mkfs", "dd if=/dev/zero")
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MAX_OUTPUT_CHARS = 50_000
    }

    override val baseManifest = SkillManifest(
        description = "Execute shell commands on the device. Restricted to safe commands on stock Android.",
        tools = listOf(
            ToolDefinition(
                name = "run_shell_command",
                description = "Run a shell command and return stdout/stderr. On stock Android, commands run as the app user with restricted access. On privileged OS, commands can run as root.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "command" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The shell command to execute"),
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
            if (exitCode == 0) {
                SkillResult.Success(result.toString())
            } else {
                SkillResult.Success(result.toString())
            }
        } catch (e: Exception) {
            SkillResult.Error("Failed to execute command: ${e.message}")
        }
    }
}
