package org.ethereumphone.andyclaw.skills.termux

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Base64

/**
 * Outcome of a file-sync operation.
 */
data class SyncResult(
    val success: Boolean,
    val error: String? = null,
    val fileCount: Int = 0,
)

/**
 * Syncs ClawHub skill files from AndyClaw's app-private storage into
 * Termux's home directory so that scripts can be executed directly.
 *
 * Files are transferred by base64-encoding each file's content and
 * writing it via a Termux `bash -c` command.  This avoids any shared-
 * storage or content-provider complexity.
 *
 * Layout inside Termux:
 * ```
 * ~/.andyclaw/skills/<slug>/
 *   ├── SKILL.md
 *   ├── scripts/
 *   │   └── jarvis
 *   └── …
 * ```
 */
class TermuxSkillSync(
    private val runner: TermuxCommandRunner,
    context: Context,
) {
    companion object {
        private const val TAG = "TermuxSkillSync"
        const val SKILLS_BASE = ".andyclaw/skills"
        private const val MAX_FILE_SIZE = 512 * 1024L   // 512 KB per file
        private const val MAX_TOTAL_SIZE = 2 * 1024 * 1024L  // 2 MB total
        private const val SYNC_TIMEOUT_MS = 60_000L
        private const val PREFS_NAME = "termux_skill_sync"
        private const val KEY_SYNCED_SLUGS = "synced_slugs"
        private const val KEY_DPKG_CONFIGURED = "dpkg_noninteractive_configured"

        private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        private const val DPKG_CFG = "$TERMUX_PREFIX/etc/dpkg/dpkg.cfg"
        private const val TERMUX_PROPS =
            "${TermuxCommandRunner.TERMUX_HOME}/.termux/termux.properties"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the absolute Termux-side path for a synced skill. */
    fun skillHomePath(slug: String): String =
        "${TermuxCommandRunner.TERMUX_HOME}/$SKILLS_BASE/$slug"

    // ── Sync tracking ───────────────────────────────────────────────

    fun getSyncedSlugs(): Set<String> =
        prefs.getStringSet(KEY_SYNCED_SLUGS, emptySet()) ?: emptySet()

    private fun markSynced(slug: String) {
        val slugs = getSyncedSlugs().toMutableSet().apply { add(slug) }
        prefs.edit().putStringSet(KEY_SYNCED_SLUGS, slugs).apply()
    }

    private fun markRemoved(slug: String) {
        val slugs = getSyncedSlugs().toMutableSet().apply { remove(slug) }
        prefs.edit().putStringSet(KEY_SYNCED_SLUGS, slugs).apply()
    }

    // ── Sync / Remove ───────────────────────────────────────────────

    /**
     * Sync a skill directory into Termux home.
     *
     * Removes any previous version first, writes every file via base64,
     * and marks shell scripts as executable.
     */
    suspend fun syncSkill(slug: String, sourceDir: File): SyncResult {
        if (!runner.isTermuxInstalled()) {
            return SyncResult(false, "Termux is not installed")
        }

        val skillHome = skillHomePath(slug)

        // Collect files, respecting size limits
        val files = sourceDir.walkTopDown()
            .filter { it.isFile && it.length() <= MAX_FILE_SIZE }
            .toList()
        val totalBytes = files.sumOf { it.length() }
        if (totalBytes > MAX_TOTAL_SIZE) {
            return SyncResult(false, "Skill bundle too large (${totalBytes / 1024} KB > ${MAX_TOTAL_SIZE / 1024} KB)")
        }

        // Wipe old version and create base directory
        val mkdirResult = runner.run(
            "rm -rf '$skillHome' && mkdir -p '$skillHome'",
            timeoutMs = SYNC_TIMEOUT_MS,
        )
        if (!mkdirResult.isSuccess) {
            return SyncResult(false, "Failed to create skill directory: ${mkdirResult.stderr}")
        }

        // Write each file via base64
        for (file in files) {
            val relativePath = file.relativeTo(sourceDir).path
            val targetPath = "$skillHome/$relativePath"
            val targetDir = targetPath.substringBeforeLast('/')
            val base64 = Base64.getEncoder().encodeToString(file.readBytes())

            val writeResult = runner.run(
                "mkdir -p '$targetDir' && printf '%s' '$base64' | base64 -d > '$targetPath'",
                timeoutMs = 15_000,
            )
            if (!writeResult.isSuccess) {
                Log.w(TAG, "Failed to write $relativePath for $slug: ${writeResult.stderr}")
                return SyncResult(false, "Failed to write $relativePath: ${writeResult.stderr}")
            }
        }

        // Mark all scripts executable
        runner.run(
            "find '$skillHome' -type f \\( -name '*.sh' -o -path '*/scripts/*' \\) -exec chmod +x {} +",
            timeoutMs = 10_000,
        )

        markSynced(slug)
        Log.i(TAG, "Synced $slug (${files.size} files, ${totalBytes / 1024} KB)")
        return SyncResult(true, fileCount = files.size)
    }

    /**
     * Run the skill's declared setup script (if any) after sync.
     */
    suspend fun runSetup(slug: String, setupPath: String): TermuxCommandResult {
        val skillHome = skillHomePath(slug)
        return runner.run(
            "cd '$skillHome' && chmod +x '$setupPath' && bash '$setupPath'",
            timeoutMs = 120_000,
        )
    }

    /**
     * One-time Termux environment hardening for background (headless) use.
     *
     * 1. **dpkg.cfg** — adds `force-confdef` / `force-confold` so package
     *    upgrades never prompt for conffile resolution (no TTY → hang).
     * 2. **termux.properties** — ensures `allow-external-apps = true` is
     *    present and uncommented so the RUN_COMMAND intent keeps working
     *    across Termux updates or config resets.
     *
     * Both changes are idempotent; re-running is harmless.
     */
    suspend fun ensureNonInteractiveDefaults() {
        if (prefs.getBoolean(KEY_DPKG_CONFIGURED, false)) return
        if (!runner.isTermuxInstalled()) return

        val script = buildString {
            // ── dpkg: auto-accept maintainer configs ────────────
            append("grep -q 'force-confdef' '$DPKG_CFG' 2>/dev/null || ")
            append("echo 'force-confdef' >> '$DPKG_CFG'; ")
            append("grep -q 'force-confold' '$DPKG_CFG' 2>/dev/null || ")
            append("echo 'force-confold' >> '$DPKG_CFG'; ")

            // ── termux.properties: keep external-apps enabled ───
            append("mkdir -p '${TermuxCommandRunner.TERMUX_HOME}/.termux'; ")
            // If the file already has an uncommented allow-external-apps line, leave it alone.
            // Otherwise, uncomment a commented-out line or append a new one.
            append("if grep -qE '^allow-external-apps\\s*=\\s*true' '$TERMUX_PROPS' 2>/dev/null; then ")
            append("  true; ")
            append("elif grep -qE '^#.*allow-external-apps' '$TERMUX_PROPS' 2>/dev/null; then ")
            append("  sed -i 's/^#.*allow-external-apps.*/allow-external-apps = true/' '$TERMUX_PROPS'; ")
            append("else ")
            append("  echo 'allow-external-apps = true' >> '$TERMUX_PROPS'; ")
            append("fi")
        }
        val result = runner.run(script, timeoutMs = 10_000)
        if (result.isSuccess) {
            prefs.edit().putBoolean(KEY_DPKG_CONFIGURED, true).apply()
            Log.i(TAG, "Configured Termux for non-interactive / external-apps operation")
        } else {
            Log.w(TAG, "Failed to configure Termux defaults: ${result.stderr}")
        }
    }

    /**
     * Install missing Termux packages required by a skill.
     *
     * Configures dpkg for non-interactive operation first, then checks
     * `command -v` for each binary and installs those that are missing.
     *
     * Uses `apt-get` directly instead of `pkg` because `pkg` is a
     * wrapper that can still trigger interactive prompts.  We run
     * `apt-get update` ourselves since `pkg` normally does that
     * automatically but raw `apt-get install` does not.
     *
     * Full flag breakdown:
     * - `-y`                        assume "yes" to all prompts
     * - `--allow-downgrades`        don't fail on version mismatches
     * - `--allow-change-held-packages` don't fail on held packages
     * - `-o Dpkg::Options::=--force-confold`  keep current config files
     * - `-o Dpkg::Options::=--force-confdef`  use package defaults for new ones
     */
    suspend fun ensureBins(bins: List<String>): TermuxCommandResult {
        if (bins.isEmpty()) {
            return TermuxCommandResult(exitCode = 0, stdout = "", stderr = "")
        }

        ensureNonInteractiveDefaults()

        val checkScript = bins.joinToString("; ") { bin ->
            "command -v '$bin' >/dev/null 2>&1 || MISSING=\"\$MISSING $bin\""
        }
        val script = "MISSING=''; $checkScript; " +
            "if [ -n \"\$MISSING\" ]; then " +
            "apt-get update -y && " +
            "apt-get " +
            "-o Dpkg::Options::=--force-confold " +
            "-o Dpkg::Options::=--force-confdef " +
            "-y --allow-downgrades --allow-change-held-packages " +
            "install \$MISSING; " +
            "else echo 'all present'; fi"
        return runner.run(script, timeoutMs = 180_000)
    }

    /**
     * Remove a synced skill from Termux home.
     */
    suspend fun removeSkill(slug: String): Boolean {
        val skillHome = skillHomePath(slug)
        val result = runner.run("rm -rf '$skillHome'", timeoutMs = 10_000)
        markRemoved(slug)
        return result.isSuccess
    }

    /**
     * Remove skills from Termux that are no longer installed via ClawHub.
     */
    suspend fun cleanOrphans(activeTermuxSlugs: Set<String>) {
        val orphans = getSyncedSlugs() - activeTermuxSlugs
        for (slug in orphans) {
            Log.i(TAG, "Cleaning orphaned Termux skill: $slug")
            removeSkill(slug)
        }
    }
}
