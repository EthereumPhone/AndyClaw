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
        description = buildString {
            appendLine("Run commands in the Termux terminal emulator's full Linux environment.")
            appendLine("Unlike the basic shell skill, Termux provides a complete Linux userland with a package manager.")
            appendLine()
            appendLine("Requirements:")
            appendLine("- Termux must be installed on the device (from F-Droid or GitHub releases)")
            appendLine("- The user must grant AndyClaw the RUN_COMMAND permission in Android settings")
            appendLine("- Termux must have been opened at least once to bootstrap its environment")
            appendLine()
            appendLine("Capabilities:")
            appendLine("- Full bash shell with standard Linux utilities")
            appendLine("- Package manager: pkg install <package> (or apt install)")
            appendLine("- Available packages: python, nodejs, git, curl, wget, ssh, gcc, clang, rust, go, ruby, etc.")
            appendLine("- Access to Termux home directory: ${TermuxCommandRunner.TERMUX_HOME}")
            appendLine("- Persistent environment: installed packages and files persist across calls")
            appendLine()
            appendLine("Common patterns:")
            appendLine("- Install Python: pkg install -y python")
            appendLine("- Run Python: python3 -c 'print(\"hello\")'")
            appendLine("- Install and use git: pkg install -y git && git clone <url>")
            appendLine("- Install Node.js: pkg install -y nodejs")
            appendLine("- Check installed packages: pkg list-installed")
            appendLine("- Update packages: pkg update -y && pkg upgrade -y")
        },
        tools = listOf(
            ToolDefinition(
                name = "termux_run_command",
                description = "Run a command in Termux's full Linux environment. Requires Termux to be installed. " +
                    "Use this instead of run_shell_command when you need tools not available in the basic Android " +
                    "shell (python, git, curl, gcc, etc.). Commands run as bash -c '<command>' in Termux's environment.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "command" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The command to execute in Termux's bash shell"),
                        )),
                        "timeout_ms" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Timeout in milliseconds (default 30000, max 300000). " +
                                    "Use higher values for package installations."
                            ),
                        )),
                        "workdir" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Working directory (default: Termux home ~)"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("command"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "termux_check_status",
                description = "Check if Termux is installed and available on this device. " +
                    "Use this before running commands to verify the environment is ready.",
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
