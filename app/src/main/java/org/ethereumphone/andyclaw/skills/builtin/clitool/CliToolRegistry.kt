package org.ethereumphone.andyclaw.skills.builtin.clitool

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.ethereumphone.andyclaw.extensions.clawhub.SafePaths
import java.io.File

// ── Data models ─────────────────────────────────────────────────────────

@Serializable
data class CliToolEntry(
    val id: String,
    val name: String,
    val sourceType: String,      // "git", "npm", or "local"
    val sourceValue: String,     // repo URL, npm package name, or local path
    val binaryName: String? = null,
    val installCommand: String? = null,
    val envVarKeys: List<String> = emptyList(),
    val skillMdFiles: List<String> = emptyList(),
    val addedAt: Long = System.currentTimeMillis(),
    val installedAt: Long? = null,
)

// ── Registry persistence ────────────────────────────────────────────────

class CliToolRegistry(private val baseDir: File) {

    companion object {
        private const val TAG = "CliToolRegistry"
        private const val REGISTRY_FILE = "registry.json"

        /**
         * What `cli_tools_add` accepts as a new id. The id names a directory under
         * `filesDir/cli-tools`, a Termux temp dir and the config-store key prefix
         * `cli.<id>.`, so it is kept to a single plain word: no separators, no dots
         * (`..` was `filesDir`, and `a.b` would share `a`'s config keys).
         */
        val ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

        fun isValidNewId(id: String): Boolean = ID_REGEX.matches(id)
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val registryFile get() = File(baseDir, REGISTRY_FILE)

    init {
        baseDir.mkdirs()
    }

    @Synchronized
    fun getAll(): List<CliToolEntry> {
        if (!registryFile.exists()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(CliToolEntry.serializer()), registryFile.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read registry: ${e.message}")
            emptyList()
        }
    }

    @Synchronized
    fun get(id: String): CliToolEntry? = getAll().find { it.id == id }

    @Synchronized
    fun add(entry: CliToolEntry) {
        require(isValidNewId(entry.id)) { "Invalid CLI tool id" }
        val entries = getAll().toMutableList()
        entries.removeAll { it.id == entry.id }
        entries.add(entry)
        save(entries)
        getToolDir(entry.id)?.mkdirs()
    }

    @Synchronized
    fun update(entry: CliToolEntry) {
        val entries = getAll().toMutableList()
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            entries[index] = entry
            save(entries)
        }
    }

    @Synchronized
    fun remove(id: String) {
        val entries = getAll().toMutableList()
        entries.removeAll { it.id == id }
        save(entries)
        getToolDir(id)?.deleteRecursively()
    }

    /**
     * The cache directory for [id], or null if [id] would not resolve to its own
     * directory directly under [baseDir]. Ids registered before [ID_REGEX] existed
     * still get their directory as long as they are a single safe component.
     */
    fun getToolDir(id: String): File? {
        if (id == REGISTRY_FILE) return null
        return SafePaths.childOf(baseDir, id)
    }

    /**
     * A cached doc file, or null. [relativePath] comes straight from the model via
     * `cli_tools_info` (a READ tool, no prompt), so it must land strictly inside the
     * tool's directory — `../../shared_prefs/…` used to read anything in the sandbox.
     */
    fun getSkillMdContent(id: String, relativePath: String = "SKILL.md"): String? {
        val dir = getToolDir(id) ?: return null
        val file = SafePaths.resolveInside(dir, relativePath) ?: return null
        return if (file.isFile) file.readText() else null
    }

    /** Cache a doc file; false (and nothing written) if it would land outside the tool's dir. */
    fun saveSkillMd(id: String, relativePath: String, content: String): Boolean {
        val dir = getToolDir(id) ?: return false
        val file = SafePaths.resolveInside(dir, relativePath) ?: return false
        file.parentFile?.mkdirs()
        file.writeText(content)
        return true
    }

    private fun save(entries: List<CliToolEntry>) {
        try {
            registryFile.writeText(json.encodeToString(ListSerializer(CliToolEntry.serializer()), entries))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write registry: ${e.message}")
        }
    }
}
