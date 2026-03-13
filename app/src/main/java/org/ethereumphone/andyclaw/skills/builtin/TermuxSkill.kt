package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import kotlinx.serialization.json.JsonArray
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
import org.ethereumphone.andyclaw.skills.termux.TermuxCommandRunner

class TermuxSkill(
    private val context: Context,
    private val runner: TermuxCommandRunner = TermuxCommandRunner(context),
) : AndyClawSkill {

    override val id = "termux"
    override val name = "Termux"

    override val baseManifest = SkillManifest(
        description = "Run commands in Termux's full Linux environment with package manager (pkg install python/nodejs/git/etc). " +
            "Termux must be installed and opened once. Packages and files persist across calls.",
        tools = listOf(
            ToolDefinition(
                name = "termux_run_command",
                description = "Run a command in Termux's bash shell (use instead of run_shell_command for python, git, curl, gcc, etc).",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "command" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                        )),
                        "timeout_ms" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Default 30000, max 300000"),
                        )),
                        "workdir" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Default: Termux home ~"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("command"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "termux_check_status",
                description = "Check if Termux is installed and available on this device.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(emptyMap()),
                )),
                requiresApproval = false,
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "termux_run_command" -> runCommand(params)
            "termux_check_status" -> checkStatus()
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun checkStatus(): SkillResult {
        val installed = runner.isTermuxInstalled()
        val result = buildJsonObject {
            put("installed", installed)
            if (installed) {
                val (version, code) = runner.getVersionInfo()
                if (version != null) put("version", version)
                if (code != null) put("version_code", code)
                put("termux_bin", TermuxCommandRunner.TERMUX_BIN)
                put("termux_home", TermuxCommandRunner.TERMUX_HOME)
                put("hint", "Run termux_run_command with 'echo ok' to verify the environment is working.")
            } else {
                put(
                    "hint",
                    "Termux is not installed. The user should install it from F-Droid " +
                        "(https://f-droid.org/packages/com.termux/) or GitHub releases " +
                        "(https://github.com/termux/termux-app/releases). " +
                        "After installing, open Termux once to let it bootstrap its environment."
                )
            }
        }
        return SkillResult.Success(result.toString())
    }

    private suspend fun runCommand(params: JsonObject): SkillResult {
        val command = params["command"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: command")
        val timeoutMs = params["timeout_ms"]?.jsonPrimitive?.intOrNull?.toLong()
            ?: TermuxCommandRunner.DEFAULT_TIMEOUT_MS
        val workdir = params["workdir"]?.jsonPrimitive?.contentOrNull
            ?: TermuxCommandRunner.TERMUX_HOME

        val result = runner.run(command, workdir, timeoutMs)

        if (result.internalError != null) {
            return SkillResult.Error(result.internalError)
        }

        val json = buildJsonObject {
            put("exit_code", result.exitCode)
            put("stdout", result.stdout)
            if (result.stderr.isNotEmpty()) put("stderr", result.stderr)
            if (result.stdout.length >= TermuxCommandRunner.MAX_OUTPUT_CHARS)
                put("stdout_truncated", true)
            if (result.stderr.length >= TermuxCommandRunner.MAX_OUTPUT_CHARS)
                put("stderr_truncated", true)
        }
        return SkillResult.Success(json.toString())
    }
}
