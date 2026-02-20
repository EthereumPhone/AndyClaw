package org.ethereumphone.andyclaw.extensions.clawhub

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.Skill
import org.ethereumphone.andyclaw.skills.SkillEntry
import org.ethereumphone.andyclaw.skills.SkillFrontmatter
import org.ethereumphone.andyclaw.skills.SkillLoader
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import java.io.File
import java.util.logging.Logger

/**
 * Bridges a ClawHub-installed skill into the [AndyClawSkill] interface.
 *
 * Unlike APK extensions (which execute via IPC), ClawHub skills are
 * instruction-based: they provide SKILL.md content that the AI agent reads
 * and follows. The adapter exposes a single tool `read_skill` that returns
 * the skill's body content for the agent to act on.
 *
 * This adapter also produces [SkillEntry] objects compatible with
 * [SkillRegistry], enabling ClawHub skills to participate in command
 * resolution, prompt assembly, and the same discovery pipeline as
 * bundled/workspace skills.
 *
 * ## Integration
 *
 * ```kotlin
 * val manager = ClawHubManager(managedDir, registry)
 * val adapters = ClawHubSkillAdapter.fromInstalledSkills(managedDir)
 *
 * // Register as AndyClawSkill instances
 * for (adapter in adapters) {
 *     nativeSkillRegistry.register(adapter)
 * }
 *
 * // Or get SkillEntry objects for the SkillRegistry
 * val entries = ClawHubSkillAdapter.toSkillEntries(managedDir)
 * ```
 */
class ClawHubSkillAdapter(
    private val skill: Skill,
    private val slug: String,
    private val installedVersion: String?,
) : AndyClawSkill {

    override val id: String = "clawhub:$slug"

    override val name: String = skill.name

    override val baseManifest: SkillManifest = SkillManifest(
        description = buildString {
            append("ClawHub skill: ${skill.name}")
            if (!installedVersion.isNullOrBlank()) append(" v$installedVersion")
            if (skill.description.isNotBlank()) append(" — ${skill.description}")
        },
        tools = listOf(
            ToolDefinition(
                name = "read_skill_${sanitizeName(slug)}",
                description = "Read the full instructions for the '${skill.name}' skill (from ClawHub)",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {})
                },
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    /**
     * Executing the read_skill tool returns the SKILL.md body content
     * (without frontmatter), which the agent uses as instructions.
     *
     * If a `REFINEMENT.md` file exists alongside the SKILL.md, its body
     * is appended as an "Android Refinements" section. This overlay
     * approach lets refinements be added/removed without touching the
     * original skill content.
     */
    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return try {
            val body = SkillFrontmatter.extractBody(skill.content)
            val refinementFile = File(skill.baseDir, "REFINEMENT.md")
            val combined = if (refinementFile.isFile) {
                val refinement = SkillFrontmatter.extractBody(refinementFile.readText())
                "$body\n\n---\n\n## Android Refinements\n\n$refinement"
            } else {
                body
            }
            SkillResult.Success(combined)
        } catch (e: Exception) {
            SkillResult.Error("Failed to read skill '${skill.name}': ${e.message}")
        }
    }

    companion object {
        private val log = Logger.getLogger("ClawHubSkillAdapter")

        /**
         * Create adapters for all installed ClawHub skills found in [managedDir].
         *
         * Each subdirectory containing a SKILL.md is treated as an installed
         * ClawHub skill. The lockfile is consulted for version info.
         */
        fun fromInstalledSkills(
            managedDir: File,
            lockFile: ClawHubLockFile? = null,
        ): List<ClawHubSkillAdapter> {
            if (!managedDir.isDirectory) return emptyList()

            val lock = lockFile ?: ClawHubLockFile(managedDir).also { it.load() }

            return managedDir.listFiles()
                ?.filter { it.isDirectory && File(it, "SKILL.md").isFile }
                ?.mapNotNull { dir ->
                    val slug = dir.name
                    val skill = SkillLoader.parseSkillFile(File(dir, "SKILL.md"), dir)
                    if (skill == null) {
                        log.warning("Skipping invalid skill in ${dir.absolutePath}")
                        return@mapNotNull null
                    }
                    val version = lock.getEntry(slug)?.version
                    ClawHubSkillAdapter(skill, slug, version)
                }
                ?: emptyList()
        }

        /**
         * Produce [SkillEntry] objects for all installed ClawHub skills,
         * suitable for merging into the existing [SkillRegistry].
         *
         * This lets ClawHub skills participate in command resolution,
         * prompt building, and the standard skill discovery pipeline.
         */
        fun toSkillEntries(
            managedDir: File,
            lockFile: ClawHubLockFile? = null,
        ): List<SkillEntry> {
            if (!managedDir.isDirectory) return emptyList()

            val lock = lockFile ?: ClawHubLockFile(managedDir).also { it.load() }

            return managedDir.listFiles()
                ?.filter { it.isDirectory && File(it, "SKILL.md").isFile }
                ?.mapNotNull { dir ->
                    val skillFile = File(dir, "SKILL.md")
                    val skill = SkillLoader.parseSkillFile(skillFile, dir) ?: return@mapNotNull null
                    val frontmatter = try {
                        SkillFrontmatter.parse(skill.content)
                    } catch (_: Exception) {
                        emptyMap()
                    }
                    SkillEntry(
                        skill = skill,
                        frontmatter = frontmatter,
                        metadata = SkillFrontmatter.resolveMetadata(frontmatter),
                        invocation = SkillFrontmatter.resolveInvocationPolicy(frontmatter),
                    )
                }
                ?: emptyList()
        }

        private fun sanitizeName(raw: String): String {
            return raw.lowercase()
                .replace(Regex("[^a-z0-9_]+"), "_")
                .replace(Regex("_+"), "_")
                .trimStart('_').trimEnd('_')
                .take(32)
                .ifEmpty { "skill" }
        }
    }
}
