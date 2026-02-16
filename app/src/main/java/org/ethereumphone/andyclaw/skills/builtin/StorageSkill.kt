package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import android.os.Environment
import android.os.StatFs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import java.io.File

class StorageSkill(private val context: Context) : AndyClawSkill {
    override val id = "storage"
    override val name = "Storage"

    override val baseManifest = SkillManifest(
        description = "Access device external storage.",
        tools = emptyList(),
    )

    override val privilegedManifest = SkillManifest(
        description = "Browse, read, and search files on external storage (privileged OS only).",
        tools = listOf(
            ToolDefinition(
                name = "list_storage_directory",
                description = "List files and directories at a path on external storage.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "path" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Directory path relative to /sdcard/ (default: root)"))),
                    )),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "read_storage_file",
                description = "Read the contents of a text file on external storage.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "path" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("File path relative to /sdcard/"))),
                        "max_bytes" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Max bytes to read (default 10000)"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("path"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "search_files",
                description = "Search for files matching a name pattern on external storage.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "query" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("File name pattern to search (case-insensitive substring)"))),
                        "path" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Directory to search in, relative to /sdcard/ (default: root)"))),
                        "limit" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Max results (default 50)"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("query"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "get_storage_info",
                description = "Get storage space information (total, free, used).",
                inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
                requiresApproval = true,
            ),
        ),
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        if (tier != Tier.PRIVILEGED) {
            return SkillResult.Error("$tool requires privileged OS access. Install AndyClaw as a system app on ethOS.")
        }
        return when (tool) {
            "list_storage_directory" -> listDirectory(params)
            "read_storage_file" -> readFile(params)
            "search_files" -> searchFiles(params)
            "get_storage_info" -> getStorageInfo()
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun resolveSafePath(relativePath: String): File? {
        val base = Environment.getExternalStorageDirectory()
        val resolved = File(base, relativePath).canonicalFile
        // Block access outside external storage and other app private data
        if (!resolved.path.startsWith(base.canonicalPath)) return null
        if (resolved.path.contains("/data/data/") || resolved.path.contains("/data/user/")) return null
        return resolved
    }

    private fun listDirectory(params: JsonObject): SkillResult {
        val path = params["path"]?.jsonPrimitive?.contentOrNull ?: ""
        val dir = resolveSafePath(path)
            ?: return SkillResult.Error("Access denied: path is outside allowed storage area")
        if (!dir.exists()) return SkillResult.Error("Directory not found: $path")
        if (!dir.isDirectory) return SkillResult.Error("Not a directory: $path")
        return try {
            val entries = dir.listFiles()?.map { file ->
                buildJsonObject {
                    put("name", file.name)
                    put("is_directory", file.isDirectory)
                    put("size", file.length())
                    put("last_modified", file.lastModified())
                }
            } ?: emptyList()
            SkillResult.Success(buildJsonObject {
                put("path", dir.path)
                put("count", entries.size)
                put("entries", JsonArray(entries))
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to list directory: ${e.message}")
        }
    }

    private fun readFile(params: JsonObject): SkillResult {
        val path = params["path"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: path")
        val maxBytes = params["max_bytes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 10000
        val file = resolveSafePath(path)
            ?: return SkillResult.Error("Access denied: path is outside allowed storage area")
        if (!file.exists()) return SkillResult.Error("File not found: $path")
        if (file.isDirectory) return SkillResult.Error("Path is a directory, not a file: $path")
        return try {
            val bytes = file.inputStream().use { it.readNBytes(maxBytes) }
            val content = String(bytes)
            val truncated = file.length() > maxBytes
            SkillResult.Success(buildJsonObject {
                put("path", file.path)
                put("size", file.length())
                put("truncated", truncated)
                put("content", content)
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to read file: ${e.message}")
        }
    }

    private fun searchFiles(params: JsonObject): SkillResult {
        val query = params["query"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: query")
        val path = params["path"]?.jsonPrimitive?.contentOrNull ?: ""
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 50
        val dir = resolveSafePath(path)
            ?: return SkillResult.Error("Access denied: path is outside allowed storage area")
        if (!dir.exists() || !dir.isDirectory) return SkillResult.Error("Directory not found: $path")
        return try {
            val results = mutableListOf<JsonObject>()
            searchRecursive(dir, query.lowercase(), results, limit)
            SkillResult.Success(buildJsonObject {
                put("query", query)
                put("count", results.size)
                put("results", JsonArray(results))
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to search files: ${e.message}")
        }
    }

    private fun searchRecursive(dir: File, query: String, results: MutableList<JsonObject>, limit: Int) {
        if (results.size >= limit) return
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (results.size >= limit) return
            if (file.name.lowercase().contains(query)) {
                results.add(buildJsonObject {
                    put("path", file.path)
                    put("name", file.name)
                    put("is_directory", file.isDirectory)
                    put("size", file.length())
                })
            }
            if (file.isDirectory && !file.name.startsWith(".")) {
                searchRecursive(file, query, results, limit)
            }
        }
    }

    private fun getStorageInfo(): SkillResult {
        return try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val totalBytes = stat.totalBytes
            val freeBytes = stat.freeBytes
            val usedBytes = totalBytes - freeBytes
            SkillResult.Success(buildJsonObject {
                put("total_bytes", totalBytes)
                put("free_bytes", freeBytes)
                put("used_bytes", usedBytes)
                put("total_gb", String.format("%.1f", totalBytes / 1_073_741_824.0))
                put("free_gb", String.format("%.1f", freeBytes / 1_073_741_824.0))
                put("used_gb", String.format("%.1f", usedBytes / 1_073_741_824.0))
                put("used_percent", String.format("%.1f", usedBytes * 100.0 / totalBytes))
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to get storage info: ${e.message}")
        }
    }
}
