package org.ethereumphone.andyclaw.extensions.clawhub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ethereumphone.andyclaw.skills.SkillLoader
import org.ethereumphone.andyclaw.skills.SkillRegistry
import java.io.File
import java.security.MessageDigest
import java.util.logging.Logger

/**
 * High-level manager for discovering and managing ClawHub skills.
 *
 * Orchestrates the full lifecycle:
 *
 * ```
 *   ┌──────────┐     ┌──────────┐     ┌──────────────┐     ┌──────────────┐
 *   │  Search   │────▶│ Download │────▶│  Extract to  │────▶│ SkillRegistry│
 *   │ (API v1)  │     │  (ZIP)   │     │  managed dir │     │   .load()    │
 *   └──────────┘     └──────────┘     └──────────────┘     └──────────────┘
 *                                            │
 *                                     ┌──────▼──────┐
 *                                     │  Lockfile    │
 *                                     │ (.clawhub/)  │
 *                                     └─────────────┘
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * val manager = ClawHubManager(
 *     managedSkillsDir = File(context.filesDir, "skills"),
 *     skillRegistry = registry,
 * )
 *
 * // Search the registry
 * val results = manager.search("calendar")
 *
 * // Install a skill
 * val success = manager.install("calendar-skill")
 *
 * // Update all installed skills
 * val updated = manager.updateAll()
 *
 * // Uninstall
 * manager.uninstall("calendar-skill")
 * ```
 *
 * @param managedSkillsDir  Directory where ClawHub skills are installed.
 *                          Each skill gets its own subdirectory named after the slug.
 * @param skillRegistry     The existing [SkillRegistry] to reload after install/uninstall.
 * @param api               ClawHub API client (custom registry URL or shared OkHttp client).
 */
