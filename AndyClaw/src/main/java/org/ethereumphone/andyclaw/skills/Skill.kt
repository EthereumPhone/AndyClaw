package org.ethereumphone.andyclaw.skills

/**
 * A loaded skill definition, parsed from a SKILL.md file.
 * Mirrors OpenClaw's Skill type from @mariozechner/pi-coding-agent.
 */
data class Skill(
    /** Unique skill name (from frontmatter or directory name). */
    val name: String,
    /** Short description of what the skill does. */
    val description: String,
    /** Absolute path to the SKILL.md file. */
    val filePath: String,
    /** Absolute path to the skill directory. */
    val baseDir: String,
    /** Full markdown content of the SKILL.md file (including frontmatter). */
    val content: String,
)

/**
 * Installation spec for a skill dependency.
 * Mirrors OpenClaw's SkillInstallSpec.
 */
data class SkillInstallSpec(
    val id: String? = null,
    val kind: String,  // "brew", "apt", "node", "go", "uv", "download"
    val label: String? = null,
    val bins: List<String> = emptyList(),
    val os: List<String> = emptyList(),
    val formula: String? = null,
    val pkg: String? = null,
    val module: String? = null,
    val url: String? = null,
)

/**
 * Requirements that must be met for a skill to be eligible.
 */
data class SkillRequirements(
    /** All of these binaries must be on PATH. */
    val bins: List<String> = emptyList(),
    /** At least one of these binaries must be on PATH. */
    val anyBins: List<String> = emptyList(),
    /** All of these environment variables must be set. */
    val env: List<String> = emptyList(),
)

/**
 * OpenClaw-specific metadata embedded in the skill frontmatter.
 * Mirrors OpenClaw's OpenClawSkillMetadata.
 */
data class SkillMetadata(
    /** If true, always include this skill in the prompt. */
    val always: Boolean = false,
    val skillKey: String? = null,
    val primaryEnv: String? = null,
    val emoji: String? = null,
    val homepage: String? = null,
    val os: List<String> = emptyList(),
    val requires: SkillRequirements? = null,
    val install: List<SkillInstallSpec> = emptyList(),
    /** Execution contract for runnable skills (Termux, etc.). */
    val execution: SkillExecutionSpec? = null,
)

/**
 * Declares how a skill should be executed at runtime.
 *
 * When [type] is `"termux"`, the skill's [entrypoint] script is run inside
 * Termux via the RUN_COMMAND intent.  Files are synced to
 * `~/.andyclaw/skills/<slug>/` in Termux's home directory.
 */
data class SkillExecutionSpec(
    /** Runtime type — only `"termux"` is supported today. */
    val type: String,
    /** Path to the main script, relative to the skill directory. */
    val entrypoint: String,
    /** Optional one-time setup script run after first sync. */
    val setup: String? = null,
    /** Tools exposed by this skill.  Empty = single implicit tool. */
    val tools: List<SkillToolSpec> = emptyList(),
)

/**
 * A single tool exposed by an executable skill.
 */
data class SkillToolSpec(
    val name: String,
    val description: String = "",
    /** Per-tool entrypoint override; falls back to [SkillExecutionSpec.entrypoint]. */
    val entrypoint: String? = null,
    val args: Map<String, SkillArgSpec> = emptyMap(),
)

/**
 * Schema for a single argument accepted by a [SkillToolSpec].
 */
data class SkillArgSpec(
    val type: String = "string",
    val description: String = "",
    val required: Boolean = false,
    val default: String? = null,
)

/**
 * Invocation policy for a skill.
 */
data class SkillInvocationPolicy(
    /** Whether users can invoke this skill via /command. */
    val userInvocable: Boolean = true,
    /** Whether to exclude this skill from the model's system prompt. */
    val disableModelInvocation: Boolean = false,
)

/**
 * A fully resolved skill entry with parsed metadata and invocation policy.
 * Mirrors OpenClaw's SkillEntry.
 */
data class SkillEntry(
    val skill: Skill,
    val frontmatter: Map<String, String> = emptyMap(),
    val metadata: SkillMetadata? = null,
    val invocation: SkillInvocationPolicy = SkillInvocationPolicy(),
)

/**
 * A skill command that can be invoked by users.
 * Mirrors OpenClaw's SkillCommandSpec.
 */
data class SkillCommandSpec(
    /** The command name (sanitized, unique). */
    val name: String,
    /** The original skill name. */
    val skillName: String,
    /** Short description (max 100 chars). */
    val description: String,
)
