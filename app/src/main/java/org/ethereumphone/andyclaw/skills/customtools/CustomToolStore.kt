package org.ethereumphone.andyclaw.skills.customtools

import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

class CustomToolStore(private val dir: File) {

    companion object {
        private const val TAG = "CustomToolStore"
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }

    init {
        dir.mkdirs()
    }

    fun save(tool: CustomToolDefinition) {
        val file = File(dir, "${tool.name}.json")
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
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun load(name: String): CustomToolDefinition? {
        val file = File(dir, "$name.json")
        if (!file.isFile) return null
        return try {
            json.decodeFromString(CustomToolDefinition.serializer(), file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load custom tool '$name': ${e.message}")
            null
        }
    }

    fun delete(name: String): Boolean {
        val file = File(dir, "$name.json")
        if (!file.isFile) return false
        val deleted = file.delete()
        if (deleted) Log.i(TAG, "Deleted custom tool '$name'")
        return deleted
    }

    fun exists(name: String): Boolean = File(dir, "$name.json").isFile
}
