package org.ethereumphone.andyclaw.skills.builtin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import java.util.concurrent.TimeUnit

class SheetsSkill(
    private val getAccessToken: suspend () -> String,
) : AndyClawSkill {

    companion object {
        private const val BASE_URL = "https://sheets.googleapis.com/v4/spreadsheets"
        private val JSON_TYPE = "application/json".toMediaType()
    }

    override val id = "sheets"
    override val name = "Google Sheets"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override val baseManifest = SkillManifest(
        description = "Read and append data to Google Sheets spreadsheets. Use this to read spreadsheet data or add new rows.",
        tools = listOf(
            ToolDefinition(
                name = "sheets_read",
                description = "Read data from a Google Sheets spreadsheet.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "spreadsheet_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The spreadsheet ID (from the URL)"),
                        )),
                        "range" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The A1 notation range to read (e.g. 'Sheet1!A1:D10', 'A1:Z100')"),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("spreadsheet_id"),
                        JsonPrimitive("range"),
                    )),
                )),
            ),
            ToolDefinition(
                name = "sheets_append",
                description = "Append rows of data to a Google Sheets spreadsheet.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "spreadsheet_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The spreadsheet ID (from the URL)"),
                        )),
                        "range" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The A1 notation range to append to (e.g. 'Sheet1!A1')"),
                        )),
                        "values" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("JSON array of arrays representing rows to append (e.g. '[[\"Name\",\"Age\"],[\"Alice\",\"30\"]]')"),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("spreadsheet_id"),
                        JsonPrimitive("range"),
                        JsonPrimitive("values"),
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
                "sheets_read" -> sheetsRead(params)
                "sheets_append" -> sheetsAppend(params)
                else -> SkillResult.Error("Unknown tool: $tool")
            }
        } catch (e: Exception) {
            SkillResult.Error("Sheets error: ${e.message}")
        }
    }

    private suspend fun sheetsRead(params: JsonObject): SkillResult = withContext(Dispatchers.IO) {
        val spreadsheetId = params["spreadsheet_id"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: spreadsheet_id")
        val range = params["range"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: range")

        val token = getAccessToken()
        val encodedRange = java.net.URLEncoder.encode(range, "UTF-8")
        val url = "$BASE_URL/$spreadsheetId/values/$encodedRange"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            return@withContext SkillResult.Error("Failed to read spreadsheet (HTTP ${response.code}): $responseBody")
        }

        val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .parseToJsonElement(responseBody).jsonObject
        val actualRange = parsed["range"]?.jsonPrimitive?.contentOrNull ?: range
        val values = parsed["values"]?.jsonArray

        if (values == null || values.isEmpty()) {
            return@withContext SkillResult.Success("No data found in range $actualRange")
        }

        val sb = StringBuilder()
        sb.appendLine("Spreadsheet data from range $actualRange (${values.size} rows):")
        sb.appendLine()

        for ((rowIdx, row) in values.withIndex()) {
            val cells = row.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            sb.appendLine("Row ${rowIdx + 1}: ${cells.joinToString(" | ")}")
        }

        SkillResult.Success(sb.toString().trimEnd())
    }

    private suspend fun sheetsAppend(params: JsonObject): SkillResult = withContext(Dispatchers.IO) {
        val spreadsheetId = params["spreadsheet_id"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: spreadsheet_id")
        val range = params["range"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: range")
        val valuesStr = params["values"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: values")

        val token = getAccessToken()
        val encodedRange = java.net.URLEncoder.encode(range, "UTF-8")
        val url = "$BASE_URL/$spreadsheetId/values/$encodedRange:append?valueInputOption=USER_ENTERED"

        val body = """{"values":$valuesStr}""".toRequestBody(JSON_TYPE)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            return@withContext SkillResult.Error("Failed to append to spreadsheet (HTTP ${response.code}): $responseBody")
        }

        val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .parseToJsonElement(responseBody).jsonObject
        val updatedRange = parsed["updates"]?.jsonObject?.get("updatedRange")?.jsonPrimitive?.contentOrNull
        val updatedRows = parsed["updates"]?.jsonObject?.get("updatedRows")?.jsonPrimitive?.contentOrNull

        val sb = StringBuilder()
        sb.append("Data appended to spreadsheet")
        if (updatedRows != null) sb.append(" ($updatedRows row(s))")
        sb.appendLine(".")
        sb.appendLine("Range: ${updatedRange ?: range}")

        SkillResult.Success(sb.toString().trimEnd())
    }
}
