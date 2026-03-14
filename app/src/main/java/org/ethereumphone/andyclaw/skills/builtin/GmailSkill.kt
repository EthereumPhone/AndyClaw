package org.ethereumphone.andyclaw.skills.builtin

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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

class GmailSkill(
    private val getAccessToken: suspend () -> String,
) : AndyClawSkill {

    companion object {
        private const val TAG = "GmailSkill"
        private const val BASE_URL = "https://gmail.googleapis.com/gmail/v1/users/me"
        private val JSON_TYPE = "application/json".toMediaType()
    }

    override val id = "gmail"
    override val name = "Gmail"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override val baseManifest = SkillManifest(
        description = "Read, send, and reply to emails via Gmail. Use this to check the user's inbox, read specific emails, send new emails, or reply to existing threads.",
        tools = listOf(
            ToolDefinition(
                name = "gmail_send",
                description = "Send a new email via Gmail.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "to" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Recipient email address"),
                        )),
                        "subject" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Email subject line"),
                        )),
                        "body" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Email body text (plain text)"),
                        )),
                        "cc" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("CC recipients (comma-separated, optional)"),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("to"),
                        JsonPrimitive("subject"),
                        JsonPrimitive("body"),
                    )),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "gmail_read",
                description = "Search and list emails from Gmail. Returns message IDs, subjects, senders, and snippets.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "query" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Gmail search query (e.g. 'is:unread', 'from:user@example.com', 'subject:meeting'). Defaults to 'is:inbox'."),
                        )),
                        "max_results" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Maximum number of messages to return (default: 10, max: 20)"),
                        )),
                    )),
                )),
            ),
            ToolDefinition(
                name = "gmail_get",
                description = "Get the full content of a specific email by its message ID.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "message_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The Gmail message ID to retrieve"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("message_id"))),
                )),
            ),
            ToolDefinition(
                name = "gmail_reply",
                description = "Reply to an existing email thread.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "message_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The message ID to reply to"),
                        )),
                        "body" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Reply body text (plain text)"),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("message_id"),
                        JsonPrimitive("body"),
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
                "gmail_send" -> gmailSend(params)
                "gmail_read" -> gmailRead(params)
                "gmail_get" -> gmailGet(params)
                "gmail_reply" -> gmailReply(params)
                else -> SkillResult.Error("Unknown tool: $tool")
            }
        } catch (e: Exception) {
            SkillResult.Error("Gmail error: ${e.message}")
        }
    }

    /** Strip CR/LF to prevent RFC 2822 header injection. */
    private fun sanitizeHeader(value: String): String =
        value.replace("\r", "").replace("\n", "")

    /**
     * Encode a header value using RFC 2047 if it contains non-ASCII characters.
     * This ensures emojis and accented characters are transmitted correctly.
     */
    private fun rfc2047Encode(value: String): String {
        if (value.all { it.code < 128 }) return value
        val b64 = Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        return "=?UTF-8?B?$b64?="
    }

    /** Validate an API path segment (message ID, etc.) to prevent path traversal. */
    private fun validateId(id: String): String? {
        if (id.isBlank() || id.contains('/') || id.contains("..")) return null
        return id
    }

    private suspend fun gmailSend(params: JsonObject): SkillResult = withContext(Dispatchers.IO) {
        val to = sanitizeHeader(params["to"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: to"))
        val subject = sanitizeHeader(params["subject"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: subject"))
        val body = params["body"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: body")
        val cc = params["cc"]?.jsonPrimitive?.contentOrNull?.let { sanitizeHeader(it) }

        val rfc2822 = buildString {
            append("To: $to\r\n")
            if (!cc.isNullOrBlank()) append("Cc: $cc\r\n")
            append("Subject: ${rfc2047Encode(subject)}\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("\r\n")
            append(Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.DEFAULT))
        }

        val encoded = Base64.encodeToString(
            rfc2822.toByteArray(Charsets.US_ASCII),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

        val token = getAccessToken()
        val jsonBody = """{"raw":"$encoded"}""".toRequestBody(JSON_TYPE)
        val request = Request.Builder()
            .url("$BASE_URL/messages/send")
            .addHeader("Authorization", "Bearer $token")
            .post(jsonBody)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            return@withContext SkillResult.Error("Failed to send email (HTTP ${response.code}): $responseBody")
        }

        SkillResult.Success("Email sent to $to with subject \"$subject\"")
    }

    private suspend fun gmailRead(params: JsonObject): SkillResult = withContext(Dispatchers.IO) {
        val query = params["query"]?.jsonPrimitive?.contentOrNull ?: "is:inbox"
        val maxResults = (params["max_results"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 20)

        val token = getAccessToken()
        val listUrl = "$BASE_URL/messages?q=${java.net.URLEncoder.encode(query, "UTF-8")}&maxResults=$maxResults"
        val listRequest = Request.Builder()
            .url(listUrl)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val listResponse = client.newCall(listRequest).execute()
        val listBody = listResponse.body?.string() ?: ""

        if (!listResponse.isSuccessful) {
            return@withContext SkillResult.Error("Failed to list emails (HTTP ${listResponse.code}): $listBody")
        }

        val listJson = kotlinx.serialization.json.Json.parseToJsonElement(listBody).jsonObject
        val messages = listJson["messages"]?.jsonArray ?: run {
            return@withContext SkillResult.Success("No emails found for query: $query")
        }

        val sb = StringBuilder()
        sb.appendLine("Found ${messages.size} email(s) for query: $query")
        sb.appendLine()

        for ((index, msg) in messages.withIndex()) {
            val msgId = msg.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val metaUrl = "$BASE_URL/messages/$msgId?format=metadata&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Date"
            val metaRequest = Request.Builder()
                .url(metaUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            try {
                val metaResponse = client.newCall(metaRequest).execute()
                val metaBody = metaResponse.body?.string() ?: continue
                if (!metaResponse.isSuccessful) continue

                val metaJson = kotlinx.serialization.json.Json.parseToJsonElement(metaBody).jsonObject
                val headers = metaJson["payload"]?.jsonObject?.get("headers")?.jsonArray
                val snippet = metaJson["snippet"]?.jsonPrimitive?.contentOrNull ?: ""

                var subject = ""
                var from = ""
                var date = ""
                headers?.forEach { header ->
                    val headerName = header.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val headerValue = header.jsonObject["value"]?.jsonPrimitive?.contentOrNull ?: ""
                    when (headerName.lowercase()) {
                        "subject" -> subject = headerValue
                        "from" -> from = headerValue
                        "date" -> date = headerValue
                    }
                }

                sb.appendLine("--- Email ${index + 1} [ID: $msgId] ---")
                sb.appendLine("From: $from")
                sb.appendLine("Subject: $subject")
                sb.appendLine("Date: $date")
                sb.appendLine("Preview: $snippet")
                sb.appendLine()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch metadata for message $msgId: ${e.message}")
            }
        }

        SkillResult.Success(sb.toString().trimEnd())
    }

    private suspend fun gmailGet(params: JsonObject): SkillResult = withContext(Dispatchers.IO) {
        val messageId = validateId(params["message_id"]?.jsonPrimitive?.contentOrNull ?: "")
            ?: return@withContext SkillResult.Error("Missing or invalid parameter: message_id")

        val token = getAccessToken()
        val request = Request.Builder()
            .url("$BASE_URL/messages/$messageId?format=full")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            return@withContext SkillResult.Error("Failed to get email (HTTP ${response.code}): $responseBody")
        }

        val msgJson = kotlinx.serialization.json.Json.parseToJsonElement(responseBody).jsonObject
        val headers = msgJson["payload"]?.jsonObject?.get("headers")?.jsonArray
        val bodyText = extractBodyText(msgJson["payload"]?.jsonObject)

        var subject = ""
        var from = ""
        var to = ""
        var date = ""
        var cc = ""
        headers?.forEach { header ->
            val headerName = header.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: ""
            val headerValue = header.jsonObject["value"]?.jsonPrimitive?.contentOrNull ?: ""
            when (headerName.lowercase()) {
                "subject" -> subject = headerValue
                "from" -> from = headerValue
                "to" -> to = headerValue
                "date" -> date = headerValue
                "cc" -> cc = headerValue
            }
        }

        val sb = StringBuilder()
        sb.appendLine("Message ID: $messageId")
        sb.appendLine("From: $from")
        sb.appendLine("To: $to")
        if (cc.isNotBlank()) sb.appendLine("Cc: $cc")
        sb.appendLine("Date: $date")
        sb.appendLine("Subject: $subject")
        sb.appendLine()
        sb.append(bodyText)

        SkillResult.Success(sb.toString().trimEnd())
    }

    private suspend fun gmailReply(params: JsonObject): SkillResult = withContext(Dispatchers.IO) {
        val messageId = validateId(params["message_id"]?.jsonPrimitive?.contentOrNull ?: "")
            ?: return@withContext SkillResult.Error("Missing or invalid parameter: message_id")
        val body = params["body"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: body")

        val token = getAccessToken()

        // Fetch original message for threading headers
        val origRequest = Request.Builder()
            .url("$BASE_URL/messages/$messageId?format=metadata&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=To&metadataHeaders=Message-ID")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val origResponse = client.newCall(origRequest).execute()
        val origBody = origResponse.body?.string() ?: ""

        if (!origResponse.isSuccessful) {
            return@withContext SkillResult.Error("Failed to get original message (HTTP ${origResponse.code})")
        }

        val origJson = kotlinx.serialization.json.Json.parseToJsonElement(origBody).jsonObject
        val threadId = origJson["threadId"]?.jsonPrimitive?.contentOrNull ?: ""
        val headers = origJson["payload"]?.jsonObject?.get("headers")?.jsonArray

        var origFrom = ""
        var origSubject = ""
        var origMessageId = ""

        headers?.forEach { header ->
            val headerName = header.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: ""
            val headerValue = header.jsonObject["value"]?.jsonPrimitive?.contentOrNull ?: ""
            when (headerName.lowercase()) {
                "from" -> origFrom = headerValue
                "subject" -> origSubject = headerValue
                "message-id" -> origMessageId = headerValue
            }
        }

        val safeFrom = sanitizeHeader(origFrom)
        val safeSubject = sanitizeHeader(origSubject)
        val safeMessageId = sanitizeHeader(origMessageId)
        val replySubject = if (safeSubject.startsWith("Re:", ignoreCase = true)) safeSubject else "Re: $safeSubject"

        val rfc2822 = buildString {
            append("To: $safeFrom\r\n")
            append("Subject: ${rfc2047Encode(replySubject)}\r\n")
            if (safeMessageId.isNotBlank()) {
                append("In-Reply-To: $safeMessageId\r\n")
                append("References: $safeMessageId\r\n")
            }
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("\r\n")
            append(Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.DEFAULT))
        }

        val encoded = Base64.encodeToString(
            rfc2822.toByteArray(Charsets.US_ASCII),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

        val sendBody = """{"raw":"$encoded","threadId":"$threadId"}""".toRequestBody(JSON_TYPE)
        val sendRequest = Request.Builder()
            .url("$BASE_URL/messages/send")
            .addHeader("Authorization", "Bearer $token")
            .post(sendBody)
            .build()

        val sendResponse = client.newCall(sendRequest).execute()
        val sendResponseBody = sendResponse.body?.string() ?: ""

        if (!sendResponse.isSuccessful) {
            return@withContext SkillResult.Error("Failed to send reply (HTTP ${sendResponse.code}): $sendResponseBody")
        }

        SkillResult.Success("Reply sent to $origFrom in thread $threadId")
    }

    private fun extractBodyText(payload: JsonObject?): String {
        if (payload == null) return ""

        // Check for direct body data
        val bodyData = payload["body"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
        if (!bodyData.isNullOrBlank()) {
            return try {
                String(Base64.decode(bodyData, Base64.URL_SAFE), Charsets.UTF_8)
            } catch (e: Exception) {
                bodyData
            }
        }

        // Check parts recursively
        val parts = payload["parts"]?.jsonArray ?: return ""
        for (part in parts) {
            val mimeType = part.jsonObject["mimeType"]?.jsonPrimitive?.contentOrNull ?: ""
            if (mimeType == "text/plain") {
                val data = part.jsonObject["body"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
                if (!data.isNullOrBlank()) {
                    return try {
                        String(Base64.decode(data, Base64.URL_SAFE), Charsets.UTF_8)
                    } catch (e: Exception) {
                        data
                    }
                }
            }
            // Recurse into multipart
            if (mimeType.startsWith("multipart/")) {
                val nested = extractBodyText(part.jsonObject)
                if (nested.isNotBlank()) return nested
            }
        }

        // Fall back to HTML if no plain text found
        for (part in parts) {
            val mimeType = part.jsonObject["mimeType"]?.jsonPrimitive?.contentOrNull ?: ""
            if (mimeType == "text/html") {
                val data = part.jsonObject["body"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
                if (!data.isNullOrBlank()) {
                    return try {
                        val html = String(Base64.decode(data, Base64.URL_SAFE), Charsets.UTF_8)
                        html.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()
                    } catch (e: Exception) {
                        data
                    }
                }
            }
        }

        return ""
    }
}
