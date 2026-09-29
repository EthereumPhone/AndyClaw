package org.ethereumphone.andyclaw.skills.customtools

import android.util.Log
import kotlinx.serialization.json.Json
import org.ethereumphone.andyclaw.extensions.clawhub.SafePaths
import java.io.File

class CustomToolStore(private val dir: File) {

    companion object {
        private const val TAG = "CustomToolStore"
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        /**
         * The only names `create_custom_tool` has ever accepted. Enforced here, on
         * every read, write and delete, because the name becomes `<name>.json` in
         * [dir]: `delete_custom_tool {"name":"../pending_approvals"}` deleted the
         * approval queue, and `test_custom_tool` ran code out of any JSON file in
         * the sandbox. Checking it only in the create path left every other caller
         * (delete, test, backup restore) open.
         */
        val NAME_REGEX = Regex("^[a-z][a-z0-9_]{0,48}$")

        fun isValidName(name: String): Boolean = NAME_REGEX.matches(name)
    }

    init {
        dir.mkdirs()
    }

    /** `<name>.json` in [dir], or null for a name the store does not accept. */
    private fun fileFor(name: String): File? =
        if (isValidName(name)) SafePaths.childOf(dir, "$name.json") else null

    fun save(tool: CustomToolDefinition) {
        val file = requireNotNull(fileFor(tool.name)) { "Invalid custom tool name" }
        file.writeText(json.encodeToString(CustomToolDefinition.serializer(), tool))
        Log.i(TAG, "Saved custom tool '${tool.name}'")
    }

    fun loadAll(): List<CustomToolDefinition> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString(CustomToolDefinition.serializer(), file.readText())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load custom tool from ${file.name}: ${e.message}")
                    null
                }
            }
            // A definition's name is what the tool is registered and later looked up
            // by; one the store would refuse could never be deleted or tested again.
            ?.filter { isValidName(it.name) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun load(name: String): CustomToolDefinition? {
        val file = fileFor(name) ?: return null
        if (!file.isFile) return null
        return try {
            json.decodeFromString(CustomToolDefinition.serializer(), file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load custom tool '$name': ${e.message}")
            null
        }
    }

    fun delete(name: String): Boolean {
        val file = fileFor(name) ?: return false
        if (!file.isFile) return false
        val deleted = file.delete()
        if (deleted) Log.i(TAG, "Deleted custom tool '$name'")
        return deleted
    }

    fun exists(name: String): Boolean = fileFor(name)?.isFile == true
}
