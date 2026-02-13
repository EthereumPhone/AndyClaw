package org.ethereumphone.andyclaw.skills

import java.io.File
import java.util.logging.Logger

/**
 * Loads skills from directories on the filesystem.
 * Skills are directories containing a SKILL.md file with frontmatter metadata.
 *
 * Mirrors OpenClaw's loadSkillsFromDir / loadSkillEntries pattern.
 */
object SkillLoader {

    private val log = Logger.getLogger("SkillLoader")
    private const val SKILL_FILENAME = "SKILL.md"

    /**
     * Load all skills from a directory. Each subdirectory containing a SKILL.md is treated as a skill.
     */
    fun loadFromDir(dir: File, source: String = "unknown"): List<Skill> {
        if (!dir.isDirectory) return emptyList()

        val skills = mutableListOf<Skill>()
        val children = dir.listFiles() ?: return emptyList()

        for (child in children.sorted()) {
            if (!child.isDirectory) continue
            val skillFile = File(child, SKILL_FILENAME)
            if (!skillFile.isFile) continue

            val skill = parseSkillFile(skillFile, child)
            if (skill != null) {
                skills.add(skill)
                log.fine("Loaded skill '${skill.name}' from $source: ${child.absolutePath}")
            }
        }

        return skills
    }

    /**
     * Parse a single SKILL.md file into a Skill object.
     */
    fun parseSkillFile(file: File, baseDir: File): Skill? {
        return try {
            val content = file.readText()
            val frontmatter = SkillFrontmatter.parse(content)
            val name = frontmatter["name"] ?: baseDir.name
            val description = frontmatter["description"] ?: ""

            Skill(
                name = name,
                description = description,
                filePath = file.absolutePath,
                baseDir = baseDir.absolutePath,
                content = content,
            )
        } catch (e: Exception) {
            log.warning("Failed to parse skill file ${file.absolutePath}: ${e.message}")
            null
        }
    }

    /**
     * Load skill entries from multiple source directories with precedence:
     * bundled < managed < workspace (higher priority overrides lower).
     *
     * Mirrors OpenClaw's loadSkillEntries() with its precedence chain.
     */
    fun loadSkillEntries(
        workspaceDir: File,
        managedSkillsDir: File? = null,
        bundledSkillsDir: File? = null,
        extraDirs: List<File> = emptyList(),
    ): List<SkillEntry> {
        val merged = LinkedHashMap<String, Skill>()

        // Load in precedence order (later overrides earlier)
        for (dir in extraDirs) {
            for (skill in loadFromDir(dir, "extra")) {
                merged[skill.name] = skill
            }
        }
        if (bundledSkillsDir != null) {
            for (skill in loadFromDir(bundledSkillsDir, "bundled")) {
                merged[skill.name] = skill
            }
        }
        if (managedSkillsDir != null) {
            for (skill in loadFromDir(managedSkillsDir, "managed")) {
                merged[skill.name] = skill
            }
        }
        val workspaceSkillsDir = File(workspaceDir, "skills")
        for (skill in loadFromDir(workspaceSkillsDir, "workspace")) {
            merged[skill.name] = skill
        }

        return merged.values.map { skill ->
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
    }
}
