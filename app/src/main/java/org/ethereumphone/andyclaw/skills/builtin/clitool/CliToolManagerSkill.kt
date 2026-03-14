package org.ethereumphone.andyclaw.skills.builtin.clitool

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.skills.termux.TermuxCommandRunner
import java.io.File

/**
 * Skill that lets the AI agent register, configure, and use arbitrary CLI tools
 * installed via Termux. Supports tools that ship with SKILL.md documentation
 * (like opensea-cli, Google Workspace CLI, etc.) as well as any CLI tool
 * with --help output.
 */
class CliToolManagerSkill(
    private val context: Context,
    private val runner: TermuxCommandRunner,
) : AndyClawSkill {

    companion object {
        private const val TAG = "CliToolManagerSkill"
        private const val CLI_TOOLS_DIR = "cli-tools"
        private const val FETCH_TIMEOUT_MS = 120_000L
        private const val RUN_TIMEOUT_DEFAULT = 30_000L
        private const val RUN_TIMEOUT_MAX = 300_000L
    }

    private val registry by lazy { CliToolRegistry(File(context.filesDir, CLI_TOOLS_DIR)) }
    private val configStore by lazy { CliToolConfigStore(context) }

    override val id = "cli-tool-manager"
    override val name = "CLI Tool Manager"

    override val baseManifest = SkillManifest(
        description = buildString {
            appendLine("Register, configure, and run external CLI tools installed in Termux.")
            appendLine("Use cli_tools_list to see registered tools and cli_tools_info to learn how to use them.")
            appendLine("Supports tools with SKILL.md documentation (e.g. opensea-cli, gws) and any CLI with --help.")
        },
        tools = listOf(
            // ── cli_tools_list ──
            ToolDefinition(
                name = "cli_tools_list",
                description = "List all registered CLI tools with their status: whether the binary is known, " +
                    "and whether required environment variables are configured.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                },
            ),
            // ── cli_tools_add ──
            ToolDefinition(
                name = "cli_tools_add",
                description = "Register a new CLI tool. Fetches and caches its SKILL.md documentation " +
                    "from a git repo, npm package, or local path. The tool becomes available via " +
                    "cli_tools_info and cli_tools_run.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") {
                            put("type", "string")
                            put("description", "Unique identifier for this tool (e.g. 'opensea', 'gws')")
                        }
                        putJsonObject("name") {
                            put("type", "string")
                            put("description", "Human-readable name (e.g. 'OpenSea CLI')")
                        }
                        putJsonObject("source_type") {
                            put("type", "string")
                            put("description", "Where to fetch docs from: 'git', 'npm', or 'local'")
                        }
                        putJsonObject("source_value") {
                            put("type", "string")
                            put("description", "Git repo URL, npm package name, or local directory path")
                        }
                        putJsonObject("binary_name") {
                            put("type", "string")
                            put("description", "Command name in PATH (e.g. 'opensea', 'gws'). Optional.")
                        }
                        putJsonObject("install_command") {
                            put("type", "string")
                            put(
                                "description",
                                "Command to install the tool in Termux (e.g. 'npm install -g @opensea/cli'). Optional."
                            )
                        }
                        putJsonObject("env_var_keys") {
                            put("type", "string")
                            put(
                                "description",
                                "Comma-separated list of environment variable names this tool needs " +
                                    "(e.g. 'OPENSEA_API_KEY,OPENSEA_CHAIN'). Optional."
                            )
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("id"))
                        add(JsonPrimitive("name"))
                        add(JsonPrimitive("source_type"))
                        add(JsonPrimitive("source_value"))
                    }
                },
                requiresApproval = true,
            ),
            // ── cli_tools_remove ──
            ToolDefinition(
                name = "cli_tools_remove",
                description = "Unregister a CLI tool and remove its cached documentation and configuration.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") {
                            put("type", "string")
                            put("description", "ID of the tool to remove")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("id")) }
                },
                requiresApproval = true,
            ),
            // ── cli_tools_info ──
            ToolDefinition(
                name = "cli_tools_info",
                description = "Get the SKILL.md documentation for a registered CLI tool. " +
                    "Use this to learn how to use the tool before running commands. " +
                    "If the tool has multiple SKILL.md files, specify which one with the 'file' parameter.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") {
                            put("type", "string")
                            put("description", "ID of the registered tool")
                        }
                        putJsonObject("file") {
                            put("type", "string")
                            put(
                                "description",
                                "Relative path to a specific SKILL.md file (for tools with multiple). " +
                                    "Omit to get the first/only one, or 'list' to see all available files."
                            )
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("id")) }
                },
            ),
            // ── cli_tools_configure ──
            ToolDefinition(
                name = "cli_tools_configure",
                description = "Store API keys and environment variables for a CLI tool (encrypted). " +
                    "These are automatically injected when running the tool via cli_tools_run.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") {
                            put("type", "string")
                            put("description", "ID of the tool to configure")
                        }
                        putJsonObject("env_vars") {
                            put("type", "object")
                            put(
                                "description",
                                "Key-value map of environment variables (e.g. {\"OPENSEA_API_KEY\": \"sk-...\"})"
                            )
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("id"))
                        add(JsonPrimitive("env_vars"))
                    }
                },
                requiresApproval = true,
            ),
            // ── cli_tools_install ──
            ToolDefinition(
                name = "cli_tools_install",
                description = "Install a registered CLI tool's binary in Termux. Uses the tool's stored " +
                    "install command or a provided override.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") {
                            put("type", "string")
                            put("description", "ID of the tool to install")
                        }
                        putJsonObject("command") {
                            put("type", "string")
                            put("description", "Override install command (uses stored command if omitted)")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("id")) }
                },
                requiresApproval = true,
            ),
            // ── cli_tools_run ──
            ToolDefinition(
                name = "cli_tools_run",
                description = "Execute a command with a registered CLI tool. The tool's configured " +
                    "environment variables (API keys, etc.) are automatically injected.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") {
                            put("type", "string")
                            put("description", "ID of the registered tool")
                        }
                        putJsonObject("command") {
                            put("type", "string")
                            put("description", "The full command to execute (e.g. 'opensea nfts list --owner 0x...')")
                        }
                        putJsonObject("timeout_ms") {
                            put("type", "integer")
                            put("description", "Timeout in milliseconds (default 30000, max 300000)")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("id"))
                        add(JsonPrimitive("command"))
                    }
                },
                requiresApproval = true,
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    // ── Dispatch ────────────────────────────────────────────────────────

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        Log.d(TAG, "Executing tool: $tool")
        return when (tool) {
            "cli_tools_list" -> executeList()
            "cli_tools_add" -> executeAdd(params)
            "cli_tools_remove" -> executeRemove(params)
            "cli_tools_info" -> executeInfo(params)
            "cli_tools_configure" -> executeConfigure(params)
            "cli_tools_install" -> executeInstall(params)
            "cli_tools_run" -> executeRun(params)
            else -> SkillResult.Error("Unknown cli-tool-manager tool: $tool")
        }
    }

    // ── cli_tools_list ──────────────────────────────────────────────────

    private fun executeList(): SkillResult {
        val entries = registry.getAll()
        if (entries.isEmpty()) {
            return SkillResult.Success(
                "No CLI tools registered. Use cli_tools_add to register a tool " +
                    "(from a git repo, npm package, or local path)."
            )
        }

        val sb = StringBuilder("## Registered CLI Tools (${entries.size})\n\n")
        for (entry in entries) {
            val configured = configStore.isConfigured(entry.id, entry.envVarKeys)
            val statusParts = mutableListOf<String>()
            if (entry.binaryName != null) statusParts.add("binary: ${entry.binaryName}")
            if (entry.envVarKeys.isNotEmpty()) {
                statusParts.add(if (configured) "configured" else "needs config: ${entry.envVarKeys.joinToString()}")
            }
            if (entry.skillMdFiles.isNotEmpty()) statusParts.add("${entry.skillMdFiles.size} doc(s)")

            sb.appendLine("- **${entry.name}** [id: ${entry.id}]")
            sb.appendLine("  Source: ${entry.sourceType}:${entry.sourceValue}")
            if (statusParts.isNotEmpty()) sb.appendLine("  Status: ${statusParts.joinToString(" | ")}")
            if (entry.installCommand != null) sb.appendLine("  Install: `${entry.installCommand}`")
            sb.appendLine()
        }
        return SkillResult.Success(sb.toString().trimEnd())
    }

    // ── cli_tools_add ───────────────────────────────────────────────────

    private suspend fun executeAdd(params: JsonObject): SkillResult {
        val id = params["id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: id")
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: name")
        val sourceType = params["source_type"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: source_type")
        val sourceValue = params["source_value"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: source_value")

        if (sourceType !in listOf("git", "npm", "local")) {
            return SkillResult.Error("source_type must be 'git', 'npm', or 'local'")
        }

        val binaryName = params["binary_name"]?.jsonPrimitive?.contentOrNull
        val installCommand = params["install_command"]?.jsonPrimitive?.contentOrNull
        val envVarKeys = params["env_var_keys"]?.jsonPrimitive?.contentOrNull
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()

        val entry = CliToolEntry(
            id = id,
            name = name,
            sourceType = sourceType,
            sourceValue = sourceValue,
            binaryName = binaryName,
            installCommand = installCommand,
            envVarKeys = envVarKeys,
        )
        registry.add(entry)

        // Attempt to fetch SKILL.md documentation
        val fetchResult = fetchSkillMd(entry)
        val updatedEntry = registry.get(id) ?: entry

        val sb = StringBuilder("Registered CLI tool '$name' (id: $id).\n")
        if (updatedEntry.skillMdFiles.isNotEmpty()) {
            sb.appendLine("Cached ${updatedEntry.skillMdFiles.size} SKILL.md file(s).")
            sb.appendLine("Use cli_tools_info to read the documentation.")
        } else {
            sb.appendLine("Warning: Could not fetch SKILL.md documentation.")
            if (fetchResult != null) sb.appendLine("Reason: $fetchResult")
            sb.appendLine("The tool is registered but you may need to use cli_tools_info " +
                "with the tool installed to get --help output as fallback.")
        }
        if (envVarKeys.isNotEmpty()) {
            sb.appendLine("Required env vars: ${envVarKeys.joinToString()}. Use cli_tools_configure to set them.")
        }
        if (installCommand != null) {
            sb.appendLine("Install with: cli_tools_install")
        }
        return SkillResult.Success(sb.toString().trimEnd())
    }

    // ── cli_tools_remove ────────────────────────────────────────────────

    private fun executeRemove(params: JsonObject): SkillResult {
        val id = params["id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: id")

        val entry = registry.get(id)
            ?: return SkillResult.Error("No CLI tool registered with id '$id'.")

        configStore.removeAllForTool(id)
        registry.remove(id)

        return SkillResult.Success("Removed CLI tool '${entry.name}' (id: $id) and cleared its configuration.")
    }

    // ── cli_tools_info ──────────────────────────────────────────────────

    private suspend fun executeInfo(params: JsonObject): SkillResult {
        val id = params["id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: id")
        val file = params["file"]?.jsonPrimitive?.contentOrNull

        val entry = registry.get(id)
            ?: return SkillResult.Error("No CLI tool registered with id '$id'.")

        // List all available doc files
        if (file == "list") {
            if (entry.skillMdFiles.isEmpty()) {
                return SkillResult.Success("No documentation files cached for '${entry.name}'. " +
                    "The tool may need to be re-added or installed to generate --help docs.")
            }
            val sb = StringBuilder("## Documentation files for ${entry.name}\n\n")
            for (path in entry.skillMdFiles) {
                sb.appendLine("- `$path`")
            }
            sb.appendLine("\nUse cli_tools_info with file='<path>' to read a specific file.")
            return SkillResult.Success(sb.toString().trimEnd())
        }

        // Determine which file to read
        val targetFile = file ?: entry.skillMdFiles.firstOrNull()

        if (targetFile != null) {
            val content = registry.getSkillMdContent(id, targetFile)
            if (content != null) {
                return SkillResult.Success("## ${entry.name} — $targetFile\n\n$content")
            }
        }

        // Fallback: try --help via Termux
        if (entry.binaryName != null && runner.isTermuxInstalled()) {
            Log.d(TAG, "No cached docs for '$id', falling back to --help")
            val helpResult = runner.run("${entry.binaryName} --help 2>&1", timeoutMs = 15_000)
            if (helpResult.isSuccess && helpResult.stdout.isNotBlank()) {
                // Cache the help output for next time
                registry.saveSkillMd(id, "HELP.md", helpResult.stdout)
                val updated = entry.copy(skillMdFiles = entry.skillMdFiles + "HELP.md")
                registry.update(updated)
                return SkillResult.Success("## ${entry.name} — --help output\n\n${helpResult.stdout}")
            }
        }

        return SkillResult.Error(
            "No documentation available for '${entry.name}'. " +
                "Try re-adding the tool or installing it first so --help can be captured."
        )
    }

    // ── cli_tools_configure ─────────────────────────────────────────────

    private fun executeConfigure(params: JsonObject): SkillResult {
        val id = params["id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: id")
        val envVarsJson = params["env_vars"]?.jsonObject
            ?: return SkillResult.Error("Missing required parameter: env_vars (must be a JSON object)")

        val entry = registry.get(id)
            ?: return SkillResult.Error("No CLI tool registered with id '$id'.")

        val setKeys = mutableListOf<String>()
        for ((key, value) in envVarsJson) {
            val strValue = (value as? JsonPrimitive)?.contentOrNull
                ?: continue
            configStore.setEnvVar(id, key, strValue)
            setKeys.add(key)
        }

        val allConfigured = configStore.isConfigured(id, entry.envVarKeys)
        val sb = StringBuilder("Configured ${setKeys.size} env var(s) for '${entry.name}': ${setKeys.joinToString()}\n")
        if (entry.envVarKeys.isNotEmpty()) {
            sb.appendLine(if (allConfigured) "All required variables are set." else {
                val missing = entry.envVarKeys.filter { configStore.getEnvVar(id, it) == null }
                "Still missing: ${missing.joinToString()}"
            })
        }
        return SkillResult.Success(sb.toString().trimEnd())
    }

    // ── cli_tools_install ───────────────────────────────────────────────

    private suspend fun executeInstall(params: JsonObject): SkillResult {
        val id = params["id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: id")
        val commandOverride = params["command"]?.jsonPrimitive?.contentOrNull

        val entry = registry.get(id)
            ?: return SkillResult.Error("No CLI tool registered with id '$id'.")

        if (!runner.isTermuxInstalled()) {
            return SkillResult.Error(
                "Termux is not installed. Install it from F-Droid " +
                    "(https://f-droid.org/packages/com.termux/) and open it once to bootstrap."
            )
        }

        val installCmd = commandOverride ?: entry.installCommand
            ?: return SkillResult.Error(
                "No install command available for '${entry.name}'. " +
                    "Provide a 'command' parameter (e.g. 'npm install -g @opensea/cli')."
            )

        val result = runner.run(installCmd, timeoutMs = FETCH_TIMEOUT_MS)
        if (result.internalError != null) {
            return SkillResult.Error("Install failed: ${result.internalError}")
        }

        val sb = StringBuilder()
        if (result.isSuccess) {
            sb.appendLine("Installation command completed successfully.")
            registry.update(entry.copy(installedAt = System.currentTimeMillis()))

            // Verify binary is available
            if (entry.binaryName != null) {
                val which = runner.run("which ${entry.binaryName}", timeoutMs = 5_000)
                if (which.isSuccess && which.stdout.isNotBlank()) {
                    sb.appendLine("Binary '${entry.binaryName}' found at: ${which.stdout.trim()}")
                } else {
                    sb.appendLine("Warning: '${entry.binaryName}' not found in PATH after install.")
                }
            }
        } else {
            sb.appendLine("Install command exited with code ${result.exitCode}.")
            if (result.stdout.isNotBlank()) sb.appendLine("stdout: ${result.stdout.take(2000)}")
            if (result.stderr.isNotBlank()) sb.appendLine("stderr: ${result.stderr.take(2000)}")
        }
        return SkillResult.Success(sb.toString().trimEnd())
    }

    // ── cli_tools_run ───────────────────────────────────────────────────

    private suspend fun executeRun(params: JsonObject): SkillResult {
        val id = params["id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: id")
        val command = params["command"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: command")
        val timeoutMs = params["timeout_ms"]?.jsonPrimitive?.intOrNull?.toLong()
            ?: RUN_TIMEOUT_DEFAULT

        val entry = registry.get(id)
            ?: return SkillResult.Error("No CLI tool registered with id '$id'.")

        if (!runner.isTermuxInstalled()) {
            return SkillResult.Error("Termux is not installed.")
        }

        // Build env var preamble
        val envVars = configStore.getAllEnvVars(id)
        val envPreamble = if (envVars.isNotEmpty()) {
            envVars.entries.joinToString(" ") { (k, v) ->
                // Shell-escape the value
                "$k='${v.replace("'", "'\\''")}'"
            } + " "
        } else ""

        val wrappedCommand = "export ${envPreamble}; $command"
        val effectiveTimeout = timeoutMs.coerceIn(1_000, RUN_TIMEOUT_MAX)

        val result = runner.run(wrappedCommand, timeoutMs = effectiveTimeout)

        if (result.internalError != null) {
            return SkillResult.Error(result.internalError)
        }

        val json = buildJsonObject {
            put("exit_code", result.exitCode)
            put("stdout", result.stdout)
            if (result.stderr.isNotEmpty()) put("stderr", result.stderr)
            if (result.stdout.length >= TermuxCommandRunner.MAX_OUTPUT_CHARS) put("stdout_truncated", true)
            if (result.stderr.length >= TermuxCommandRunner.MAX_OUTPUT_CHARS) put("stderr_truncated", true)
        }
        return SkillResult.Success(json.toString())
    }

    // ── SKILL.md fetching ───────────────────────────────────────────────

    /**
     * Fetches SKILL.md files from the tool's source and caches them locally.
     * Returns an error message if fetching failed, null on success.
     */
    private suspend fun fetchSkillMd(entry: CliToolEntry): String? {
        return when (entry.sourceType) {
            "git" -> fetchFromGit(entry)
            "npm" -> fetchFromNpm(entry)
            "local" -> fetchFromLocal(entry)
            else -> "Unknown source type: ${entry.sourceType}"
        }
    }

    private suspend fun fetchFromGit(entry: CliToolEntry): String? {
        if (!runner.isTermuxInstalled()) {
            return "Termux is not installed (needed for git clone)."
        }

        val tmpDir = "/data/data/com.termux/files/home/.cli-tool-tmp/${entry.id}"
        try {
            // Ensure git is available, clone, find SKILL.md files
            val cloneCmd = buildString {
                append("pkg install -y git 2>/dev/null; ")
                append("rm -rf $tmpDir && ")
                append("git clone --depth 1 ${entry.sourceValue} $tmpDir 2>/dev/null && ")
                append("find $tmpDir -name 'SKILL.md' -type f")
            }
            val result = runner.run(cloneCmd, timeoutMs = FETCH_TIMEOUT_MS)
            if (!result.isSuccess || result.stdout.isBlank()) {
                return "git clone failed or no SKILL.md found. Exit code: ${result.exitCode}. " +
                    result.stderr.take(500)
            }

            val paths = result.stdout.lines().filter { it.contains("SKILL.md") }
            return cacheSkillMdFiles(entry, tmpDir, paths)
        } finally {
            // Cleanup temp dir
            runner.run("rm -rf $tmpDir", timeoutMs = 10_000)
        }
    }

    private suspend fun fetchFromNpm(entry: CliToolEntry): String? {
        if (!runner.isTermuxInstalled()) {
            return "Termux is not installed (needed for npm)."
        }

        val tmpDir = "/data/data/com.termux/files/home/.cli-tool-tmp/${entry.id}"
        try {
            val cmd = buildString {
                append("pkg install -y nodejs 2>/dev/null; ")
                append("rm -rf $tmpDir && mkdir -p $tmpDir && ")
                append("cd $tmpDir && ")
                append("npm pack ${entry.sourceValue} 2>/dev/null && ")
                append("tar -xzf *.tgz 2>/dev/null && ")
                append("find $tmpDir -name 'SKILL.md' -type f")
            }
            val result = runner.run(cmd, timeoutMs = FETCH_TIMEOUT_MS)
            if (!result.isSuccess || result.stdout.isBlank()) {
                return "npm pack failed or no SKILL.md found. Exit code: ${result.exitCode}. " +
                    result.stderr.take(500)
            }

            val paths = result.stdout.lines().filter { it.contains("SKILL.md") }
            return cacheSkillMdFiles(entry, tmpDir, paths)
        } finally {
            runner.run("rm -rf $tmpDir", timeoutMs = 10_000)
        }
    }

    private fun fetchFromLocal(entry: CliToolEntry): String? {
        val sourceDir = File(entry.sourceValue)
        if (!sourceDir.exists()) {
            return "Local path does not exist: ${entry.sourceValue}"
        }

        val skillFiles = sourceDir.walkTopDown()
            .filter { it.name == "SKILL.md" && it.isFile }
            .toList()

        if (skillFiles.isEmpty()) {
            return "No SKILL.md files found in ${entry.sourceValue}"
        }

        val relativePaths = mutableListOf<String>()
        for (file in skillFiles) {
            val relativePath = file.relativeTo(sourceDir).path
            registry.saveSkillMd(entry.id, relativePath, file.readText())
            relativePaths.add(relativePath)
        }

        registry.update(entry.copy(skillMdFiles = relativePaths))
        return null // success
    }

    /**
     * Reads SKILL.md files from a Termux temp directory and caches them in the registry.
     */
    private suspend fun cacheSkillMdFiles(
        entry: CliToolEntry,
        tmpDir: String,
        absolutePaths: List<String>,
    ): String? {
        if (absolutePaths.isEmpty()) {
            return "No SKILL.md files found."
        }

        val relativePaths = mutableListOf<String>()
        for (absPath in absolutePaths) {
            val trimmed = absPath.trim()
            if (trimmed.isEmpty()) continue

            // Read the file content via Termux
            val catResult = runner.run("cat '$trimmed'", timeoutMs = 10_000)
            if (!catResult.isSuccess || catResult.stdout.isBlank()) continue

            // Compute a relative path for storage
            val relativePath = trimmed.removePrefix("$tmpDir/")
                .ifEmpty { "SKILL.md" }

            registry.saveSkillMd(entry.id, relativePath, catResult.stdout)
            relativePaths.add(relativePath)
        }

        if (relativePaths.isEmpty()) {
            return "Found SKILL.md files but could not read them."
        }

        registry.update(entry.copy(skillMdFiles = relativePaths))
        return null // success
    }
}
