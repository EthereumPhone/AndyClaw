package org.ethereumphone.andyclaw.extensions.clawhub

import kotlinx.serialization.json.Json
import java.io.File
import java.util.logging.Logger

/**
 * Manages the `.clawhub/lock.json` file that tracks installed ClawHub skills.
 *
 * The lockfile format mirrors the official ClawHub CLI:
 * ```json
 * {
 *   "version": 1,
 *   "skills": {
 *     "my-skill": { "version": "1.0.0", "installedAt": 1700000000000 }
 *   }
 * }
 * ```
 *
 * @param baseDir The directory that contains the `.clawhub/` folder
 *                (typically the managed skills directory or workspace root).
 */
class ClawHubLockFile(private val baseDir: File) {

    private val log = Logger.getLogger("ClawHubLockFile")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val lockDir: File get() = File(baseDir, LOCK_DIR)
    private val lockFile: File get() = File(lockDir, LOCK_FILENAME)

    private var data: ClawHubLockData = ClawHubLockData()

    /**
     * Load the lockfile from disk. Creates default empty state if missing.
     */
    fun load(): ClawHubLockData {
        if (!lockFile.isFile) {
            data = ClawHubLockData()
            return data
        }
        data = try {
            val raw = lockFile.readText()
            json.decodeFromString(ClawHubLockData.serializer(), raw)
        } catch (e: Exception) {
            log.warning("Failed to parse lockfile, starting fresh: ${e.message}")
            ClawHubLockData()
        }
        return data
    }

    /**
     * Persist the current lockfile state to disk.
     */
    fun save() {
        try {
            lockDir.mkdirs()
            val raw = json.encodeToString(ClawHubLockData.serializer(), data)
            lockFile.writeText(raw)
        } catch (e: Exception) {
            log.warning("Failed to save lockfile: ${e.message}")
        }
    }

    /**
     * Record that a skill was installed (or updated).
     */
    fun recordInstall(slug: String, version: String?) {
        data.skills[slug] = ClawHubLockEntry(
            version = version,
            installedAt = System.currentTimeMillis(),
        )
        save()
    }

    /**
     * Remove a skill record from the lockfile.
     */
    fun recordUninstall(slug: String) {
        data.skills.remove(slug)
        save()
    }

    /**
     * Get the lock entry for a specific skill, or null if not tracked.
     */
    fun getEntry(slug: String): ClawHubLockEntry? = data.skills[slug]

    /**
     * Get all tracked skill slugs and their lock entries.
     */
    fun getAllEntries(): Map<String, ClawHubLockEntry> = data.skills.toMap()

    /**
     * Check if a skill slug is tracked in the lockfile.
     */
    fun isInstalled(slug: String): Boolean = data.skills.containsKey(slug)

    companion object {
        private const val LOCK_DIR = ".clawhub"
        private const val LOCK_FILENAME = "lock.json"
    }
}
