package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileSystemSkill(private val context: Context) : AndyClawSkill {
    override val id = "filesystem"
    override val name = "File System"

    override val baseManifest = SkillManifest(
        description = "Read, write, and list files on the device filesystem.",
        tools = listOf(
            ToolDefinition(
                name = "list_directory",
                description = "List files and directories at a given path.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "path" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The directory path to list"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("path"))),
                )),
            ),
            ToolDefinition(
                name = "read_file",
                description = "Read the contents of a file. Returns the first N bytes.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "path" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The file path to read"),
                        )),
                        "max_bytes" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Maximum bytes to read (default 100000)"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("path"))),
                )),
            ),
            ToolDefinition(
                name = "write_file",
                description = "Write content to a file. Creates the file if it doesn't exist.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "path" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The file path to write to"),
                        )),
                        "content" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The content to write"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("content"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "file_info",
                description = "Get detailed information about a file or directory.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "path" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The file or directory path"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("path"))),
                )),
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "list_directory" -> listDirectory(params)
            "read_file" -> readFile(params)
            "write_file" -> writeFile(params)
            "file_info" -> fileInfo(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun resolvePath(path: String): File {
        val file = File(path)
        if (file.isAbsolute) return file
        return File(context.filesDir, path)
    }

    private fun listDirectory(params: JsonObject): SkillResult {
        val path = params["path"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: path")
        val dir = resolvePath(path)
        if (!dir.exists()) return SkillResult.Error("Directory not found: $path")
        if (!dir.isDirectory) return SkillResult.Error("Not a directory: $path")

        return try {
            val entries = dir.listFiles()?.map { file ->
                buildJsonObject {
                    put("name", file.name)
                    put("type", if (file.isDirectory) "directory" else "file")
                    put("size", file.length())
                    put("readable", file.canRead())
                    put("writable", file.canWrite())
                }
            } ?: emptyList()
            SkillResult.Success(JsonArray(entries).toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to list directory: ${e.message}")
        }
    }

    private fun readFile(params: JsonObject): SkillResult {
        val path = params["path"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: path")
        val maxBytes = params["max_bytes"]?.jsonPrimitive?.intOrNull ?: 100_000
        val file = resolvePath(path)
        if (!file.exists()) return SkillResult.Error("File not found: $path")
        if (!file.isFile) return SkillResult.Error("Not a file: $path")
        if (!file.canRead()) return SkillResult.Error("File not readable: $path")

        return try {
            val content = if (file.length() > maxBytes) {
                file.inputStream().use { it.readNBytes(maxBytes).decodeToString() }
            } else {
                file.readText()
            }
            val result = buildJsonObject {
                put("content", content)
                put("size", file.length())
                if (file.length() > maxBytes) put("truncated", true)
            }
            SkillResult.Success(result.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to read file: ${e.message}")
        }
    }

    private fun writeFile(params: JsonObject): SkillResult {
        val path = params["path"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: path")
        val content = params["content"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: content")
        val file = resolvePath(path)

        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            SkillResult.Success(buildJsonObject {
                put("success", true)
                put("path", file.absolutePath)
                put("bytes_written", content.toByteArray().size)
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to write file: ${e.message}")
        }
    }

    private fun fileInfo(params: JsonObject): SkillResult {
        val path = params["path"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: path")
        val file = resolvePath(path)
        if (!file.exists()) return SkillResult.Error("Path not found: $path")

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        return try {
            val info = buildJsonObject {
                put("path", file.absolutePath)
                put("name", file.name)
                put("type", if (file.isDirectory) "directory" else "file")
                put("size", file.length())
                put("readable", file.canRead())
                put("writable", file.canWrite())
                put("executable", file.canExecute())
                put("last_modified", dateFormat.format(Date(file.lastModified())))
                if (file.isDirectory) {
                    put("child_count", file.listFiles()?.size ?: 0)
                }
            }
            SkillResult.Success(info.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to get file info: ${e.message}")
        }
    }
}
