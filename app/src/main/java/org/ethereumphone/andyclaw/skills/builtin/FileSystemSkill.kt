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
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.ExecutionEngine.currentProvenance
import org.ethereumphone.andyclaw.agent.currentRunToken
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

    private val workDir: File get() = context.filesDir

    override val baseManifest = SkillManifest(
        description = "Read, write, and list files in the app's sandbox directory (${context.filesDir.absolutePath}). All paths are resolved relative to this directory. No special permissions required.",
        tools = listOf(
            ToolDefinition(
                name = "list_directory",
                description = "List files and directories. Paths are relative to the app sandbox. Use '.' or '' for the root sandbox directory.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "path" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Directory path relative to app sandbox (e.g. '.' or 'scripts')"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("path"))),
                )),
            ),
            ToolDefinition(
                name = "read_file",
                description = "Read the contents of a file in the app sandbox. Returns the first N bytes.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "path" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("File path relative to app sandbox"),
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
                description = "Write content to a file in the app sandbox. Creates the file and parent directories if they don't exist.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "path" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("File path relative to app sandbox (e.g. 'scripts/hello.sh')"),
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
                description = "Get detailed information about a file or directory in the app sandbox.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "path" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("File or directory path relative to app sandbox"),
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
            "write_file" -> writeFile(params, isCleanOwnerRun())
            "file_info" -> fileInfo(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    /**
     * The owner's own chat that has not read anyone else's words in this run. Only such a run
     * may rewrite the owner-authority files ([OWNER_WRITABLE]). No token or no provenance
     * reads as not clean — the defaults are closed.
     */
    private suspend fun isCleanOwnerRun(): Boolean =
        currentProvenance() == Provenance.USER && currentRunToken()?.readThirdPartyContent != true

    private fun resolvePath(path: String, write: Boolean = false, cleanOwnerRun: Boolean = false): File {
        // Always resolve within the app sandbox - no permission needed
        val cleaned = path.trimStart('/')
        if (cleaned.isEmpty() || cleaned == ".") return workDir
        return File(workDir, cleaned).also {
            // Prevent path traversal outside the sandbox. A bare prefix match let "files2" pass
            // for "files".
            val root = workDir.canonicalPath
            val canon = it.canonicalPath
            require(canon == root || canon.startsWith(root + File.separator)) {
                "Path escapes app sandbox"
            }
            // AndyClaw's own control files are not the agent's to read or rewrite: who created
            // each scheduled job, the queued approvals, which Telegram chat is the owner's.
            // Rewriting any of them was a way to give a stranger the owner's authority.
            val rel = canon.removePrefix(root).trimStart(File.separatorChar)
            protectionError(rel, write, cleanOwnerRun)?.let { reason ->
                throw IllegalArgumentException("'$path' $reason")
            }
        }
    }

    internal companion object {
        const val MAX_READ_BYTES = 1024 * 1024

        val PROTECTED = setOf(
            org.ethereumphone.andyclaw.safety.TriggerProvenanceStore.FILENAME,
            org.ethereumphone.andyclaw.safety.PendingApprovalStore.FILENAME,
            org.ethereumphone.andyclaw.safety.PendingApprovalStore.OUTCOMES_FILENAME,
            "telegram_chats.json",
            "flows",
            "session_frames",
            // The gateway device identity (its private key). Not the agent's to read.
            "openclaw",
        )

        /**
         * Code and weights that run with AndyClaw's authority. Each has its own tool that
         * validates what goes in (create_custom_tool test-runs, skill_* tools, the CLI tool
         * manager, the model downloader); write_file into them skipped all of that. Readable.
         */
        val WRITE_PROTECTED = setOf(
            "custom-tools",
            "ai-skills",
            "skills",
            "clawhub-skills",
            "cli-tools",
            "models",
        )

        /**
         * Standing instructions every later run obeys: HEARTBEAT.md is fed to each heartbeat as
         * the owner's own task list (TRUSTED, clean token), soul.md and user_story.md go into
         * every system prompt. A tainted or background run writing one planted an instruction
         * that ran unattended forever. Only the owner's clean chat may write them — there is no
         * other editor for HEARTBEAT.md, so "add a heartbeat task" in chat keeps working.
         * heartbeat_journal.md is deliberately not here: the heartbeat writes it every run.
         */
        val OWNER_WRITABLE = setOf(
            "HEARTBEAT.md",
            "soul.md",
            "user_story.md",
        )

        private fun matches(rel: String, p: String) =
            rel == p || rel.startsWith("$p.") || rel.startsWith(p + File.separator)

        /** Why [rel] (canonical, relative to filesDir) may not be touched, or null if it may. */
        internal fun protectionError(rel: String, write: Boolean, cleanOwnerRun: Boolean): String? = when {
            PROTECTED.any { matches(rel, it) } ->
                "is AndyClaw's own state; the file tools don't read or write it"
            write && WRITE_PROTECTED.any { matches(rel, it) } ->
                "holds code or models AndyClaw runs; write_file can't change it (use the dedicated tool)"
            write && !cleanOwnerRun && OWNER_WRITABLE.any { matches(rel, it) } ->
                "holds the owner's standing instructions; only the owner's own chat, before it has " +
                    "read anyone else's content, may change it"
            else -> null
        }
    }

    private fun listDirectory(params: JsonObject): SkillResult {
        val path = params["path"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: path")
        val dir = try {
            resolvePath(path)
        } catch (e: IllegalArgumentException) {
            return SkillResult.Error(e.message ?: "Invalid path")
        }
        if (!dir.exists()) return SkillResult.Error("Directory not found: $path (resolved to ${dir.absolutePath})")
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
        // Clamped: a model-chosen max_bytes of 2^31 read a whole file into memory, and the
        // OutOfMemoryError that followed is an Error no catch below sees.
        val maxBytes = (params["max_bytes"]?.jsonPrimitive?.intOrNull ?: 100_000).coerceIn(1, MAX_READ_BYTES)
        val file = try {
            resolvePath(path)
        } catch (e: IllegalArgumentException) {
            return SkillResult.Error(e.message ?: "Invalid path")
        }
        if (!file.exists()) return SkillResult.Success("{\"error\":\"file_not_found\",\"message\":\"File does not exist yet: $path. You can create it with write_file.\"}")
        if (!file.isFile) return SkillResult.Error("Not a file: $path")
        if (!file.canRead()) return SkillResult.Error("File not readable: $path")

        return try {
            // Always a bounded read, whatever length() says: a file growing while we read it
            // (a log another tool appends to) used to be read to its end with readText().
            val read = file.inputStream().use { BoundedText.read(it, maxBytes) }
            val result = buildJsonObject {
                put("content", read.text)
                put("size", file.length())
                if (read.truncated) put("truncated", true)
            }
            SkillResult.Success(result.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to read file: ${e.message}")
        }
    }

    private fun writeFile(params: JsonObject, cleanOwnerRun: Boolean): SkillResult {
        val path = params["path"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: path")
        val content = params["content"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: content")
        val file = try {
            resolvePath(path, write = true, cleanOwnerRun = cleanOwnerRun)
        } catch (e: IllegalArgumentException) {
            return SkillResult.Error(e.message ?: "Invalid path")
        }

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
        val file = try {
            resolvePath(path)
        } catch (e: IllegalArgumentException) {
            return SkillResult.Error(e.message ?: "Invalid path")
        }
        if (!file.exists()) return SkillResult.Error("Path not found: $path (resolved to ${file.absolutePath})")

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
