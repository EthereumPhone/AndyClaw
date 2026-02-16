package org.ethereumphone.andyclaw.skills

import java.io.File
import java.util.logging.Logger

/**
 * Registry for managing loaded skills.
 * Handles loading from multiple directories, filtering by eligibility,
 * and generating the skills prompt for the AI agent.
 *
 * Supports skills from three sources:
 * 1. Local filesystem (bundled, managed, workspace directories)
 * 2. APK extensions (via ExtensionSkillAdapter)
 * 3. ClawHub registry (remote skills installed to managed dir)
 *
 * Mirrors OpenClaw's workspace skill snapshot + command spec generation.
 */
class SkillRegistry {

    private val log = Logger.getLogger("SkillRegistry")
    private val entries = mutableListOf<SkillEntry>()
    private val commandSpecs = mutableListOf<SkillCommandSpec>()

    /** Reload callback invoked by ClawHubManager after install/uninstall. */
    var onReloadRequested: (() -> Unit)? = null

    val skills: List<SkillEntry> get() = entries.toList()
    val commands: List<SkillCommandSpec> get() = commandSpecs.toList()

    /**
     * Load skills from the given directories and rebuild the registry.
     *
     * ClawHub-installed skills are automatically included when they reside
     * in [managedSkillsDir] (the directory ClawHubManager installs into).
     */
    fun load(
        workspaceDir: File,
        managedSkillsDir: File? = null,
        bundledSkillsDir: File? = null,
        extraDirs: List<File> = emptyList(),
    ) {
        entries.clear()
        commandSpecs.clear()

        val loaded = SkillLoader.loadSkillEntries(
            workspaceDir = workspaceDir,
            managedSkillsDir = managedSkillsDir,
            bundledSkillsDir = bundledSkillsDir,
            extraDirs = extraDirs,
        )
        entries.addAll(loaded)
        commandSpecs.addAll(buildCommandSpecs(loaded))

        log.info("Loaded ${entries.size} skills, ${commandSpecs.size} commands")
    }

    /**
     * Merge additional skill entries into the registry without clearing
     * existing entries. Entries with duplicate names are skipped (first wins).
     *
     * Useful for appending ClawHub-sourced entries or extension-sourced entries
     * after the initial [load] call.
     */
    fun mergeEntries(additional: List<SkillEntry>) {
        val existingNames = entries.map { it.skill.name.lowercase() }.toMutableSet()
        var added = 0
        for (entry in additional) {
            val key = entry.skill.name.lowercase()
            if (key !in existingNames) {
                entries.add(entry)
                existingNames.add(key)
                added++
            }
        }
        if (added > 0) {
            commandSpecs.clear()
            commandSpecs.addAll(buildCommandSpecs(entries))
            log.info("Merged $added additional skills (total: ${entries.size})")
        }
    }

    /**
     * Request a registry reload (triggers the [onReloadRequested] callback).
     * Used by ClawHubManager after install/uninstall operations.
     */
    fun requestReload() {
        onReloadRequested?.invoke()
    }

    /**
     * Find a skill entry by name.
     */
    fun findByName(name: String): SkillEntry? {
        val lower = name.lowercase()
        return entries.find { it.skill.name.lowercase() == lower }
    }

    /**
     * Find a skill by command name (for /command invocation).
     */
    fun findByCommand(commandName: String): SkillEntry? {
        val lower = commandName.lowercase().replace(Regex("[\\s_]+"), "-")
        val spec = commandSpecs.find { cmd ->
            cmd.name.lowercase() == lower ||
            cmd.skillName.lowercase() == lower ||
            normalizeCommandLookup(cmd.name) == lower ||
            normalizeCommandLookup(cmd.skillName) == lower
        } ?: return null
        return findByName(spec.skillName)
    }

