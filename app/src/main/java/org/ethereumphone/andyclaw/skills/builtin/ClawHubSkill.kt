package org.ethereumphone.andyclaw.skills.builtin

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.ethereumphone.andyclaw.extensions.clawhub.ClawHubManager
import org.ethereumphone.andyclaw.extensions.clawhub.InstallResult
import org.ethereumphone.andyclaw.extensions.clawhub.UpdateResult
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition

/**
 * Built-in skill that lets the AI agent search, install, uninstall, update,
 * and manage ClawHub skills at runtime.
 *
 * Skills installed via this skill are recorded in the ClawHub lockfile and
 * synced into the [NativeSkillRegistry], so they appear in the Installed tab
 * of the ClawHub UI and become immediately available as agent tools.
 */
class ClawHubSkill(
    private val manager: ClawHubManager,
) : AndyClawSkill {

    companion object {
        private const val TAG = "ClawHubSkill"
    }

    override val id = "clawhub"
    override val name = "ClawHub"

    override val baseManifest = SkillManifest(
        description = "Search, install, uninstall, and manage skills from the ClawHub registry. " +
            "Installed skills become immediately available as agent tools and appear in the ClawHub UI.",
        tools = listOf(
            ToolDefinition(
                name = "clawhub_search",
                description = "Search the ClawHub skill registry by natural-language query. " +
                    "Returns matching skills with slugs, names, descriptions, and versions.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("query") {
                            put("type", "string")
                            put("description", "Natural-language search query (e.g. 'calendar management', 'file organizer')")
                        }
                        putJsonObject("limit") {
                            put("type", "integer")
                            put("description", "Maximum number of results to return (default: 20)")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("query")) }
                },
            ),
            ToolDefinition(
                name = "clawhub_browse",
                description = "Browse all available skills on ClawHub with pagination. " +
                    "Use this to discover what skills are available without a specific search query.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("cursor") {
                            put("type", "string")
                            put("description", "Pagination cursor from a previous browse call (omit for first page)")
                        }
                    }
                },
            ),
            ToolDefinition(
                name = "clawhub_skill_info",
                description = "Get detailed information about a specific skill on ClawHub, " +
                    "including owner, versions, and moderation status.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("slug") {
                            put("type", "string")
                            put("description", "Skill slug (e.g. 'calendar-skill')")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("slug")) }
                },
            ),
            ToolDefinition(
                name = "clawhub_install",
                description = "Install a skill from ClawHub by its slug. The skill will be downloaded, " +
                    "registered, and immediately available as an agent tool. " +
                    "It will also appear in the Installed tab of the ClawHub UI.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("slug") {
                            put("type", "string")
                            put("description", "Skill slug to install (e.g. 'calendar-skill')")
                        }
                        putJsonObject("version") {
                            put("type", "string")
                            put("description", "Specific version to install (latest if omitted)")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("slug")) }
                },
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "clawhub_uninstall",
                description = "Uninstall a ClawHub skill by its slug. Removes the skill files, " +
                    "unregisters it from the agent, and removes it from the Installed tab.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("slug") {
                            put("type", "string")
                            put("description", "Slug of the installed skill to remove")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("slug")) }
                },
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "clawhub_update",
                description = "Update an installed ClawHub skill to the latest (or specified) version.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("slug") {
                            put("type", "string")
                            put("description", "Slug of the installed skill to update")
                        }
                        putJsonObject("version") {
                            put("type", "string")
                            put("description", "Target version (latest if omitted)")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("slug")) }
                },
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "clawhub_list_installed",
                description = "List all currently installed ClawHub skills with their slugs, " +
                    "display names, versions, and install timestamps.",
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
            "clawhub_search" -> executeSearch(params)
            "clawhub_browse" -> executeBrowse(params)
            "clawhub_skill_info" -> executeSkillInfo(params)
            "clawhub_install" -> executeInstall(params)
            "clawhub_uninstall" -> executeUninstall(params)
            "clawhub_update" -> executeUpdate(params)
            "clawhub_list_installed" -> executeListInstalled()
            else -> SkillResult.Error("Unknown clawhub tool: $tool")
        }
    }

    // ── clawhub_search ──────────────────────────────────────────────────

    private suspend fun executeSearch(params: JsonObject): SkillResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: query")
        val limit = params["limit"]?.jsonPrimitive?.content?.toIntOrNull()

        return try {
            val results = manager.search(query, limit ?: 20)
            if (results.isEmpty()) {
                return SkillResult.Success("No skills found matching '$query'.")
            }

            val sb = StringBuilder("## Search Results for \"$query\" (${results.size})\n\n")
            for (result in results) {
                val slug = result.slug ?: continue
                val installed = manager.isInstalled(slug)
                sb.appendLine("- **${result.displayName ?: slug}** [slug: $slug]")
                if (result.version != null) sb.appendLine("  Version: ${result.version}")
                if (result.summary != null) sb.appendLine("  ${result.summary}")
                if (installed) sb.appendLine("  Status: INSTALLED")
                sb.appendLine()
            }
            SkillResult.Success(sb.toString().trimEnd())
        } catch (e: Exception) {
            Log.e(TAG, "Search failed: ${e.message}", e)
            SkillResult.Error("Search failed: ${e.message}")
        }
    }

    // ── clawhub_browse ──────────────────────────────────────────────────

    private suspend fun executeBrowse(params: JsonObject): SkillResult {
        val cursor = params["cursor"]?.jsonPrimitive?.content

        return try {
            val response = manager.browse(cursor)
            if (response.items.isEmpty()) {
                return SkillResult.Success("No more skills to browse.")
            }

            val sb = StringBuilder("## Available Skills (${response.items.size})\n\n")
            for (skill in response.items) {
                val installed = manager.isInstalled(skill.slug)
                sb.appendLine("- **${skill.displayName}** [slug: ${skill.slug}]")
                skill.latestVersion?.let { sb.appendLine("  Latest: v${it.version}") }
                if (skill.summary != null) sb.appendLine("  ${skill.summary}")
                if (installed) sb.appendLine("  Status: INSTALLED")
                sb.appendLine()
            }
            if (response.nextCursor != null) {
                sb.appendLine("---")
                sb.appendLine("More results available. Use cursor: \"${response.nextCursor}\" to load the next page.")
            }
            SkillResult.Success(sb.toString().trimEnd())
        } catch (e: Exception) {
            Log.e(TAG, "Browse failed: ${e.message}", e)
            SkillResult.Error("Browse failed: ${e.message}")
        }
    }

    // ── clawhub_skill_info ──────────────────────────────────────────────

    private suspend fun executeSkillInfo(params: JsonObject): SkillResult {
        val slug = params["slug"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: slug")

        return try {
            val detail = manager.getSkillInfo(slug)
                ?: return SkillResult.Error("Skill '$slug' not found on ClawHub.")

            val sb = StringBuilder("## Skill: ${detail.skill?.displayName ?: slug}\n\n")
            sb.appendLine("Slug: $slug")
            sb.appendLine("Installed locally: ${if (manager.isInstalled(slug)) "Yes" else "No"}")

            detail.skill?.let { skill ->
                if (skill.summary != null) sb.appendLine("Summary: ${skill.summary}")
            }

            detail.owner?.let { owner ->
                val ownerDisplay = owner.displayName ?: owner.handle ?: "Unknown"
                sb.appendLine("Author: $ownerDisplay")
            }

            detail.latestVersion?.let { ver ->
                sb.appendLine("Latest version: v${ver.version}")
                if (ver.changelog.isNotBlank()) {
                    sb.appendLine("Changelog: ${ver.changelog}")
                }
            }

            detail.moderation?.let { mod ->
                if (mod.isMalwareBlocked) sb.appendLine("WARNING: This skill is BLOCKED for malware.")
                if (mod.isSuspicious) sb.appendLine("WARNING: This skill is flagged as suspicious.")
            }

            SkillResult.Success(sb.toString().trimEnd())
        } catch (e: Exception) {
            Log.e(TAG, "Skill info failed for '$slug': ${e.message}", e)
            SkillResult.Error("Failed to get skill info: ${e.message}")
        }
    }

    // ── clawhub_install ─────────────────────────────────────────────────

    private suspend fun executeInstall(params: JsonObject): SkillResult {
        val slug = params["slug"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: slug")
        val version = params["version"]?.jsonPrimitive?.content

        return try {
            // If the approval dialog already downloaded and assessed this skill,
            // confirm the pending install instead of re-downloading.
            if (manager.hasPendingInstall(slug)) {
                val pendingVersion = manager.getPendingVersion(slug) ?: version
                return when (val result = manager.confirmInstall(slug, pendingVersion)) {
                    is InstallResult.Success -> {
                        Log.i(TAG, "Confirmed pending install of '$slug' v${result.version ?: "latest"}")
                        SkillResult.Success(
                            "Successfully installed '${result.slug}' v${result.version ?: "latest"}.\n" +
                                "The skill is now available as an agent tool and visible in the ClawHub Installed tab."
                        )
                    }
                    is InstallResult.AlreadyInstalled -> {
                        SkillResult.Success(
                            "Skill '${result.slug}' is already installed" +
                                (if (result.version != null) " (v${result.version})" else "") +
                                ". Use clawhub_update to update it."
                        )
                    }
                    is InstallResult.Failed -> {
                        SkillResult.Error("Install failed for '${result.slug}': ${result.reason}")
                    }
                }
            }

            when (val result = manager.install(slug, version)) {
                is InstallResult.Success -> {
                    Log.i(TAG, "Installed ClawHub skill '$slug' v${result.version ?: "latest"}")
                    SkillResult.Success(
                        "Successfully installed '${result.slug}' v${result.version ?: "latest"}.\n" +
                            "The skill is now available as an agent tool and visible in the ClawHub Installed tab."
                    )
                }
                is InstallResult.AlreadyInstalled -> {
                    SkillResult.Success(
                        "Skill '${result.slug}' is already installed" +
                            (if (result.version != null) " (v${result.version})" else "") +
                            ". Use clawhub_update to update it."
                    )
                }
                is InstallResult.Failed -> {
                    SkillResult.Error("Install failed for '${result.slug}': ${result.reason}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Install failed for '$slug': ${e.message}", e)
            SkillResult.Error("Install failed: ${e.message}")
        }
    }

    // ── clawhub_uninstall ───────────────────────────────────────────────

    private suspend fun executeUninstall(params: JsonObject): SkillResult {
        val slug = params["slug"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: slug")

        return try {
            val success = manager.uninstall(slug)
            if (success) {
                Log.i(TAG, "Uninstalled ClawHub skill '$slug'")
                SkillResult.Success(
                    "Successfully uninstalled '$slug'. " +
                        "The skill has been removed and is no longer available."
                )
            } else {
                SkillResult.Error("Skill '$slug' is not installed or could not be removed.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Uninstall failed for '$slug': ${e.message}", e)
            SkillResult.Error("Uninstall failed: ${e.message}")
        }
    }

    // ── clawhub_update ──────────────────────────────────────────────────

    private suspend fun executeUpdate(params: JsonObject): SkillResult {
        val slug = params["slug"]?.jsonPrimitive?.content
            ?: return SkillResult.Error("Missing required parameter: slug")
        val version = params["version"]?.jsonPrimitive?.content

        return try {
            when (val result = manager.update(slug, version)) {
                is UpdateResult.Updated -> {
                    Log.i(TAG, "Updated '$slug' from v${result.fromVersion} to v${result.toVersion}")
                    SkillResult.Success(
                        "Updated '${result.slug}' from v${result.fromVersion ?: "unknown"} to v${result.toVersion}."
                    )
                }
                is UpdateResult.AlreadyUpToDate -> {
                    SkillResult.Success(
                        "Skill '${result.slug}' is already up to date" +
                            (if (result.version != null) " (v${result.version})" else "") + "."
                    )
                }
                is UpdateResult.NotInstalled -> {
                    SkillResult.Error(
                        "Skill '${result.slug}' is not installed. Use clawhub_install first."
                    )
                }
                is UpdateResult.Failed -> {
                    SkillResult.Error("Update failed for '${result.slug}': ${result.reason}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update failed for '$slug': ${e.message}", e)
            SkillResult.Error("Update failed: ${e.message}")
        }
    }

    // ── clawhub_list_installed ───────────────────────────────────────────

    private fun executeListInstalled(): SkillResult {
        val installed = manager.listInstalled()
        if (installed.isEmpty()) {
            return SkillResult.Success(
                "No ClawHub skills installed. Use clawhub_search or clawhub_browse to find skills, " +
                    "then clawhub_install to add them."
            )
        }

        val sb = StringBuilder("## Installed ClawHub Skills (${installed.size})\n\n")
        for (skill in installed) {
            sb.appendLine("- **${skill.displayName}** [slug: ${skill.slug}]")
            if (skill.version != null) sb.appendLine("  Version: v${skill.version}")
            if (skill.installedAt > 0) {
                val date = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm", java.util.Locale.US
                ).format(java.util.Date(skill.installedAt))
                sb.appendLine("  Installed: $date")
            }
            sb.appendLine()
        }
        return SkillResult.Success(sb.toString().trimEnd())
    }
}
