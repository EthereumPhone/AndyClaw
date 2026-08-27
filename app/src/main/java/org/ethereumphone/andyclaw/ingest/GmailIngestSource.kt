package org.ethereumphone.andyclaw.ingest

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches candidate mails and hands them to [ReservationExtractor] as bytes.
 *
 * This is the half of ingestion that touches the network, and it is deliberately the only
 * half: everything it returns is inert data, and every decision about what that data *means*
 * is made by the pure parsers. That split is what makes "zero LLM calls in the extraction
 * path" a property of the code rather than a promise.
 *
 * The OAuth substrate already exists — `GoogleAuthManager` holds `gmail.modify` and
 * `GmailSkill` already calls these endpoints. What was missing was never access; it was a
 * deterministic parser.
 *
 * Base64 is decoded with `java.util.Base64`, not `android.util.Base64`. The Android one is
 * a stub under this project's unit tests (`unitTests.isReturnDefaultValues = true`) and
 * returns an empty array, which would make every ingestion test pass against nothing.
 */
class GmailIngestSource(
    private val getAccessToken: suspend () -> String,
    private val client: OkHttpClient = defaultClient(),
) : MailSource {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Candidate messages for [query].
     *
     * Bounded on both axes — how many messages, and how big an attachment is worth pulling
     * — because this runs on a battery behind a metered connection and a mail with a 30 MB
     * PDF in it is not a boarding pass.
     */
    override suspend fun fetch(): List<MailMessage> = fetchMatching(DEFAULT_QUERY, DEFAULT_MAX_RESULTS)

    suspend fun fetchMatching(
        query: String,
        maxResults: Int,
    ): List<MailMessage> = withContext(Dispatchers.IO) {
        val token = try {
            getAccessToken()
        } catch (e: Exception) {
            Log.i(TAG, "no Google access token — skipping mail ingest: ${e.message}")
            return@withContext emptyList()
        }

        val ids = listMessageIds(token, query, maxResults.coerceIn(1, HARD_MAX_RESULTS))
        ids.mapNotNull { id -> runCatching { getMessage(token, id) }.getOrNull() }
    }

    // ── Gmail API ─────────────────────────────────────────────────────

    private fun listMessageIds(token: String, query: String, maxResults: Int): List<String> {
        val url = "$BASE_URL/messages?q=${java.net.URLEncoder.encode(query, "UTF-8")}&maxResults=$maxResults"
        val body = get(url, token) ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val messages = root["messages"] as? JsonArray ?: return emptyList()
        return messages.mapNotNull { (it as? JsonObject)?.str("id") }
    }

    private fun getMessage(token: String, id: String): MailMessage? {
        val body = get("$BASE_URL/messages/$id?format=full", token) ?: return null
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null

        val payload = root["payload"] as? JsonObject
        val headers = (payload?.get("headers") as? JsonArray).orEmpty()
        val subject = headerValue(headers, "subject")
        val from = headerValue(headers, "from")
        val received = root["internalDate"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
            ?: (root["internalDate"] as? JsonPrimitive)?.longOrNull
            ?: 0L

        val parts = mutableListOf<MailPart>()
        collectParts(token, id, payload, parts, depth = 0)

        return MailMessage(
            id = id,
            subject = subject,
            from = from,
            receivedMs = received,
            parts = parts,
        )
    }

    /**
     * Walk the MIME tree, pulling body text inline and attachments by id.
     *
     * Depth-bounded: a mail is a tree the sender chose the shape of, and a pathological one
     * would otherwise be a stack overflow on a background thread.
     */
    private fun collectParts(
        token: String,
        messageId: String,
        node: JsonObject?,
        into: MutableList<MailPart>,
        depth: Int,
    ) {
        if (node == null || depth > MAX_MIME_DEPTH || into.size >= MAX_PARTS) return

        val mime = node.str("mimeType").orEmpty().lowercase()
        val filename = node.str("filename")?.takeIf { it.isNotBlank() }
        val body = node["body"] as? JsonObject

        val inline = body?.str("data")
        if (inline != null) {
            val bytes = decodeUrlBase64(inline)
            if (bytes != null) {
                into += if (mime.startsWith("text/")) {
                    MailPart(mime, filename, text = String(bytes, Charsets.UTF_8))
                } else {
                    MailPart(mime, filename, bytes = bytes)
                }
            }
        }

        val attachmentId = body?.str("attachmentId")
        val size = body?.get("size")?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() } ?: 0L
        if (attachmentId != null && isWorthDownloading(mime, filename) && size <= MAX_ATTACHMENT_BYTES) {
            val data = get("$BASE_URL/messages/$messageId/attachments/$attachmentId", token)
                ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                ?.str("data")
            decodeUrlBase64(data)?.let { into += MailPart(mime, filename, bytes = it) }
        }

        (node["parts"] as? JsonArray)?.forEach { child ->
            collectParts(token, messageId, child as? JsonObject, into, depth + 1)
        }
    }

    /**
     * Only the three attachment types anything here can read.
     *
     * Downloading an attachment costs bandwidth and battery, and there is no parser for a
     * JPEG of a hotel lobby — a permissive filter here would be a background data cost with
     * nothing on the other end of it.
     */
    private fun isWorthDownloading(mime: String, filename: String?): Boolean {
        val name = filename?.lowercase().orEmpty()
        return mime.contains("pkpass") || name.endsWith(".pkpass") ||
            mime.startsWith("application/pdf") || name.endsWith(".pdf") ||
            mime.startsWith("text/calendar") || name.endsWith(".ics")
    }

    private fun get(url: String, token: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) {
                Log.w(TAG, "Gmail ${response.code} for $url")
                null
            } else {
                body
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Gmail request failed: ${e.message}")
        null
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun headerValue(headers: JsonArray, name: String): String? =
        headers.mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("name")?.equals(name, ignoreCase = true) == true }
            ?.str("value")

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    companion object {
        private const val TAG = "GmailIngest"
        private const val BASE_URL = "https://gmail.googleapis.com/gmail/v1/users/me"

        /**
         * What to look at.
         *
         * Reservation markup arrives in confirmation mail, so the query is the union of
         * "recent mail that says one of the words a confirmation says" and "recent mail
         * with something scannable attached". It is a filter, not a classifier — anything
         * it lets through still has to parse, and anything that does not parse produces
         * nothing.
         */
        const val DEFAULT_QUERY =
            "newer_than:60d (" +
                "subject:(booking OR reservation OR confirmation OR itinerary OR " +
                "boarding OR \"check-in\" OR ticket) " +
                "OR filename:pkpass OR filename:ics OR filename:pdf)"

        private const val DEFAULT_MAX_RESULTS = 25
        private const val HARD_MAX_RESULTS = 100
        private const val MAX_MIME_DEPTH = 8
        private const val MAX_PARTS = 40
        private const val MAX_ATTACHMENT_BYTES = 8L * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Gmail returns URL-safe base64 with the padding stripped. */
        internal fun decodeUrlBase64(data: String?): ByteArray? {
            if (data.isNullOrBlank()) return null
            return try {
                java.util.Base64.getUrlDecoder().decode(data.trim())
            } catch (e: Exception) {
                try {
                    java.util.Base64.getMimeDecoder().decode(data.trim())
                } catch (e2: Exception) {
                    null
                }
            }
        }
    }
}
