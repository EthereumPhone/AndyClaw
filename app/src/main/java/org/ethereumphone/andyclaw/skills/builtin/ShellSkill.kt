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

class ShellSkill(
    private val context: Context,
    private val isSafetyEnabled: () -> Boolean = { false },
) : AndyClawSkill {
    override val id = "shell"
    override val name = "Shell"

    private val workDir get() = context.filesDir

    companion object {
        private val BLOCKED_COMMANDS = setOf(
            "rm -rf /", "rm -rf /*", ":(){ :|:& };:", "dd if=/dev/zero",
            "mkfs", "chmod -R 777 /", "> /dev/sda",
            "curl | sh", "wget | sh", "curl | bash", "wget | bash",
        )

        private val DANGEROUS_SUBSTRING_PATTERNS = setOf(
            "sudo ", "doas ",
            "eval ", "\$(curl", "\$(wget",
            "/etc/passwd", "/etc/shadow", "~/.ssh", ".bash_history", "id_rsa",
        )

        private val DANGEROUS_REGEX_PATTERNS = listOf(
            Regex("""\|\s*sh(\s|;|&|$)""") to "pipe to sh",
            Regex("""\|\s*bash(\s|;|&|$)""") to "pipe to bash",
            Regex("""\|\s*zsh(\s|;|&|$)""") to "pipe to zsh",
        )

        private val NEVER_AUTO_APPROVE_PATTERNS = setOf(
            "rm -rf", "rm -fr", "chmod 777", "shutdown", "reboot",
            "iptables", "useradd", "passwd", "kill -9", "killall",
            "docker rm", "docker rmi", "git push --force", "git reset --hard",
            "DROP TABLE", "DELETE FROM", "TRUNCATE", "mkfs",
            "dd if=", "mount ", "umount ", "fdisk ", "parted ",
            "ip route", "ip addr", "iptables", "nft ",
            "curl -d @", "curl --data @", "curl --upload-file",
            "wget --post-file",
        )

        private val SAFE_ENV_VARS = setOf(
            "PATH", "HOME", "USER", "LOGNAME", "SHELL", "TERM",
            "LANG", "LC_ALL", "LC_CTYPE", "LC_MESSAGES",
            "PWD", "TMPDIR", "TMP", "TEMP",
            "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_STORAGE",
            "EXTERNAL_STORAGE", "ANDROID_BOOTLOGO",
        )

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
                description = "Run a shell command in the app sandbox directory (${context.filesDir.absolutePath}). Files written with write_file are available here. Example: after writing 'hello.sh', run it with 'sh hello.sh'.",
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

        val safetyEnabled = isSafetyEnabled()

        for (blocked in BLOCKED_COMMANDS) {
            if (command.contains(blocked, ignoreCase = true)) {
                return SkillResult.Error("Blocked dangerous command pattern: $blocked")
            }
        }

        if (safetyEnabled) {
            for (pattern in DANGEROUS_SUBSTRING_PATTERNS) {
                if (command.contains(pattern, ignoreCase = true)) {
                    return SkillResult.Error(
                        "[Safety] Command blocked — matched dangerous pattern: \"$pattern\". " +
                                "Disable safety mode in Settings to allow this command."
                    )
                }
            }

            for ((regex, description) in DANGEROUS_REGEX_PATTERNS) {
                if (regex.containsMatchIn(command)) {
                    return SkillResult.Error(
                        "[Safety] Command blocked — matched dangerous pattern: \"$description\". " +
                                "Disable safety mode in Settings to allow this command."
                    )
                }
            }

            val injectionReason = detectCommandInjection(command)
            if (injectionReason != null) {
                return SkillResult.Error(
                    "[Safety] Command blocked — $injectionReason. " +
                            "Disable safety mode in Settings to allow this command."
                )
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

            if (safetyEnabled) {
                scrubEnvironment(processBuilder)
            }

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

    private fun scrubEnvironment(processBuilder: ProcessBuilder) {
        val env = processBuilder.environment()
        val safeValues = SAFE_ENV_VARS.associateWith { env[it] }.filterValues { it != null }
        env.clear()
        for ((key, value) in safeValues) {
            if (value != null) env[key] = value
        }
    }

    private fun detectCommandInjection(command: String): String? {
        if ('\u0000' in command) {
            return "null bytes detected (bypass attempt)"
        }

        val lower = command.lowercase()

        if (lower.contains("base64") && hasPipeTo(lower, "sh", "bash", "zsh")) {
            return "base64-decode-to-shell pattern detected"
        }

        if ((lower.contains("printf") || lower.contains("echo")) &&
            lower.contains("\\x") && hasPipeTo(lower, "sh", "bash", "zsh")) {
            return "hex-escape-to-shell pattern detected"
        }

        if ((lower.contains("xxd") || lower.contains("od ")) &&
            hasPipeTo(lower, "sh", "bash", "zsh")) {
            return "binary-decode-to-shell pattern detected"
        }

        if (hasCommandToken(lower, "dig") && lower.contains("\$(")) {
            return "DNS exfiltration pattern detected"
        }

        if (hasCommandToken(lower, "nc") || hasCommandToken(lower, "ncat") ||
            hasCommandToken(lower, "netcat")) {
            if (lower.contains("|") || lower.contains("cat ")) {
                return "netcat data exfiltration pattern detected"
            }
        }

        val curlPostPatterns = listOf("-d @", "-d@", "--data @", "--data-binary @", "--upload-file")
        if (hasCommandToken(lower, "curl") && curlPostPatterns.any { lower.contains(it) }) {
            return "curl file upload/exfiltration pattern detected"
        }

        if (hasCommandToken(lower, "wget") && lower.contains("--post-file")) {
            return "wget file upload pattern detected"
        }

        if (lower.contains("rev") && hasPipeTo(lower, "sh", "bash", "zsh")) {
            return "string-reversal obfuscation pattern detected"
        }

        return null
    }

    private fun hasCommandToken(lower: String, token: String): Boolean {
        val separators = charArrayOf(' ', '|', ';', '&', '(', '\n', '\t')
        val idx = lower.indexOf(token)
        if (idx < 0) return false
        val before = if (idx == 0) ' ' else lower[idx - 1]
        val after = if (idx + token.length >= lower.length) ' ' else lower[idx + token.length]
        return before in separators && (after in separators || after == ' ')
    }

    private fun hasPipeTo(lower: String, vararg targets: String): Boolean {
        for (target in targets) {
            val pattern = "| $target"
            val idx = lower.indexOf(pattern)
            if (idx >= 0) {
                val afterIdx = idx + pattern.length
                if (afterIdx >= lower.length || lower[afterIdx] in charArrayOf(' ', '\n', '\t', ';', '|', '&')) {
                    return true
                }
            }
        }
        return false
    }
}
