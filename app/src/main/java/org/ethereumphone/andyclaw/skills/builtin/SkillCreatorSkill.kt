package org.ethereumphone.andyclaw.skills.builtin

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.Skill
import org.ethereumphone.andyclaw.skills.SkillFrontmatter
import org.ethereumphone.andyclaw.skills.SkillLoader
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Meta-skill that lets the AI agent author new SKILL.md-based skills at runtime.
 *
 * Created skills are stored in [aiSkillsDir] and immediately registered in the
 * [NativeSkillRegistry] via [onSkillsChanged]. They follow the exact same SKILL.md
 * format as ClawHub and bundled skills, so they participate in prompt assembly,
 * command resolution, and the standard skill discovery pipeline.
 *
 * The agent can inspect existing builtin, ClawHub, and extension skills for
 * reference before creating new ones.
 */
class SkillCreatorSkill(
    private val aiSkillsDir: File,
    private val clawHubSkillsDir: File,
    private val nativeSkillRegistry: NativeSkillRegistry,
    private val onSkillsChanged: () -> Unit,
) : AndyClawSkill {

    companion object {
        private const val TAG = "SkillCreatorSkill"
        private val SLUG_REGEX = Regex("^[a-z0-9][a-z0-9-]*[a-z0-9]$|^[a-z0-9]$")
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

        /**
         * Create instruction-only adapters for all AI-created skills on disk.
         * Called by NodeApp during [syncAiSkills] to register them.
         */
        fun createAdaptersFromDir(aiSkillsDir: File): List<AndyClawSkill> {
            if (!aiSkillsDir.isDirectory) return emptyList()
            return aiSkillsDir.listFiles()
                ?.filter { it.isDirectory && File(it, "SKILL.md").isFile }
                ?.mapNotNull { dir ->
                    val skill = SkillLoader.parseSkillFile(File(dir, "SKILL.md"), dir)
                        ?: return@mapNotNull null
                    AiSkillAdapter(skill, dir.name)
                }
                ?: emptyList()
        }
    }

    override val id = "skill-creator"
    override val name = "Skill Creator"

    override val baseManifest = SkillManifest(
        description = "Create new skills at runtime that persist across conversations. " +
            "Study existing skills with skill_list_references and skill_read_source before creating. " +
            "Created skills immediately become available as tools the agent can invoke.",
        tools = listOf(
            ToolDefinition(
                name = "skill_create",
                description = "Create a new SKILL.md-based skill. The skill follows the standard AndyClaw/OpenClaw " +
                    "format and becomes immediately available. Study existing skills first for best results.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") {
                            put("type", "string")
                            put("description", "Human-readable display name (e.g. 'Battery Optimizer')")
                        }
                        putJsonObject("slug") {
                            put("type", "string")
                            put("description", "URL-safe identifier: lowercase letters, numbers, hyphens only (e.g. 'battery-optimizer')")
                        }
                        putJsonObject("description") {
                            put("type", "string")
                            put("description", "Short one-line description of what the skill does")
                        }
                        putJsonObject("instructions") {
                            put("type", "string")
                            put("description", "Full markdown body with detailed instructions the agent should follow when this skill is invoked. " +
                                "Include step-by-step guidance, examples, edge cases, and Android-specific considerations.")
                        }
                        putJsonObject("emoji") {
                            put("type", "string")
                            put("description", "Single emoji representing the skill (optional)")
                        }
                        putJsonObject("user_invocable") {
                            put("type", "boolean")
                            put("description", "Whether users can invoke via /command (default: true)")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("name"))
                        add(JsonPrimitive("slug"))
                        add(JsonPrimitive("description"))
                        add(JsonPrimitive("instructions"))
                    }
                },
            ),
            ToolDefinition(
                name = "skill_write_file",
                description = "Write an additional file into an AI-created skill's directory. " +
                    "Use this for scripts, config files, or supporting resources alongside the SKILL.md.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("slug") {
                            put("type", "string")
                            put("description", "Slug of the AI-created skill to add the file to")
                        }
                        putJsonObject("file_path") {
                            put("type", "string")
                            put("description", "Relative file path within the skill directory (e.g. 'main.sh', 'config/settings.json')")
                        }
                        putJsonObject("content") {
                            put("type", "string")
                            put("description", "File content to write")
                        }
                        putJsonObject("executable") {
                            put("type", "boolean")
                            put("description", "Mark the file as executable (for shell scripts, default: false)")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("slug"))
                        add(JsonPrimitive("file_path"))
                        add(JsonPrimitive("content"))
                    }
                },
            ),
            ToolDefinition(
                name = "skill_list_references",
                description = "List all available skills (builtin, ClawHub, AI-created) with their descriptions. " +
                    "Use this to study the ecosystem before creating a new skill, to find skills to reference, " +
                    "or to check for naming conflicts.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("type_filter") {
                            put("type", "string")
                            put("description", "Filter by source: 'builtin', 'clawhub', 'ai', or 'all' (default: 'all')")
                        }
                    }
                },
            ),
            ToolDefinition(
                name = "skill_read_source",
                description = "Read the full source of a skill for reference. " +
                    "For SKILL.md skills (ClawHub/AI): returns the complete SKILL.md content including frontmatter. " +
                    "For builtin skills: returns the manifest with tool definitions and schemas. " +
                    "Use this to understand how existing skills are structured before creating new ones.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("skill_id") {
                            put("type", "string")
                            put("description", "Skill ID (e.g. 'memory', 'clawhub:calendar-skill', 'ai:my-skill') or just the slug/name")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("skill_id")) }
                },
            ),
            ToolDefinition(
                name = "skill_list_created",
                description = "List all AI-created skills with their slugs, names, and descriptions.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                },
            ),
            ToolDefinition(
                name = "skill_delete",
                description = "Delete an AI-created skill by its slug. Only AI-created skills can be deleted this way.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("slug") {
                            put("type", "string")
                            put("description", "Slug of the AI-created skill to delete")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("slug")) }
                },
                requiresApproval = true,
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        Log.d(TAG, "Executing tool: $tool")
        return when (tool) {
            "skill_create" -> executeCreate(params)
            "skill_write_file" -> executeWriteFile(params)
            "skill_list_references" -> executeListReferences(params)
            "skill_read_source" -> executeReadSource(params)
            "skill_list_created" -> executeListCreated()
            "skill_delete" -> executeDelete(params)
            else -> SkillResult.Error("Unknown skill-creator tool: $tool")
        }
    }

    // ── skill_create ────────────────────────────────────────────────────

    private fun executeCreate(params: JsonObject): SkillResult {
        val name = params["name"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: name")
        val slug = params["slug"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: slug")
        val description = params["description"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: description")
        val instructions = params["instructions"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: instructions")
        val emoji = params["emoji"]?.jsonPrimitive?.content ?: "🤖"
        val userInvocable = params["user_invocable"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

        if (!SLUG_REGEX.matches(slug)) {
            return SkillResult.Error(
                "Invalid slug '$slug'. Must be lowercase letters, numbers, and hyphens only " +
                    "(e.g. 'my-cool-skill'). Cannot start or end with a hyphen."
            )
        }

        if (slug.length > 64) {
            return SkillResult.Error("Slug too long (max 64 characters): '$slug'")
        }

        // Check for conflicts with existing AI skills
        val targetDir = File(aiSkillsDir, slug)
        if (targetDir.isDirectory) {
            return SkillResult.Error(
                "An AI skill with slug '$slug' already exists. " +
                    "Use skill_delete first, or choose a different slug."
            )
        }

        // Check for conflicts with ClawHub skills
        if (File(clawHubSkillsDir, slug).isDirectory) {
            return SkillResult.Error(
                "A ClawHub skill with slug '$slug' is already installed. " +
                    "Choose a different slug to avoid conflicts."
            )
        }

        val timestamp = DATE_FMT.format(Date())
        val skillMd = buildString {
            appendLine("---")
            appendLine("name: \"${escapeYaml(name)}\"")
            appendLine("description: \"${escapeYaml(description)}\"")
            appendLine("user-invocable: $userInvocable")
            appendLine("disable-model-invocation: false")
            appendLine("metadata:")
            appendLine("  andyclaw:")
            appendLine("    emoji: \"$emoji\"")
            appendLine("    source: ai-created")
            appendLine("    created_at: \"$timestamp\"")
            appendLine("---")
            appendLine()
            append(instructions)
        }

        return try {
            targetDir.mkdirs()
            File(targetDir, "SKILL.md").writeText(skillMd)

            onSkillsChanged()

            Log.i(TAG, "Created AI skill '$name' ($slug)")
            SkillResult.Success(
                "Skill '$name' created successfully.\n" +
                    "  Slug: $slug\n" +
                    "  Location: ${targetDir.absolutePath}\n" +
                    "  Tool: read_ai_skill_${sanitizeName(slug)}\n\n" +
                    "The skill is now available and can be invoked by the agent."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create skill '$slug': ${e.message}", e)
            targetDir.deleteRecursively()
            SkillResult.Error("Failed to create skill: ${e.message}")
        }
    }

    // ── skill_write_file ────────────────────────────────────────────────

    private fun executeWriteFile(params: JsonObject): SkillResult {
        val slug = params["slug"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: slug")
        val filePath = params["file_path"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: file_path")
        val content = params["content"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: content")
        val executable = params["executable"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        val skillDir = File(aiSkillsDir, slug)
        if (!skillDir.isDirectory) {
            return SkillResult.Error("AI skill '$slug' does not exist. Create it first with skill_create.")
        }

        // Prevent path traversal
        val normalizedPath = filePath.replace("\\", "/")
        if (normalizedPath.contains("..") || normalizedPath.startsWith("/")) {
            return SkillResult.Error("Invalid file path: must be a relative path without '..' components")
        }

        // Don't allow overwriting SKILL.md (use skill_create for that)
        if (normalizedPath == "SKILL.md") {
            return SkillResult.Error("Cannot overwrite SKILL.md via skill_write_file. Use skill_delete + skill_create to replace.")
        }

        return try {
            val target = File(skillDir, normalizedPath)
            target.parentFile?.mkdirs()
            target.writeText(content)
            if (executable) {
                target.setExecutable(true, false)
            }

            Log.i(TAG, "Wrote file '$filePath' to skill '$slug'")
            SkillResult.Success("File written: $filePath (${content.length} bytes${if (executable) ", executable" else ""})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write file '$filePath' to skill '$slug': ${e.message}", e)
            SkillResult.Error("Failed to write file: ${e.message}")
        }
    }

    // ── skill_list_references ───────────────────────────────────────────

    private fun executeListReferences(params: JsonObject): SkillResult {
        val filter = params["type_filter"]?.jsonPrimitive?.content?.lowercase() ?: "all"
        val sb = StringBuilder()

        if (filter == "all" || filter == "builtin") {
            val builtins = nativeSkillRegistry.getAll()
                .filter { !it.id.startsWith("clawhub:") && !it.id.startsWith("ai:") }
            sb.appendLine("## Builtin Skills (${builtins.size})\n")
            for (skill in builtins) {
                val toolCount = skill.baseManifest.tools.size +
                    (skill.privilegedManifest?.tools?.size ?: 0)
                sb.appendLine("- **${skill.name}** [id: ${skill.id}] ($toolCount tools)")
                sb.appendLine("  ${skill.baseManifest.description.take(200)}")
            }
            sb.appendLine()
        }

        if (filter == "all" || filter == "clawhub") {
            val clawHubSkills = scanSkillDir(clawHubSkillsDir)
            sb.appendLine("## ClawHub Skills (${clawHubSkills.size})\n")
            if (clawHubSkills.isEmpty()) {
                sb.appendLine("- No ClawHub skills installed")
            }
            for ((slug, skill) in clawHubSkills) {
                sb.appendLine("- **${skill.name}** [slug: $slug]")
                sb.appendLine("  ${skill.description.take(200)}")
            }
            sb.appendLine()
        }

        if (filter == "all" || filter == "ai") {
            val aiSkills = scanSkillDir(aiSkillsDir)
            sb.appendLine("## AI-Created Skills (${aiSkills.size})\n")
            if (aiSkills.isEmpty()) {
                sb.appendLine("- No AI-created skills yet")
            }
            for ((slug, skill) in aiSkills) {
                sb.appendLine("- **${skill.name}** [slug: $slug]")
                sb.appendLine("  ${skill.description.take(200)}")
            }
            sb.appendLine()
        }

        return SkillResult.Success(sb.toString().trimEnd())
    }

    // ── skill_read_source ───────────────────────────────────────────────

    private fun executeReadSource(params: JsonObject): SkillResult {
        val skillId = params["skill_id"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: skill_id")

        // Try as a builtin skill first (by ID or name)
        val builtin = nativeSkillRegistry.getAll().find {
            it.id == skillId || it.name.equals(skillId, ignoreCase = true)
        }
        if (builtin != null && !builtin.id.startsWith("clawhub:") && !builtin.id.startsWith("ai:")) {
            return SkillResult.Success(formatBuiltinSkill(builtin))
        }

        // Try as a ClawHub skill
        val clawHubSlug = skillId.removePrefix("clawhub:")
        val clawHubDir = File(clawHubSkillsDir, clawHubSlug)
        val clawHubFile = File(clawHubDir, "SKILL.md")
        if (clawHubFile.isFile) {
            return SkillResult.Success(
                "# ClawHub Skill: $clawHubSlug\n\n" +
                    "```markdown\n${clawHubFile.readText()}\n```"
            )
        }

        // Try as an AI-created skill
        val aiSlug = skillId.removePrefix("ai:")
        val aiDir = File(aiSkillsDir, aiSlug)
        val aiFile = File(aiDir, "SKILL.md")
        if (aiFile.isFile) {
            return SkillResult.Success(
                "# AI-Created Skill: $aiSlug\n\n" +
                    "```markdown\n${aiFile.readText()}\n```"
            )
        }

        // Fuzzy match: try matching by name across all SKILL.md skills
        val allSkills = scanSkillDir(clawHubSkillsDir) + scanSkillDir(aiSkillsDir)
        val fuzzyMatch = allSkills.entries.find {
            it.value.name.equals(skillId, ignoreCase = true)
        }
        if (fuzzyMatch != null) {
            val file = File(fuzzyMatch.value.baseDir, "SKILL.md")
            if (file.isFile) {
                return SkillResult.Success(
                    "# Skill: ${fuzzyMatch.value.name} (${fuzzyMatch.key})\n\n" +
                        "```markdown\n${file.readText()}\n```"
                )
            }
        }

        return SkillResult.Error(
            "Skill not found: '$skillId'. Use skill_list_references to see available skills."
        )
    }

    // ── skill_list_created ──────────────────────────────────────────────

    private fun executeListCreated(): SkillResult {
        val aiSkills = scanSkillDir(aiSkillsDir)
        if (aiSkills.isEmpty()) {
            return SkillResult.Success("No AI-created skills yet. Use skill_create to make one.")
        }

        val sb = StringBuilder("## AI-Created Skills (${aiSkills.size})\n\n")
        for ((slug, skill) in aiSkills) {
            val frontmatter = SkillFrontmatter.parse(skill.content)
            val meta = SkillFrontmatter.resolveMetadata(frontmatter)
            val emoji = meta?.emoji ?: "🤖"

            sb.appendLine("$emoji **${skill.name}** [slug: $slug]")
            sb.appendLine("  Description: ${skill.description}")
            sb.appendLine("  Tool: read_ai_skill_${sanitizeName(slug)}")
            sb.appendLine("  Location: ${skill.baseDir}")

            val extraFiles = File(skill.baseDir).listFiles()
                ?.filter { it.name != "SKILL.md" && it.name != "REFINEMENT.md" }
                ?.map { it.name }
            if (!extraFiles.isNullOrEmpty()) {
                sb.appendLine("  Extra files: ${extraFiles.joinToString(", ")}")
            }
            sb.appendLine()
        }

        return SkillResult.Success(sb.toString().trimEnd())
    }

    // ── skill_delete ────────────────────────────────────────────────────

    private fun executeDelete(params: JsonObject): SkillResult {
        val slug = params["slug"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: slug")

        val targetDir = File(aiSkillsDir, slug)
        if (!targetDir.isDirectory) {
            return SkillResult.Error("AI skill '$slug' not found. Use skill_list_created to see available skills.")
        }

        return try {
            val skillName = SkillLoader.parseSkillFile(File(targetDir, "SKILL.md"), targetDir)?.name ?: slug
            targetDir.deleteRecursively()
            onSkillsChanged()

            Log.i(TAG, "Deleted AI skill '$slug'")
            SkillResult.Success("Skill '$skillName' ($slug) deleted and unregistered.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete skill '$slug': ${e.message}", e)
            SkillResult.Error("Failed to delete skill: ${e.message}")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun scanSkillDir(dir: File): Map<String, Skill> {
        if (!dir.isDirectory) return emptyMap()
        val result = linkedMapOf<String, Skill>()
        dir.listFiles()
            ?.filter { it.isDirectory && File(it, "SKILL.md").isFile }
            ?.sortedBy { it.name }
            ?.forEach { subdir ->
                SkillLoader.parseSkillFile(File(subdir, "SKILL.md"), subdir)?.let {
                    result[subdir.name] = it
                }
            }
        return result
    }

    private fun formatBuiltinSkill(skill: AndyClawSkill): String {
        return buildString {
            appendLine("# Builtin Skill: ${skill.name}")
            appendLine("ID: ${skill.id}")
            appendLine("Type: builtin (native Kotlin)")
            appendLine()
            appendLine("## Description")
            appendLine(skill.baseManifest.description)
            appendLine()
            appendLine("## Tools (base tier)")
            for (tool in skill.baseManifest.tools) {
                appendLine()
                appendLine("### ${tool.name}")
                appendLine(tool.description)
                formatToolSchema(tool, this)
            }
            skill.privilegedManifest?.let { priv ->
                if (priv.tools.isNotEmpty()) {
                    appendLine()
                    appendLine("## Tools (privileged tier)")
                    for (tool in priv.tools) {
                        appendLine()
                        appendLine("### ${tool.name}")
                        appendLine(tool.description)
                        formatToolSchema(tool, this)
                    }
                }
            }
        }
    }

    private fun formatToolSchema(tool: ToolDefinition, sb: StringBuilder) {
        val props = try { tool.inputSchema["properties"]?.jsonObject } catch (_: Exception) { null }
        val required = try {
            tool.inputSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
        } catch (_: Exception) { null } ?: emptySet()

        if (props != null && props.isNotEmpty()) {
            sb.appendLine("Parameters:")
            for ((key, value) in props) {
                val obj = try { value.jsonObject } catch (_: Exception) { continue }
                val type = obj["type"]?.jsonPrimitive?.content ?: "any"
                val desc = obj["description"]?.jsonPrimitive?.content ?: ""
                val req = if (key in required) " (required)" else ""
                sb.appendLine("  - $key ($type$req): $desc")
            }
        }
    }

    private fun escapeYaml(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}

// ── AI Skill Adapter ────────────────────────────────────────────────────

private fun sanitizeName(raw: String): String =
    raw.lowercase()
        .replace(Regex("[^a-z0-9_]+"), "_")
        .replace(Regex("_+"), "_")
        .trimStart('_').trimEnd('_')
        .take(32)
        .ifEmpty { "skill" }

/**
 * Bridges an AI-created SKILL.md into [AndyClawSkill].
 *
 * Exposes a `read_ai_skill_<slug>` tool that returns the skill's body.
 * If a REFINEMENT.md exists alongside the SKILL.md, its content is
 * appended — allowing iterative improvement without touching the original.
 */
internal class AiSkillAdapter(
    private val skill: Skill,
    val slug: String,
) : AndyClawSkill {

    override val id = "ai:$slug"
    override val name = skill.name

    override val baseManifest = SkillManifest(
        description = buildString {
            append("AI-created skill: ${skill.name}")
            if (skill.description.isNotBlank()) append(" — ${skill.description}")
        },
        tools = listOf(
            ToolDefinition(
                name = "read_ai_skill_${sanitizeName(slug)}",
                description = "Read the full instructions for the '${skill.name}' skill (AI-created)",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {})
                },
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return try {
            val body = SkillFrontmatter.extractBody(skill.content)
            val refinementFile = File(skill.baseDir, "REFINEMENT.md")
            val combined = if (refinementFile.isFile) {
                val refinement = SkillFrontmatter.extractBody(refinementFile.readText())
                "$body\n\n---\n\n## Refinements\n\n$refinement"
            } else {
                body
            }
            SkillResult.Success(combined)
        } catch (e: Exception) {
            SkillResult.Error("Failed to read AI skill '${skill.name}': ${e.message}")
        }
    }
}