class ClawHubManager(
    private val managedSkillsDir: File,
    private val skillRegistry: SkillRegistry,
    private val api: ClawHubApi = ClawHubApi(),
) {

    private val log = Logger.getLogger("ClawHubManager")
    private val lockFile = ClawHubLockFile(managedSkillsDir)
    private val operationMutex = Mutex()

    init {
        lockFile.load()
    }

    // ── Search & Browse ─────────────────────────────────────────────

    /**
     * Search for skills on ClawHub by natural-language query.
     */
    suspend fun search(query: String, limit: Int? = null): List<ClawHubSearchResult> {
        return try {
            api.search(query, limit).results
        } catch (e: Exception) {
            log.warning("ClawHub search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Browse available skills with optional pagination.
     */
    suspend fun browse(cursor: String? = null): ClawHubSkillListResponse {
        return try {
            api.listSkills(cursor)
        } catch (e: Exception) {
            log.warning("ClawHub browse failed: ${e.message}")
            ClawHubSkillListResponse()
        }
    }

    /**
     * Get detailed info about a specific skill.
     */
    suspend fun getSkillInfo(slug: String): ClawHubSkillDetail? {
        return try {
            api.getSkill(slug)
        } catch (e: Exception) {
            log.warning("ClawHub getSkill failed for '$slug': ${e.message}")
            null
        }
    }

    // ── Install ─────────────────────────────────────────────────────

    /**
     * Install a skill from ClawHub by slug.
     *
     * Downloads the skill bundle, extracts it to the managed skills dir,
     * updates the lockfile, and reloads the [SkillRegistry].
     *
     * @param slug     Skill slug on ClawHub.
     * @param version  Specific version to install (latest if null).
     * @param force    Overwrite if the skill directory already exists.
     * @return Result describing success or failure.
     */
    suspend fun install(
        slug: String,
        version: String? = null,
        force: Boolean = false,
    ): InstallResult = operationMutex.withLock {
        val targetDir = File(managedSkillsDir, slug)

        if (targetDir.isDirectory && !force) {
            if (lockFile.isInstalled(slug)) {
                return@withLock InstallResult.AlreadyInstalled(slug, lockFile.getEntry(slug)?.version)
            }
            // Directory exists but not in lockfile — treat as force-install
        }

        log.info("Installing skill '$slug' (version=${version ?: "latest"})…")

        // Resolve the version to install
        val resolvedVersion = version ?: try {
            api.resolve(slug)?.latestVersion?.version
        } catch (e: Exception) {
            log.warning("Version resolve failed for '$slug': ${e.message}")
            null
        }

        // Download and extract
        val success = try {
            if (targetDir.isDirectory) targetDir.deleteRecursively()
            api.downloadAndExtract(slug, resolvedVersion, targetDir)
        } catch (e: Exception) {
            log.warning("Download failed for '$slug': ${e.message}")
            return@withLock InstallResult.Failed(slug, "Download failed: ${e.message}")
        }

        if (!success) {
            return@withLock InstallResult.Failed(slug, "Download or extraction failed")
        }

        // Verify the extracted content has a SKILL.md
        val skillFile = File(targetDir, "SKILL.md")
        if (!skillFile.isFile) {
            log.warning("Downloaded skill '$slug' has no SKILL.md — cleaning up")
            targetDir.deleteRecursively()
            return@withLock InstallResult.Failed(slug, "Skill bundle missing SKILL.md")
        }

        // Update lockfile
        lockFile.recordInstall(slug, resolvedVersion)

        // Reload skill registry so the new skill is immediately available
        reloadSkillRegistry()

        log.info("Installed skill '$slug' v${resolvedVersion ?: "unknown"}")
        InstallResult.Success(slug, resolvedVersion)
    }

    // ── Two-phase install (with threat assessment) ─────────────────

    /**
     * Phase 1: download a skill, extract it, and run a threat assessment.
     *
     * The skill files are written to disk but **not** registered in the
     * lockfile or skill registry. The caller should inspect the returned
     * [ThreatAssessment] and then call [confirmInstall] or
     * [cancelPendingInstall].
     */
    suspend fun downloadAndAssess(
        slug: String,
        version: String? = null,
        force: Boolean = false,
    ): DownloadAssessResult = operationMutex.withLock {
        val targetDir = File(managedSkillsDir, slug)

        if (targetDir.isDirectory && !force) {
            if (lockFile.isInstalled(slug)) {
                return@withLock DownloadAssessResult.AlreadyInstalled(
                    slug, lockFile.getEntry(slug)?.version,
                )
            }
        }

        log.info("Downloading skill '$slug' for assessment (version=${version ?: "latest"})…")

        val resolvedVersion = version ?: try {
            api.resolve(slug)?.latestVersion?.version
        } catch (e: Exception) {
            log.warning("Version resolve failed for '$slug': ${e.message}")
            null
        }

        try {
            if (targetDir.isDirectory) targetDir.deleteRecursively()
            if (!api.downloadAndExtract(slug, resolvedVersion, targetDir)) {
                return@withLock DownloadAssessResult.Failed(slug, "Download or extraction failed")
            }
        } catch (e: Exception) {
            return@withLock DownloadAssessResult.Failed(slug, "Download failed: ${e.message}")
        }

        val skillFile = File(targetDir, "SKILL.md")
        if (!skillFile.isFile) {
            targetDir.deleteRecursively()
            return@withLock DownloadAssessResult.Failed(slug, "Skill bundle missing SKILL.md")
        }

        // Fetch server-side moderation flags for the skill
        val moderation = try {
            api.getSkill(slug).moderation
        } catch (e: Exception) {
            log.warning("Could not fetch moderation data for '$slug': ${e.message}")
            null
        }

        val assessment = SkillThreatAnalyzer.deepAssess(targetDir, moderation)
        log.info("Threat assessment for '$slug': ${assessment.level}")

        DownloadAssessResult.Ready(slug, resolvedVersion, assessment)
    }

    /**
     * Phase 2a: finalise a previously downloaded skill.
     *
     * Updates the lockfile and reloads the skill registry so the skill
     * becomes immediately available.
     */
    suspend fun confirmInstall(
        slug: String,
        version: String?,
    ): InstallResult = operationMutex.withLock {
        val targetDir = File(managedSkillsDir, slug)

        if (!targetDir.isDirectory || !File(targetDir, "SKILL.md").isFile) {
            return@withLock InstallResult.Failed(
                slug, "Skill files not found — was the download completed?",
            )
        }

        lockFile.recordInstall(slug, version)
        reloadSkillRegistry()

        log.info("Confirmed install of skill '$slug' v${version ?: "unknown"}")
        InstallResult.Success(slug, version)
    }

    /**
     * Phase 2b: cancel a pending install and clean up extracted files.
     */
    suspend fun cancelPendingInstall(slug: String) = operationMutex.withLock {
        val targetDir = File(managedSkillsDir, slug)
        if (targetDir.isDirectory && !lockFile.isInstalled(slug)) {
            targetDir.deleteRecursively()
            log.info("Cancelled pending install of skill '$slug'")
        }
    }

    // ── Uninstall ───────────────────────────────────────────────────

    /**
     * Uninstall a ClawHub skill by slug.
     *
     * Removes the skill directory, updates the lockfile, and reloads the registry.
     *
     * @return true if the skill was found and removed.
     */
    suspend fun uninstall(slug: String): Boolean = operationMutex.withLock {
        val targetDir = File(managedSkillsDir, slug)

        if (!targetDir.isDirectory && !lockFile.isInstalled(slug)) {
            log.warning("Skill '$slug' is not installed")
            return@withLock false
        }

        return@withLock withContext(Dispatchers.IO) {
            try {
                if (targetDir.isDirectory) {
                    targetDir.deleteRecursively()
                }
                lockFile.recordUninstall(slug)
                reloadSkillRegistry()
                log.info("Uninstalled skill '$slug'")
                true
            } catch (e: Exception) {
                log.warning("Failed to uninstall skill '$slug': ${e.message}")
                false
            }
        }
    }

    // ── Update ──────────────────────────────────────────────────────

    /**
     * Update a single installed skill to the latest (or specified) version.
     *
     * Compares the local content hash against the registry to determine
     * if an update is available. If so, re-downloads the skill.
     *
     * @return Result of the update attempt.
     */
    suspend fun update(
        slug: String,
        version: String? = null,
        force: Boolean = false,
    ): UpdateResult = operationMutex.withLock {
        if (!lockFile.isInstalled(slug)) {
            return@withLock UpdateResult.NotInstalled(slug)
        }

        val targetDir = File(managedSkillsDir, slug)
        val currentVersion = lockFile.getEntry(slug)?.version

        // Resolve whether an update is available
        val localHash = if (targetDir.isDirectory) computeContentHash(targetDir) else null
        val resolved = try {
            api.resolve(slug, localHash)
        } catch (e: Exception) {
            log.warning("Resolve failed for '$slug': ${e.message}")
            return@withLock UpdateResult.Failed(slug, "Version resolve failed: ${e.message}")
        }

        val targetVersion = version ?: resolved.latestVersion?.version
        if (targetVersion == null) {
            return@withLock UpdateResult.Failed(slug, "No version available on registry")
        }

        // If we match the latest and no explicit version requested, we're up to date
        if (!force && version == null && resolved.match != null &&
            resolved.match.version == resolved.latestVersion?.version
        ) {
            return@withLock UpdateResult.AlreadyUpToDate(slug, currentVersion)
        }

        // Download the new version
        val success = try {
            if (targetDir.isDirectory) targetDir.deleteRecursively()
            api.downloadAndExtract(slug, targetVersion, targetDir)
        } catch (e: Exception) {
            return@withLock UpdateResult.Failed(slug, "Download failed: ${e.message}")
        }

        if (!success) {
            return@withLock UpdateResult.Failed(slug, "Download or extraction failed")
        }

        lockFile.recordInstall(slug, targetVersion)
        reloadSkillRegistry()

        log.info("Updated skill '$slug' from v${currentVersion ?: "?"} to v$targetVersion")
        UpdateResult.Updated(slug, currentVersion, targetVersion)
    }

    /**
     * Update all installed ClawHub skills to their latest versions.
     *
     * @return List of update results (one per installed skill).
     */
    suspend fun updateAll(force: Boolean = false): List<UpdateResult> {
        val slugs = lockFile.getAllEntries().keys.toList()
        return slugs.map { slug -> update(slug, force = force) }
    }

    // ── Query installed skills ──────────────────────────────────────

    /**
     * List all installed ClawHub skills (from the lockfile).
     */
    fun listInstalled(): List<InstalledClawHubSkill> {
        return lockFile.getAllEntries().map { (slug, entry) ->
            val targetDir = File(managedSkillsDir, slug)
            val skill = if (targetDir.isDirectory) {
                SkillLoader.parseSkillFile(File(targetDir, "SKILL.md"), targetDir)
            } else null

            InstalledClawHubSkill(
                slug = slug,
                displayName = skill?.name ?: slug,
                version = entry.version,
                installedAt = entry.installedAt,
                localDir = targetDir.absolutePath,
            )
        }
    }

    /**
     * Check if a skill slug is installed via ClawHub.
     */
    fun isInstalled(slug: String): Boolean = lockFile.isInstalled(slug)

    // ── Internals ───────────────────────────────────────────────────

    /**
     * Reload the skill registry so newly installed/removed skills take effect.
     *
     * Triggers [SkillRegistry.requestReload] which invokes the caller-supplied
     * reload callback. This lets the host app re-run [SkillRegistry.load] with
     * its full set of directories (workspace, managed, bundled, extra) and then
     * merge ClawHub entries if needed.
     */
    private fun reloadSkillRegistry() {
        log.fine("Requesting skill registry reload after ClawHub operation")
        skillRegistry.requestReload()
    }

    /**
     * Compute a SHA-256 content hash of all files in a skill directory.
     * Used for version matching against the registry (same as ClawHub CLI).
     */
    private fun computeContentHash(dir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val files = dir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(dir).path }

        for (file in files) {
            val relativePath = file.relativeTo(dir).path
            digest.update(relativePath.toByteArray())
            digest.update(file.readBytes())
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

// ── Result types ────────────────────────────────────────────────────

/** Outcome of a skill install operation. */
sealed class InstallResult {
    abstract val slug: String

    data class Success(override val slug: String, val version: String?) : InstallResult()
    data class AlreadyInstalled(override val slug: String, val version: String?) : InstallResult()
    data class Failed(override val slug: String, val reason: String) : InstallResult()
}

/** Outcome of a skill update operation. */
sealed class UpdateResult {
    abstract val slug: String

    data class Updated(
        override val slug: String,
        val fromVersion: String?,
        val toVersion: String,
    ) : UpdateResult()

    data class AlreadyUpToDate(override val slug: String, val version: String?) : UpdateResult()
    data class NotInstalled(override val slug: String) : UpdateResult()
    data class Failed(override val slug: String, val reason: String) : UpdateResult()
}
