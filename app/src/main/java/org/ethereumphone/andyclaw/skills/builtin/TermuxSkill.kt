package org.ethereumphone.andyclaw.skills.builtin

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
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
import java.util.UUID
import kotlin.coroutines.resume

class TermuxSkill(private val context: Context) : AndyClawSkill {
    override val id = "termux"
    override val name = "Termux"

    companion object {
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        private const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"

        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MAX_TIMEOUT_MS = 300_000L
        private const val MAX_OUTPUT_CHARS = 50_000

        private const val RESULT_ACTION_PREFIX = "org.ethereumphone.andyclaw.TERMUX_RESULT_"
    }

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
            appendLine("- Access to Termux home directory: $TERMUX_HOME")
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

    private fun isTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun checkStatus(): SkillResult {
        val installed = isTermuxInstalled()
        val result = buildJsonObject {
            put("installed", installed)
            if (installed) {
                try {
                    val info = context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
                    put("version", info.versionName ?: "unknown")
                    put("version_code", info.longVersionCode)
                } catch (_: Exception) { }
                put("termux_bin", TERMUX_BIN)
                put("termux_home", TERMUX_HOME)
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
        if (!isTermuxInstalled()) {
            return SkillResult.Error(
                "Termux is not installed. Ask the user to install Termux from F-Droid or GitHub releases."
            )
        }

        val command = params["command"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: command")
        val timeoutMs = params["timeout_ms"]?.jsonPrimitive?.intOrNull?.toLong()
            ?.coerceIn(1000, MAX_TIMEOUT_MS)
            ?: DEFAULT_TIMEOUT_MS
        val workdir = params["workdir"]?.jsonPrimitive?.contentOrNull ?: TERMUX_HOME

        return try {
            withTimeout(timeoutMs) {
                executeViaIntent(command, workdir)
            }
        } catch (_: TimeoutCancellationException) {
            SkillResult.Error(
                "Command timed out after ${timeoutMs}ms. The command may still be running in Termux. " +
                    "Try increasing timeout_ms for long-running operations like package installations."
            )
        } catch (e: Exception) {
            SkillResult.Error("Failed to execute Termux command: ${e.message}")
        }
    }

    /**
     * Sends a RUN_COMMAND intent to Termux's RunCommandService and waits for
     * the result via a PendingIntent callback broadcast.
     */
    private suspend fun executeViaIntent(
        command: String,
        workdir: String,
    ): SkillResult = suspendCancellableCoroutine { cont ->
        val requestId = UUID.randomUUID().toString()
        val action = "$RESULT_ACTION_PREFIX$requestId"

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                try {
                    context.unregisterReceiver(this)
                } catch (_: Exception) { }

                val result = extractResult(intent)
                if (cont.isActive) {
                    cont.resume(result)
                }
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter(action),
            Context.RECEIVER_NOT_EXPORTED,
        )

        val resultIntent = Intent(action).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestId.hashCode(),
            resultIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
        )

        val serviceIntent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, "$TERMUX_BIN/bash")
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_WORKDIR, workdir)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
        }

        try {
            context.startService(serviceIntent)
        } catch (e: Exception) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) { }

            if (cont.isActive) {
                cont.resume(
                    SkillResult.Error(
                        "Failed to start Termux service: ${e.message}. " +
                            "Make sure Termux is running and the RUN_COMMAND permission is granted to AndyClaw."
                    )
                )
            }
            return@suspendCancellableCoroutine
        }

        cont.invokeOnCancellation {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) { }
        }
    }

    /**
     * Extracts stdout, stderr, and exit code from the Termux result intent.
     * Handles both direct extras and the nested "result" Bundle format that
     * different Termux versions may use.
     */
    private fun extractResult(intent: Intent): SkillResult {
        val source: Bundle? = intent.getBundleExtra("result") ?: intent.extras

        val stdout = source?.getString("stdout") ?: ""
        val stderr = source?.getString("stderr") ?: ""
        val exitCode = source?.getInt("exitCode", -1) ?: -1
        val err = source?.getInt("err", 0) ?: 0
        val errmsg = source?.getString("errmsg")

        val result = buildJsonObject {
            put("exit_code", exitCode)
            put("stdout", stdout.take(MAX_OUTPUT_CHARS))
            if (stderr.isNotEmpty()) {
                put("stderr", stderr.take(MAX_OUTPUT_CHARS))
            }
            if (err != 0 && errmsg != null) {
                put("internal_error", errmsg)
            }
            if (stdout.length > MAX_OUTPUT_CHARS) put("stdout_truncated", true)
            if (stderr.length > MAX_OUTPUT_CHARS) put("stderr_truncated", true)
        }
        return SkillResult.Success(result.toString())
    }
}
