package org.ethereumphone.andyclaw.skills.builtin

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
 * Meta-skill for refining existing skills without overwriting their originals.
 *
 * Refinements are stored as `REFINEMENT.md` files alongside each skill's `SKILL.md`.
 * When a skill adapter reads the skill content, the refinement is appended, producing
 * an enhanced version that incorporates Android-specific guidance, bug fixes, or
 * additional instructions layered on top of the original.
 *
 * This overlay approach has several advantages:
 * - The original SKILL.md is never modified, so ClawHub updates won't lose refinements
 * - Refinements can be independently viewed, updated, or reverted
 * - Multiple iterations of refinement are tracked via the version history in the file
 *
 * Works with both ClawHub-installed skills and AI-created skills.
 */
class SkillRefinementSkill(
    private val aiSkillsDir: File,
    private val clawHubSkillsDir: File,
    private val nativeSkillRegistry: NativeSkillRegistry,
) : AndyClawSkill {

    companion object {
        private const val TAG = "SkillRefinementSkill"
        private const val REFINEMENT_FILE = "REFINEMENT.md"
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }

    override val id = "skill-refinement"
    override val name = "Skill Refinement"

    override val baseManifest = SkillManifest(
        description = "Refine and improve existing skills (ClawHub or AI-created) without overwriting the original. " +
            "Refinements are stored as overlays that get appended to the skill's content, " +
            "adding Android-specific guidance, fixes, or enhancements. " +
            "The original SKILL.md is never modified.",
        tools = listOf(
            ToolDefinition(
                name = "refinement_list_skills",
                description = "List all skills that can be refined (ClawHub and AI-created SKILL.md skills). " +
                    "Shows whether each skill already has a refinement.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                },
            ),
            ToolDefinition(
                name = "refinement_read",
                description = "Read a skill's full content with any existing refinement applied. " +
                    "Shows the original SKILL.md body, plus the REFINEMENT.md overlay if one exists. " +
                    "Use this to understand a skill before creating or updating a refinement.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("slug") {
                            put("type", "string")
                            put("description", "Skill slug (directory name)")
                        }
                        putJsonObject("show_raw") {
                            put("type", "boolean")
                            put("description", "If true, show SKILL.md and REFINEMENT.md separately instead of combined (default: false)")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("slug")) }
                },
            ),
            ToolDefinition(
                name = "refinement_create",
                description = "Create or update a REFINEMENT.md overlay for a skill. " +
                    "The refinement is appended to the original skill content when the agent reads it. " +
                    "Use this to add Android-specific guidance, fix issues, or enhance instructions. " +
                    "If a refinement already exists, it will be replaced (the old version is preserved " +
                    "in a history section within the file).",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("slug") {
                            put("type", "string")
                            put("description", "Skill slug to refine")
                        }
                        putJsonObject("content") {
                            put("type", "string")
                            put("description", "Markdown refinement content — Android-specific instructions, fixes, or enhancements to layer on top of the original skill")
                        }
                        putJsonObject("reason") {
                            put("type", "string")
                            put("description", "Brief explanation of why this refinement is needed (e.g. 'Improve file path handling on Android')")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("slug"))
                        add(JsonPrimitive("content"))
                    }
                },
            ),
            ToolDefinition(
                name = "refinement_remove",
                description = "Remove the REFINEMENT.md overlay from a skill, reverting to the original SKILL.md content.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("slug") {
                            put("type", "string")
                            put("description", "Skill slug to remove refinement from")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("slug")) }
                },
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "refinement_list_all",
                description = "List all skills that currently have active refinements, showing the reason and date for each.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                },
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        Log.d(TAG, "Executing tool: $tool")
        return when (tool) {
            "refinement_list_skills" -> executeListSkills()
            "refinement_read" -> executeRead(params)
            "refinement_create" -> executeCreate(params)
            "refinement_remove" -> executeRemove(params)
            "refinement_list_all" -> executeListAll()
            else -> SkillResult.Error("Unknown skill-refinement tool: $tool")
        }
    }

    // ── refinement_list_skills ──────────────────────────────────────────

    private fun executeListSkills(): SkillResult {
        val sb = StringBuilder()
        var total = 0

        val clawHubSkills = scanRefineable(clawHubSkillsDir, "ClawHub")
        if (clawHubSkills.isNotEmpty()) {
            sb.appendLine("## ClawHub Skills (${clawHubSkills.size})\n")
            for (entry in clawHubSkills) {
                total++
                val refined = if (entry.hasRefinement) " ✎ REFINED" else ""
                sb.appendLine("- **${entry.name}** [slug: ${entry.slug}]$refined")
                sb.appendLine("  ${entry.description.take(150)}")
            }
            sb.appendLine()
        }

        val aiSkills = scanRefineable(aiSkillsDir, "AI-created")
        if (aiSkills.isNotEmpty()) {
            sb.appendLine("## AI-Created Skills (${aiSkills.size})\n")
            for (entry in aiSkills) {
                total++
                val refined = if (entry.hasRefinement) " ✎ REFINED" else ""
                sb.appendLine("- **${entry.name}** [slug: ${entry.slug}]$refined")
                sb.appendLine("  ${entry.description.take(150)}")
            }
            sb.appendLine()
        }

        if (total == 0) {
            return SkillResult.Success(
                "No refineable skills found. Install ClawHub skills or create AI skills first."
            )
        }

        return SkillResult.Success(sb.toString().trimEnd())
    }

    // ── refinement_read ─────────────────────────────────────────────────

    private fun executeRead(params: JsonObject): SkillResult {
        val slug = params["slug"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: slug")
        val showRaw = params["show_raw"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        val skillDir = findSkillDir(slug)
            ?: return SkillResult.Error("Skill '$slug' not found in ClawHub or AI-created skills.")

        val skillFile = File(skillDir, "SKILL.md")
        if (!skillFile.isFile) {
            return SkillResult.Error("SKILL.md missing in skill directory: ${skillDir.absolutePath}")
        }

        val skillContent = skillFile.readText()
        val body = SkillFrontmatter.extractBody(skillContent)
        val refinementFile = File(skillDir, REFINEMENT_FILE)

        if (showRaw) {
            val sb = StringBuilder()
            sb.appendLine("## Original SKILL.md\n")
            sb.appendLine("```markdown")
            sb.appendLine(skillContent)
            sb.appendLine("```")

            if (refinementFile.isFile) {
                sb.appendLine("\n## REFINEMENT.md\n")
                sb.appendLine("```markdown")
                sb.appendLine(refinementFile.readText())
                sb.appendLine("```")
            } else {
                sb.appendLine("\n*No refinement exists for this skill.*")
            }

            return SkillResult.Success(sb.toString())
        }

        // Combined view (as the agent would see it)
        val combined = if (refinementFile.isFile) {
            val refinementContent = SkillFrontmatter.extractBody(refinementFile.readText())
            "$body\n\n---\n\n## Refinements\n\n$refinementContent"
        } else {
            body
        }

        val label = if (refinementFile.isFile) "(with refinement)" else "(no refinement)"
        return SkillResult.Success("# $slug $label\n\n$combined")
    }

    // ── refinement_create ───────────────────────────────────────────────

    private fun executeCreate(params: JsonObject): SkillResult {
        val slug = params["slug"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: slug")
        val content = params["content"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: content")
        val reason = params["reason"]?.jsonPrimitive?.content ?: "General refinement"

        val skillDir = findSkillDir(slug)
            ?: return SkillResult.Error("Skill '$slug' not found in ClawHub or AI-created skills.")

        val refinementFile = File(skillDir, REFINEMENT_FILE)
        val timestamp = DATE_FMT.format(Date())

        val isUpdate = refinementFile.isFile

        return try {
            val refinementMd = buildString {
                appendLine("---")
                appendLine("reason: \"${escapeYaml(reason)}\"")
                if (isUpdate) {
                    appendLine("updated_at: \"$timestamp\"")
                } else {
                    appendLine("created_at: \"$timestamp\"")
                }
                appendLine("---")
                appendLine()
                append(content)

                // If updating, preserve previous version in a collapsed history section
                if (isUpdate) {
                    val previousContent = refinementFile.readText()
                    val previousBody = SkillFrontmatter.extractBody(previousContent)
                    val previousFm = SkillFrontmatter.parse(previousContent)
                    val prevDate = previousFm["updated_at"] ?: previousFm["created_at"] ?: "unknown"

                    appendLine("\n\n---\n")
                    appendLine("<details>")
                    appendLine("<summary>Previous refinement ($prevDate)</summary>\n")
                    appendLine(previousBody)
                    appendLine("\n</details>")
                }
            }

            refinementFile.writeText(refinementMd)

            val action = if (isUpdate) "updated" else "created"
            Log.i(TAG, "Refinement $action for '$slug': $reason")
            SkillResult.Success(
                "Refinement $action for '$slug'.\n" +
                    "  Reason: $reason\n" +
                    "  File: ${refinementFile.absolutePath}\n\n" +
                    "The refinement will be appended when the agent reads this skill's instructions."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create refinement for '$slug': ${e.message}", e)
            SkillResult.Error("Failed to create refinement: ${e.message}")
        }
    }

    // ── refinement_remove ───────────────────────────────────────────────

    private fun executeRemove(params: JsonObject): SkillResult {
        val slug = params["slug"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: slug")

        val skillDir = findSkillDir(slug)
            ?: return SkillResult.Error("Skill '$slug' not found.")

        val refinementFile = File(skillDir, REFINEMENT_FILE)
        if (!refinementFile.isFile) {
            return SkillResult.Error("No refinement exists for skill '$slug'.")
        }

        return try {
            refinementFile.delete()
            Log.i(TAG, "Removed refinement for '$slug'")
            SkillResult.Success("Refinement removed for '$slug'. The skill will revert to its original content.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove refinement for '$slug': ${e.message}", e)
            SkillResult.Error("Failed to remove refinement: ${e.message}")
        }
    }

    // ── refinement_list_all ─────────────────────────────────────────────

    private fun executeListAll(): SkillResult {
        val refined = mutableListOf<RefinedSkillInfo>()

        for (dir in listOf(clawHubSkillsDir, aiSkillsDir)) {
            if (!dir.isDirectory) continue
            dir.listFiles()
                ?.filter { it.isDirectory && File(it, REFINEMENT_FILE).isFile }
                ?.forEach { skillDir ->
                    val slug = skillDir.name
                    val refinementFile = File(skillDir, REFINEMENT_FILE)
                    val fm = try {
                        SkillFrontmatter.parse(refinementFile.readText())
                    } catch (_: Exception) { emptyMap() }
                    val skill = SkillLoader.parseSkillFile(File(skillDir, "SKILL.md"), skillDir)
                    val source = if (dir == clawHubSkillsDir) "ClawHub" else "AI-created"

                    refined.add(
                        RefinedSkillInfo(
                            slug = slug,
                            name = skill?.name ?: slug,
                            source = source,
                            reason = fm["reason"] ?: "No reason specified",
                            date = fm["updated_at"] ?: fm["created_at"] ?: "unknown",
                        )
                    )
                }
        }

        if (refined.isEmpty()) {
            return SkillResult.Success("No active refinements. Use refinement_create to refine a skill.")
        }

        val sb = StringBuilder("## Active Refinements (${refined.size})\n\n")
        for (info in refined) {
            sb.appendLine("- **${info.name}** [${info.source}, slug: ${info.slug}]")
            sb.appendLine("  Reason: ${info.reason}")
            sb.appendLine("  Date: ${info.date}")
            sb.appendLine()
        }

        return SkillResult.Success(sb.toString().trimEnd())
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun findSkillDir(slug: String): File? {
        val clawHub = File(clawHubSkillsDir, slug)
        if (clawHub.isDirectory && File(clawHub, "SKILL.md").isFile) return clawHub

        val ai = File(aiSkillsDir, slug)
        if (ai.isDirectory && File(ai, "SKILL.md").isFile) return ai

        return null
    }

    private fun scanRefineable(dir: File, source: String): List<RefineableSkillEntry> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory && File(it, "SKILL.md").isFile }
            ?.sortedBy { it.name }
            ?.mapNotNull { subdir ->
                val skill = SkillLoader.parseSkillFile(File(subdir, "SKILL.md"), subdir)
                    ?: return@mapNotNull null
                RefineableSkillEntry(
                    slug = subdir.name,
                    name = skill.name,
                    description = skill.description,
                    source = source,
                    hasRefinement = File(subdir, REFINEMENT_FILE).isFile,
                )
            }
            ?: emptyList()
    }

    private fun escapeYaml(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private data class RefineableSkillEntry(
        val slug: String,
        val name: String,
        val description: String,
        val source: String,
        val hasRefinement: Boolean,
    )

    private data class RefinedSkillInfo(
        val slug: String,
        val name: String,
        val source: String,
        val reason: String,
        val date: String,
    )
}