    /**
     * Build the skills prompt section for the agent's system prompt.
     * Lists available skills with their names and descriptions.
     */
    fun buildPrompt(): String {
        val eligible = entries.filter { !it.invocation.disableModelInvocation }
        if (eligible.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("<available_skills>")
        for (entry in eligible) {
            val emoji = entry.metadata?.emoji?.let { "$it " } ?: ""
            sb.appendLine("- ${emoji}${entry.skill.name}: ${entry.skill.description}")
        }
        sb.appendLine("</available_skills>")
        return sb.toString()
    }

    /**
     * Get the full SKILL.md content for a specific skill (for reading after selection).
     */
    fun getSkillContent(name: String): String? {
        val entry = findByName(name) ?: return null
        return SkillFrontmatter.extractBody(entry.skill.content)
    }

    /**
     * Install a skill to the managed skills directory by copying it.
     */
    fun installSkill(skillDir: File, managedSkillsDir: File): Boolean {
        val skillFile = File(skillDir, "SKILL.md")
        if (!skillFile.isFile) {
            log.warning("Cannot install skill: no SKILL.md in ${skillDir.absolutePath}")
            return false
        }

        val skill = SkillLoader.parseSkillFile(skillFile, skillDir) ?: return false
        val targetDir = File(managedSkillsDir, skill.name)
        return try {
            targetDir.mkdirs()
            skillDir.copyRecursively(targetDir, overwrite = true)
            log.info("Installed skill '${skill.name}' to ${targetDir.absolutePath}")
            true
        } catch (e: Exception) {
            log.warning("Failed to install skill '${skill.name}': ${e.message}")
            false
        }
    }

    /**
     * Uninstall a skill from the managed skills directory.
     */
    fun uninstallSkill(skillName: String, managedSkillsDir: File): Boolean {
        val targetDir = File(managedSkillsDir, skillName)
        if (!targetDir.isDirectory) {
            log.warning("Skill '$skillName' not found in managed dir")
            return false
        }
        return try {
            targetDir.deleteRecursively()
            log.info("Uninstalled skill '$skillName'")
            true
        } catch (e: Exception) {
            log.warning("Failed to uninstall skill '$skillName': ${e.message}")
            false
        }
    }

    /**
     * Resolve a /command invocation string into a skill command + args.
     */
    fun resolveCommandInvocation(input: String): CommandInvocation? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) return null

        val match = Regex("^/([^\\s]+)(?:\\s+([\\s\\S]+))?$").find(trimmed) ?: return null
        val commandName = match.groupValues[1].trim().lowercase()
        val args = match.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }

        // Handle /skill <name> <args> form
        if (commandName == "skill") {
            if (args == null) return null
            val skillMatch = Regex("^([^\\s]+)(?:\\s+([\\s\\S]+))?$").find(args) ?: return null
            val skillName = skillMatch.groupValues[1].trim()
            val skillArgs = skillMatch.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
            val entry = findByCommand(skillName) ?: return null
            return CommandInvocation(entry = entry, args = skillArgs)
        }

        val entry = findByCommand(commandName) ?: return null
        return CommandInvocation(entry = entry, args = args)
    }

    private fun buildCommandSpecs(entries: List<SkillEntry>): List<SkillCommandSpec> {
        val userInvocable = entries.filter { it.invocation.userInvocable }
        val used = mutableSetOf<String>()
        val specs = mutableListOf<SkillCommandSpec>()

        for (entry in userInvocable) {
            val base = sanitizeCommandName(entry.skill.name)
            val unique = resolveUniqueName(base, used)
            used.add(unique.lowercase())

            val description = entry.skill.description.let {
                if (it.length > 100) it.take(99) + "…" else it
            }

            specs.add(SkillCommandSpec(
                name = unique,
                skillName = entry.skill.name,
                description = description,
            ))
        }

        return specs
    }

    private fun sanitizeCommandName(raw: String): String {
        val normalized = raw.lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .replace(Regex("_+"), "_")
            .trimStart('_').trimEnd('_')
        return normalized.take(32).ifEmpty { "skill" }
    }

    private fun resolveUniqueName(base: String, used: Set<String>): String {
        if (!used.contains(base.lowercase())) return base
        for (i in 2..999) {
            val candidate = "${base}_$i"
            if (!used.contains(candidate.lowercase())) return candidate
        }
        return "${base}_x"
    }

    private fun normalizeCommandLookup(value: String): String {
        return value.trim().lowercase().replace(Regex("[\\s_]+"), "-")
    }
}

/**
 * Result of resolving a /command invocation.
 */
data class CommandInvocation(
    val entry: SkillEntry,
    val args: String? = null,
)
