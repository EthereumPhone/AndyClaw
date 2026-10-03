package org.ethereumphone.andyclaw.extensions.clawhub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ethereumphone.andyclaw.skills.SkillLoader
import org.ethereumphone.andyclaw.skills.SkillRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
    private val riskDataCache = ConcurrentHashMap<String, ClawHubRiskData>()
    private val pendingVersions = ConcurrentHashMap<String, String?>()
    /** How each pending install was assessed, for a confirmation that did not show it. */
    private val pendingLevels = ConcurrentHashMap<String, ThreatLevel>()

    init {
        lockFile.load()
        // What waits in staging is a pending install of a process that is gone: nobody will
        // confirm it now.
        stagingRoot.deleteRecursively()
    }

    // ── Risk data ───────────────────────────────────────────────────

    /**
     * Fetch merged risk data (skill-level moderation + version-level
     * security analysis) for a skill. Results are cached per slug.
     */
    suspend fun getRiskData(slug: String): ClawHubRiskData? {
        // The slug is spliced into the API path; don't let `../` walk it.
        if (!SafePaths.isValidClawHubSlug(slug)) return null
        riskDataCache[slug]?.let { return it }
        return try {
            val detail = api.getSkill(slug)
            val versionSecurity = detail.latestVersion?.version?.let { ver ->
                try {
                    api.getVersionDetail(slug, ver).version?.security
                } catch (_: Exception) {
                    null
                }
            }
            ClawHubRiskData(
                moderation = detail.moderation,
                versionSecurity = versionSecurity,
            ).also { riskDataCache[slug] = it }
        } catch (e: Exception) {
            log.fine("Could not fetch risk data for '$slug': ${e.message}")
            null
        }
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
        if (!SafePaths.isValidClawHubSlug(slug)) return null
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
        val targetDir = skillDir(slug)
            ?: return@withLock InstallResult.Failed(slug, INVALID_SLUG)

        if (targetDir.isDirectory && !force) {
            if (lockFile.isInstalled(slug)) {
                return@withLock InstallResult.AlreadyInstalled(slug, lockFile.getEntry(slug)?.version)
            }
            // Directory exists but not in lockfile — treat as force-install
        }

        log.info("Installing skill '$slug' (version=${version ?: "latest"})…")

        val resolvedVersion = version ?: try {
            api.getSkill(slug).latestVersion?.version
        } catch (e: Exception) {
            log.warning("Version resolve failed for '$slug': ${e.message}")
            null
        }

        if (resolvedVersion == null) {
            return@withLock InstallResult.Failed(
                slug, "Could not resolve version — the skill may not exist on ClawHub",
            )
        }

        val staged = when (val s = downloadToStaging(slug, resolvedVersion)) {
            is Staged.Ok -> s.dir
            is Staged.Error -> return@withLock InstallResult.Failed(slug, s.reason)
        }
        if (!swapIn(staged, targetDir)) {
            return@withLock InstallResult.Failed(slug, "Could not move the skill into place")
        }

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
     * The skill files are written to staging (`.clawhub/staging/<slug>`), **not** to the
     * managed directory, the lockfile or the skill registry. The caller should inspect the
     * returned [ThreatAssessment] and then call [confirmInstall] or [cancelPendingInstall].
     * Swapped in here, a bundle nobody confirmed — a HIGH or CRITICAL one whose dialog was
     * stopped or left — was registered as a live skill on the next reload.
     */
    suspend fun downloadAndAssess(
        slug: String,
        version: String? = null,
        force: Boolean = false,
    ): DownloadAssessResult = operationMutex.withLock {
        val targetDir = skillDir(slug)
            ?: return@withLock DownloadAssessResult.Failed(slug, INVALID_SLUG)

        if (targetDir.isDirectory && !force) {
            if (lockFile.isInstalled(slug)) {
                return@withLock DownloadAssessResult.AlreadyInstalled(
                    slug, lockFile.getEntry(slug)?.version,
                )
            }
        }

        log.info("Downloading skill '$slug' for assessment (version=${version ?: "latest"})…")

        val skillDetail = try {
            api.getSkill(slug)
        } catch (e: Exception) {
            log.warning("Version resolve failed for '$slug': ${e.message}")
            return@withLock DownloadAssessResult.Failed(
                slug, "Could not resolve skill — it may not exist on ClawHub",
            )
        }

        val resolvedVersion = version ?: skillDetail.latestVersion?.version
        if (resolvedVersion == null) {
            return@withLock DownloadAssessResult.Failed(
                slug, "No published version found for this skill on ClawHub",
            )
        }

        val staged = when (val s = downloadToStaging(slug, resolvedVersion)) {
            is Staged.Ok -> s.dir
            is Staged.Error -> return@withLock DownloadAssessResult.Failed(slug, s.reason)
        }

        val versionSecurity = try {
            api.getVersionDetail(slug, resolvedVersion).version?.security
        } catch (_: Exception) {
            null
        }
        val riskData = ClawHubRiskData(
            moderation = skillDetail.moderation,
            versionSecurity = versionSecurity,
        )

        val assessment = SkillThreatAnalyzer.deepAssess(staged, riskData)
        log.info("Threat assessment for '$slug': ${assessment.level}")

        DownloadAssessResult.Ready(slug, resolvedVersion, assessment).also {
            pendingVersions[slug] = resolvedVersion
            pendingLevels[slug] = assessment.level
        }
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
        pendingVersions.remove(slug)
        pendingLevels.remove(slug)
        val targetDir = skillDir(slug)
            ?: return@withLock InstallResult.Failed(slug, INVALID_SLUG)

        // Only what downloadAndAssess staged and assessed is moved in.
        val staged = File(stagingRoot, slug)
        if (!staged.isDirectory || findSkillMd(staged) == null) {
            return@withLock InstallResult.Failed(
                slug, "Skill files not found — was the download completed?",
            )
        }
        if (!swapIn(staged, targetDir)) {
            return@withLock InstallResult.Failed(slug, "Could not move the skill into place")
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
        pendingVersions.remove(slug)
        pendingLevels.remove(slug)
        val targetDir = skillDir(slug) ?: return@withLock
        File(stagingRoot, slug).deleteRecursively()
        // A pending install an older build left in the managed directory itself.
        if (targetDir.isDirectory && !lockFile.isInstalled(slug)) {
            targetDir.deleteRecursively()
        }
        log.info("Cancelled pending install of skill '$slug'")
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
        val targetDir = skillDir(slug)
        if (targetDir == null) {
            // Never a directory we may delete. A lockfile row by that name can only
            // have come from a hand-edited or restored lockfile; drop the row so it
            // stops showing as installed, and touch nothing on disk.
            if (!lockFile.isInstalled(slug)) return@withLock false
            lockFile.recordUninstall(slug)
            reloadSkillRegistry()
            return@withLock true
        }

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

        val targetDir = skillDir(slug)
            ?: return@withLock UpdateResult.Failed(slug, INVALID_SLUG)
        val currentVersion = lockFile.getEntry(slug)?.version

        val detail = try {
            api.getSkill(slug)
        } catch (e: Exception) {
            log.warning("Resolve failed for '$slug': ${e.message}")
            return@withLock UpdateResult.Failed(slug, "Version resolve failed: ${e.message}")
        }

        val targetVersion = version ?: detail.latestVersion?.version
        if (targetVersion == null) {
            return@withLock UpdateResult.Failed(slug, "No version available on registry")
        }

        if (!force && version == null && currentVersion == targetVersion) {
            return@withLock UpdateResult.AlreadyUpToDate(slug, currentVersion)
        }

        // Download the new version beside the installed one. The installed copy is
        // only replaced once the new one has extracted cleanly and passed the same
        // threat assessment an install gets — an update used to delete first and
        // assess never, so a failed download lost the skill and a skill that turned
        // malicious in a later version was swapped in without a look.
        val staged = when (val s = downloadToStaging(slug, targetVersion)) {
            is Staged.Ok -> s.dir
            is Staged.Error -> return@withLock UpdateResult.Failed(slug, s.reason)
        }

        val newAssessment = SkillThreatAnalyzer.deepAssess(
            staged,
            ClawHubRiskData(detail.moderation, versionSecurityOf(slug, targetVersion)),
        )
        val currentLevel = if (targetDir.isDirectory && findSkillMd(targetDir) != null) {
            SkillThreatAnalyzer.deepAssess(
                targetDir,
                ClawHubRiskData(detail.moderation, currentVersion?.let { versionSecurityOf(slug, it) }),
            ).level
        } else {
            null
        }
        updateBlockReason(currentLevel, newAssessment.level)?.let { reason ->
            staged.deleteRecursively()
            log.warning("Refused update of '$slug' to v$targetVersion: $reason")
            return@withLock UpdateResult.Failed(slug, reason)
        }

        if (!swapIn(staged, targetDir)) {
            return@withLock UpdateResult.Failed(slug, "Could not move the new version into place")
        }

        lockFile.recordInstall(slug, targetVersion)
        reloadSkillRegistry()

        log.info("Updated skill '$slug' from v${currentVersion ?: "?"} to v$targetVersion")
        UpdateResult.Updated(slug, currentVersion, targetVersion)
    }

    /**
     * Update all installed ClawHub skills to their latest versions.
     *
     * Spaces requests with a 500ms gap between skills to stay within
     * the ClawHub API rate limit budget (each update = 1 read + 1 download).
     *
     * @return List of update results (one per installed skill).
     */
    suspend fun updateAll(force: Boolean = false): List<UpdateResult> {
        val slugs = lockFile.getAllEntries().keys.toList()
        return slugs.mapIndexed { index, slug ->
            if (index > 0) delay(500)
            update(slug, force = force)
        }
    }

    // ── Query installed skills ──────────────────────────────────────

    /**
     * List all installed ClawHub skills (from the lockfile).
     */
    fun listInstalled(): List<InstalledClawHubSkill> {
        return lockFile.getAllEntries().map { (slug, entry) ->
            // An unsafe slug still lists (so it can be uninstalled), but nothing is
            // read from wherever it would resolve to.
            val targetDir = skillDir(slug)
            val skillMd = if (targetDir?.isDirectory == true) findSkillMd(targetDir) else null
            val skill = skillMd?.let { SkillLoader.parseSkillFile(it, targetDir!!) }

            InstalledClawHubSkill(
                slug = slug,
                displayName = skill?.name ?: slug,
                version = entry.version,
                installedAt = entry.installedAt,
                localDir = targetDir?.absolutePath ?: "",
            )
        }
    }

    /**
     * Check if a skill slug is installed via ClawHub.
     */
    fun isInstalled(slug: String): Boolean = lockFile.isInstalled(slug)

    /**
     * Whether a skill has been downloaded and assessed but not yet confirmed.
     * True when it is staged, is not in the lockfile, and the version was
     * recorded during [downloadAndAssess].
     */
    fun hasPendingInstall(slug: String): Boolean {
        skillDir(slug) ?: return false
        return File(stagingRoot, slug).isDirectory && !lockFile.isInstalled(slug) && pendingVersions.containsKey(slug)
    }

    /** How a pending install was assessed, or null when there is none. */
    fun getPendingLevel(slug: String): ThreatLevel? = pendingLevels[slug]

    /**
     * Return the resolved version from a pending [downloadAndAssess] call,
     * or null if there is no pending install for this slug.
     */
    fun getPendingVersion(slug: String): String? = pendingVersions[slug]

    /**
     * Read the raw SKILL.md content for an installed skill.
     *
     * @return The file content, or null if the skill is not installed or
     *         the SKILL.md file cannot be read.
     */
    fun readSkillContent(slug: String): String? {
        val targetDir = skillDir(slug) ?: return null
        val skillMd = findSkillMd(targetDir) ?: return null
        return try {
            skillMd.readText()
        } catch (e: Exception) {
            log.warning("Failed to read SKILL.md for '$slug': ${e.message}")
            null
        }
    }

    // ── Internals ───────────────────────────────────────────────────

    /**
     * The install directory for [slug], or null if the slug is not one ClawHub
     * could have issued or would resolve anywhere but directly under
     * [managedSkillsDir]. Every entry point goes through this: `File(dir, "..")`
     * is `filesDir`, and an install deletes its target before extracting.
     */
    private fun skillDir(slug: String): File? =
        if (SafePaths.isValidClawHubSlug(slug)) SafePaths.childOf(managedSkillsDir, slug) else null

    /**
     * `.clawhub/staging/` (and `.clawhub/replaced/` in [swapIn]) — not direct
     * children with a SKILL.md, so the skill loader never picks them up.
     */
    private val stagingRoot: File get() = File(managedSkillsDir, STAGING_DIR)

    private sealed class Staged {
        data class Ok(val dir: File) : Staged()
        data class Error(val reason: String) : Staged()
    }

    /**
     * Download and extract [slug]@[version] into a fresh staging directory and
     * check it has a SKILL.md. Leaves the installed copy (if any) untouched.
     */
    private suspend fun downloadToStaging(slug: String, version: String): Staged {
        val staging = File(stagingRoot, slug)
        staging.deleteRecursively()
        val ok = try {
            api.downloadAndExtract(slug, version, staging)
        } catch (e: Exception) {
            log.warning("Download failed for '$slug': ${e.message}")
            staging.deleteRecursively()
            return Staged.Error("Download failed: ${e.message}")
        }
        if (!ok) {
            staging.deleteRecursively()
            return Staged.Error("Download or extraction failed")
        }
        if (findSkillMd(staging) == null) {
            val extracted = staging.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(staging).path }
                .toList()
            log.warning(
                "Skill '$slug' ZIP missing SKILL.md at root. " +
                    "Extracted ${extracted.size} file(s): ${extracted.take(10)}",
            )
            staging.deleteRecursively()
            return Staged.Error("Skill bundle missing SKILL.md")
        }
        return Staged.Ok(staging)
    }

    /**
     * Replace [targetDir] with [staged]. The old copy is moved aside first and put
     * back if the rename fails, so a failure leaves the previous version installed.
     */
    private fun swapIn(staged: File, targetDir: File): Boolean {
        val old = File(managedSkillsDir, "$REPLACED_DIR/${targetDir.name}")
        old.deleteRecursively()
        old.parentFile?.mkdirs()
        if (targetDir.exists() && !targetDir.renameTo(old)) {
            staged.deleteRecursively()
            return false
        }
        if (!staged.renameTo(targetDir)) {
            if (old.exists()) old.renameTo(targetDir)
            staged.deleteRecursively()
            return false
        }
        old.deleteRecursively()
        return true
    }

    private suspend fun versionSecurityOf(slug: String, version: String): ClawHubVersionSecurity? =
        try {
            api.getVersionDetail(slug, version).version?.security
        } catch (_: Exception) {
            null
        }

    /**
     * Find the SKILL.md file in a directory, case-insensitively.
     * ClawHub skills may use `SKILL.md`, `skill.md`, `Skill.md`, etc.
     */
    private fun findSkillMd(dir: File): File? {
        return dir.listFiles()?.firstOrNull {
            it.isFile && it.name.equals("SKILL.md", ignoreCase = true)
        }
    }

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

    companion object {
        private const val STAGING_DIR = ".clawhub/staging"
        private const val REPLACED_DIR = ".clawhub/replaced"
        private const val INVALID_SLUG = "Invalid skill slug"

        /**
         * Whether an update from a version assessed at [current] to one assessed at
         * [next] must be refused, and why. An update is not interactive (the agent's
         * `clawhub_update`, the settings button, `updateAll`), so it may not raise
         * the risk the user accepted at install: CRITICAL is always refused, and a
         * MEDIUM-or-worse version is refused when it is worse than what is installed.
         * With nothing assessable installed, anything the install flow would have
         * prompted for (MEDIUM+) is refused — reinstall to see the prompt.
         */
        fun updateBlockReason(current: ThreatLevel?, next: ThreatLevel): String? = when {
            next == ThreatLevel.CRITICAL ->
                "New version assessed as ${next.displayName}; not installed"
            next >= ThreatLevel.MEDIUM && (current == null || next > current) ->
                "New version assessed as ${next.displayName} (installed: ${current?.displayName ?: "unknown"}); " +
                    "reinstall it to review the risk"
            else -> null
        }
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
