package org.ethereumphone.andyclaw.skills.builtin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import java.util.concurrent.TimeUnit

class DriveSkill(
    private val getAccessToken: suspend () -> String,
) : AndyClawSkill {

    companion object {
        private const val BASE_URL = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3"
        private val JSON_TYPE = "application/json".toMediaType()
    }

    override val id = "drive"
    override val name = "Google Drive"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override val baseManifest = SkillManifest(
        description = "List, download, and upload files in Google Drive. Use this to search for files, read document content, or upload new files.",
        tools = listOf(
            ToolDefinition(
                name = "drive_list",
                description = "Search and list files in Google Drive.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "query" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Search query (e.g. \"name contains 'report'\", \"mimeType='application/pdf'\"). Leave empty to list recent files."),
                        )),
                        "max_results" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Maximum number of files to return (default: 10, max: 50)"),
                        )),
                    )),
                )),
            ),
            ToolDefinition(
                name = "drive_download",
                description = "Download or export the content of a file from Google Drive. For Google Docs/Sheets/Slides, exports as plain text. For other files, downloads the raw content.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "file_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The Google Drive file ID"),
                        )),
                        "max_length" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Maximum characters to return (default: 10000, max: 50000)"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("file_id"))),
                )),
            ),
            ToolDefinition(
                name = "drive_upload",
                description = "Upload a new file to Google Drive.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "name" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("File name (e.g. 'report.txt')"),
                        )),
                        "content" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("File content as text"),
                        )),
                        "mime_type" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("MIME type (default: 'text/plain')"),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("name"),
                        JsonPrimitive("content"),
                    )),
                )),
                requiresApproval = true,
            ),
        ),
        permissions = listOf(android.Manifest.permission.INTERNET),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return try {
            when (tool) {
                "drive_list" -> driveList(params)
                "drive_download" -> driveDownload(params)
                "drive_upload" -> driveUpload(params)
                else -> SkillResult.Error("Unknown tool: $tool")
            }
        } catch (e: Exception) {
            SkillResult.Error("Drive error: ${e.message}")
        }
    }

    private suspend fun driveList(params: JsonObject): SkillResult = withContext(Dispatchers.IO) {
        val query = params["query"]?.jsonPrimitive?.contentOrNull
        val maxResults = (params["max_results"]?.jsonPrimitive?.let {
            it.contentOrNull?.toIntOrNull()
        } ?: 10).coerceIn(1, 50)

        val token = getAccessToken()
        val urlBuilder = StringBuilder("$BASE_URL/files?fields=files(id,name,mimeType,modifiedTime,size)&pageSize=$maxResults")
        if (!query.isNullOrBlank()) {
            urlBuilder.append("&q=${java.net.URLEncoder.encode(query, "UTF-8")}")
        } else {
            urlBuilder.append("&orderBy=modifiedTime+desc")
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            return@withContext SkillResult.Error("Failed to list files (HTTP ${response.code}): $responseBody")
        }

        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(responseBody).jsonObject
        val files = parsed["files"]?.jsonArray

        if (files == null || files.isEmpty()) {
            return@withContext SkillResult.Success("No files found" +
                if (!query.isNullOrBlank()) " matching query: $query" else "")
        }

        val sb = StringBuilder()
        sb.appendLine("Found ${files.size} file(s):")
        sb.appendLine()

        for ((index, file) in files.withIndex()) {
            val name = file.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: "(unnamed)"
            val id = file.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: ""
            val mimeType = file.jsonObject["mimeType"]?.jsonPrimitive?.contentOrNull ?: ""
            val modified = file.jsonObject["modifiedTime"]?.jsonPrimitive?.contentOrNull ?: ""
            val size = file.jsonObject["size"]?.jsonPrimitive?.contentOrNull

            sb.appendLine("${index + 1}. $name")
            sb.appendLine("   ID: $id")
            sb.appendLine("   Type: $mimeType")
            if (modified.isNotBlank()) sb.appendLine("   Modified: $modified")
            if (size != null) sb.appendLine("   Size: $size bytes")
            sb.appendLine()
        }

        SkillResult.Success(sb.toString().trimEnd())
    }

    private fun validateId(id: String): String? {
        if (id.isBlank() || id.contains('/') || id.contains("..")) return null
        return id
    }

    private suspend fun driveDownload(params: JsonObject): SkillResult = withContext(Dispatchers.IO) {
        val fileId = validateId(params["file_id"]?.jsonPrimitive?.contentOrNull ?: "")
            ?: return@withContext SkillResult.Error("Missing or invalid parameter: file_id")
        val maxLength = (params["max_length"]?.jsonPrimitive?.let {
            it.contentOrNull?.toIntOrNull()
        } ?: 10000).coerceIn(100, 50000)

        val token = getAccessToken()

        // First get file metadata to check if it's a Google Docs type
        val metaRequest = Request.Builder()
            .url("$BASE_URL/files/$fileId?fields=mimeType,name")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val metaResponse = client.newCall(metaRequest).execute()
        val metaBody = metaResponse.body?.string() ?: ""

        if (!metaResponse.isSuccessful) {
            return@withContext SkillResult.Error("Failed to get file metadata (HTTP ${metaResponse.code}): $metaBody")
        }

        val metaJson = kotlinx.serialization.json.Json.parseToJsonElement(metaBody).jsonObject
        val mimeType = metaJson["mimeType"]?.jsonPrimitive?.contentOrNull ?: ""
        val fileName = metaJson["name"]?.jsonPrimitive?.contentOrNull ?: ""

        // Google Docs types need export, regular files use alt=media
        val downloadUrl = when {
            mimeType == "application/vnd.google-apps.document" ->
                "$BASE_URL/files/$fileId/export?mimeType=text/plain"
            mimeType == "application/vnd.google-apps.spreadsheet" ->
                "$BASE_URL/files/$fileId/export?mimeType=text/csv"
            mimeType == "application/vnd.google-apps.presentation" ->
                "$BASE_URL/files/$fileId/export?mimeType=text/plain"
            else ->
                "$BASE_URL/files/$fileId?alt=media"
        }

        val downloadRequest = Request.Builder()
            .url(downloadUrl)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val downloadResponse = client.newCall(downloadRequest).execute()
        val content = downloadResponse.body?.string() ?: ""

        if (!downloadResponse.isSuccessful) {
            return@withContext SkillResult.Error("Failed to download file (HTTP ${downloadResponse.code}): $content")
        }

        val truncated = if (content.length > maxLength) {
            content.take(maxLength) + "\n\n[Content truncated at $maxLength characters]"
        } else {
            content
        }

        val sb = StringBuilder()
        sb.appendLine("File: $fileName")
        sb.appendLine("Type: $mimeType")
        sb.appendLine("Size: ${content.length} characters")
        sb.appendLine()
        sb.append(truncated)

        SkillResult.Success(sb.toString().trimEnd())
    }

    private suspend fun driveUpload(params: JsonObject): SkillResult = withContext(Dispatchers.IO) {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: name")
        val content = params["content"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: content")
        val mimeType = params["mime_type"]?.jsonPrimitive?.contentOrNull ?: "text/plain"

        val token = getAccessToken()

        val metadata = """{"name":"${name.replace("\"", "\\\"")}"}"""

        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody(JSON_TYPE))
            .addPart(content.toRequestBody(mimeType.toMediaType()))
            .build()

        val request = Request.Builder()
            .url("$UPLOAD_URL/files?uploadType=multipart&fields=id,name,mimeType,size")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            return@withContext SkillResult.Error("Failed to upload file (HTTP ${response.code}): $responseBody")
        }

        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(responseBody).jsonObject
        val fileId = parsed["id"]?.jsonPrimitive?.contentOrNull ?: ""
        SkillResult.Success("File \"$name\" uploaded to Google Drive.\nFile ID: $fileId")
    }
}
