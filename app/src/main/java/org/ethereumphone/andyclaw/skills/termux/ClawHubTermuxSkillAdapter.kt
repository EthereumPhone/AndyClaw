package org.ethereumphone.andyclaw.skills.termux

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.Skill
import org.ethereumphone.andyclaw.skills.SkillExecutionSpec
import org.ethereumphone.andyclaw.skills.SkillFrontmatter
import org.ethereumphone.andyclaw.skills.SkillLoader
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillMetadata
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.SkillToolSpec
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.extensions.clawhub.ClawHubLockFile
import org.ethereumphone.andyclaw.extensions.clawhub.ClawHubSkillAdapter
import java.io.File

/**
 * Bridges a ClawHub skill that declares `execution.type: termux` into the
 * [AndyClawSkill] interface with **real tool invocations** instead of
 * instruction-only read access.
 *
 * On the first tool call the adapter will:
 *  1. Sync the skill's files into Termux home (`~/.andyclaw/skills/<slug>/`)
 *  2. Install any missing required binaries via `pkg install`
 *  3. Run the declared `setup` script (if any)
 *
 * Subsequent calls skip the sync phase and go straight to execution.
 *
 * ## Execution contract
 *
 * For tools with ≤3 simple string args the entrypoint receives them as
 * positional arguments:
 * ```
 * ~/.andyclaw/skills/<slug>/scripts/jarvis "Hello world"
 * ```
 *
 * For more complex tools the entrypoint receives the tool name + JSON:
 * ```
 * ~/.andyclaw/skills/<slug>/main.sh tool_name '{"key":"value"}'
 * ```
 */
class ClawHubTermuxSkillAdapter(
    private val skill: Skill,
    val slug: String,
    private val installedVersion: String?,
    private val executionSpec: SkillExecutionSpec,
    private val metadata: SkillMetadata?,
    private val runner: TermuxCommandRunner,
    private val sync: TermuxSkillSync,
) : AndyClawSkill {

    companion object {
        private const val TAG = "ClawHubTermuxAdapter"

        /**
         * Factory: create the correct adapter type for every installed
         * ClawHub skill.  Skills with `execution.type == "termux"` get a
         * [ClawHubTermuxSkillAdapter]; everything else gets the original
         * instruction-only [ClawHubSkillAdapter].
         */
        fun createAdaptersForInstalledSkills(
            managedDir: File,
            runner: TermuxCommandRunner,
            sync: TermuxSkillSync,
        ): List<AndyClawSkill> {
            if (!managedDir.isDirectory) return emptyList()

            val lock = ClawHubLockFile(managedDir).also { it.load() }

            return managedDir.listFiles()
                ?.filter { it.isDirectory && File(it, "SKILL.md").isFile }
                ?.mapNotNull { dir ->
                    val slugName = dir.name
                    val safeSlug = runCatching { TermuxShell.validateSlug(slugName) }
                        .getOrElse {
                            Log.w(TAG, "Skipping skill with invalid slug '$slugName': ${it.message}")
                            return@mapNotNull null
                        }

                    val parsedSkill = SkillLoader.parseSkillFile(
                        File(dir, "SKILL.md"), dir,
                    ) ?: run {
                        Log.w(TAG, "Skipping invalid skill in ${dir.absolutePath}")
                        return@mapNotNull null
                    }

                    val frontmatter = try {
                        SkillFrontmatter.parse(parsedSkill.content)
                    } catch (_: Exception) { emptyMap() }

                    val meta = SkillFrontmatter.resolveMetadata(frontmatter)
                    val version = lock.getEntry(slugName)?.version
                    val exec = meta?.execution

                    if (exec != null && exec.type == "termux") {
                        ClawHubTermuxSkillAdapter(
                            skill = parsedSkill,
                            slug = safeSlug,
                            installedVersion = version,
                            executionSpec = exec,
                            metadata = meta,
                            runner = runner,
                            sync = sync,
                        )
                    } else {
                        ClawHubSkillAdapter(parsedSkill, slugName, version)
                    }
                }
                ?: emptyList()
        }
    }

    // ── AndyClawSkill implementation ────────────────────────────────

    override val id: String = "clawhub:$slug"

    override val name: String = skill.name

    override val baseManifest: SkillManifest = SkillManifest(
        description = buildDescription(),
        tools = buildToolDefinitions(),
    )

    override val privilegedManifest: SkillManifest? = null

    @Volatile
    private var synced = false

    @Volatile
    private var setupDone = false

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        // Gate: Termux must be installed
        if (!runner.isTermuxInstalled()) {
            return SkillResult.Error(
                "Termux is required to run the '$name' skill but is not installed. " +
                    "Install Termux from F-Droid or GitHub releases, open it once, " +
                    "then grant AndyClaw the RUN_COMMAND permission."
            )
        }

        // Lazy sync on first call
        if (!synced) {
            val syncResult = ensureReady()
            if (syncResult != null) return syncResult
        }

        // Resolve the tool spec
        val toolSpec = resolveToolSpec(tool)
            ?: return SkillResult.Error("Unknown tool: $tool")

        // Build and execute the command
        val command = try {
            buildCommand(toolSpec, params)
        } catch (e: IllegalArgumentException) {
            return SkillResult.Error("Invalid Termux execution spec: ${e.message}")
        }

        val skillHome = try {
            sync.skillHomePath(slug)
        } catch (e: IllegalArgumentException) {
            return SkillResult.Error("Invalid Termux skill path for '$slug': ${e.message}")
        }

        val result = runner.run(command, workdir = skillHome, timeoutMs = 60_000)

        if (result.internalError != null) {
            return SkillResult.Error("${name}: ${result.internalError}")
        }

        return SkillResult.Success(buildJsonObject {
            put("exit_code", result.exitCode)
            put("stdout", result.stdout)
            if (result.stderr.isNotEmpty()) put("stderr", result.stderr)
        }.toString())
    }

    // ── Lazy initialisation ─────────────────────────────────────────

    private suspend fun ensureReady(): SkillResult? {
        // 1. Sync files
        val sourceDir = File(skill.baseDir)
        val syncResult = sync.syncSkill(slug, sourceDir)
        if (!syncResult.success) {
            return SkillResult.Error("Failed to sync '$name' to Termux: ${syncResult.error}")
        }
        synced = true

        // 2. Install required binaries
        val bins = metadata?.requires?.bins ?: emptyList()
        if (bins.isNotEmpty()) {
            val installResult = sync.ensureBins(bins)
            if (!installResult.isSuccess) {
                Log.w(TAG, "Dependency install warning for $slug: ${installResult.stderr}")
            }
        }

        // 3. Run setup script (once)
        if (!setupDone && executionSpec.setup != null) {
            val setupResult = sync.runSetup(slug, executionSpec.setup!!)
            if (!setupResult.isSuccess) {
                Log.w(TAG, "Setup script warning for $slug: ${setupResult.stderr}")
            }
            setupDone = true
        }

        return null
    }

    // ── Tool resolution ─────────────────────────────────────────────

    private fun resolveToolSpec(toolName: String): SkillToolSpec? {
        // Match by generated tool name (prefixed with skill slug)
        return executionSpec.tools.find { toolKey(it) == toolName }
    }

    private fun toolKey(spec: SkillToolSpec): String =
        "${sanitize(slug)}_${sanitize(spec.name)}"

    // ── Command building ────────────────────────────────────────────

    private fun buildCommand(toolSpec: SkillToolSpec, params: JsonObject): String {
        val rawEntrypoint = toolSpec.entrypoint ?: executionSpec.entrypoint
        val entrypoint = TermuxShell.validateRelativePath(rawEntrypoint, "entrypoint")
        val scriptPath = "${sync.skillHomePath(slug)}/$entrypoint"
        val quotedScriptPath = TermuxShell.quote(scriptPath)

        val allSimpleStrings = toolSpec.args.values.all { it.type == "string" }
        val argCount = toolSpec.args.size

        return if (argCount <= 3 && allSimpleStrings) {
            // Positional mode: pass each arg value directly
            val positional = toolSpec.args.keys.mapNotNull { key ->
                params[key]?.jsonPrimitive?.contentOrNull
            }
            val escaped = positional.joinToString(" ") { TermuxShell.quote(it) }
            if (escaped.isBlank()) quotedScriptPath else "$quotedScriptPath $escaped"
        } else {
            // JSON mode: entrypoint <tool> '<json>'
            "$quotedScriptPath ${TermuxShell.quote(toolSpec.name)} ${TermuxShell.quote(params.toString())}"
        }
    }

    // ── Manifest / tool definition builders ─────────────────────────

    private fun buildDescription(): String = buildString {
        append("ClawHub skill: ${skill.name}")
        if (!installedVersion.isNullOrBlank()) append(" v$installedVersion")
        if (skill.description.isNotBlank()) append(" — ${skill.description}")
        append(" [executable via Termux]")
    }

    private fun buildToolDefinitions(): List<ToolDefinition> {
        return executionSpec.tools.map { toolSpec ->
            ToolDefinition(
                name = toolKey(toolSpec),
                description = buildToolDescription(toolSpec),
                inputSchema = buildInputSchema(toolSpec),
                requiresApproval = true,
            )
        }
    }

    private fun buildToolDescription(spec: SkillToolSpec): String {
        val base = spec.description.ifBlank { "Run '${spec.name}' from the ${skill.name} skill" }
        return "$base (ClawHub / Termux)"
    }

    private fun buildInputSchema(spec: SkillToolSpec): JsonObject {
        val properties = mutableMapOf<String, JsonObject>()
        val required = mutableListOf<JsonPrimitive>()

        for ((argName, argSpec) in spec.args) {
            properties[argName] = JsonObject(buildMap {
                put("type", JsonPrimitive(argSpec.type))
                if (argSpec.description.isNotBlank()) {
                    put("description", JsonPrimitive(argSpec.description))
                }
            })
            if (argSpec.required) required.add(JsonPrimitive(argName))
        }

        return JsonObject(buildMap {
            put("type", JsonPrimitive("object"))
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) put("required", JsonArray(required))
        })
    }

    private fun sanitize(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(32)
            .ifEmpty { "skill" }
}
