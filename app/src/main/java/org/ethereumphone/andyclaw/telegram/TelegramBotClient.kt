package org.ethereumphone.andyclaw.telegram

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TelegramUpdate(
    val updateId: Long,
    val chatId: Long,
    val text: String,
    val fromUsername: String?,
    val fromFirstName: String?,
)

class TelegramBotClient(
    private val token: () -> String,
    private val baseUrl: String = "https://api.telegram.org",
) {
    companion object {
        private const val TAG = "TelegramBotClient"
        private const val MAX_MESSAGE_LENGTH = 4096
    }

    private val longPollClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val sendClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun apiUrl(method: String): String = "$baseUrl/bot${token()}/$method"

    suspend fun getUpdates(offset: Long?, timeout: Int = 30): List<TelegramUpdate> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                if (offset != null) put("offset", offset)
                put("timeout", timeout)
                put("allowed_updates", JSONArray().put("message"))
            }

            val request = Request.Builder()
                .url(apiUrl("getUpdates"))
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            try {
                longPollClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "getUpdates failed: ${response.code}")
                        return@withContext emptyList()
                    }
                    val json = JSONObject(response.body?.string() ?: return@withContext emptyList())
                    if (!json.optBoolean("ok", false)) {
                        Log.w(TAG, "getUpdates not ok: ${json.optString("description")}")
                        return@withContext emptyList()
                    }
                    parseUpdates(json.getJSONArray("result"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "getUpdates error: ${e.message}")
                emptyList()
            }
        }

    suspend fun sendMessage(chatId: Long, text: String): Boolean =
        withContext(Dispatchers.IO) {
            val chunks = splitMessage(text)
            var allSent = true
            for (chunk in chunks) {
                if (!sendSingleMessage(chatId, chunk)) {
                    allSent = false
                }
            }
            allSent
        }

    private fun sendSingleMessage(chatId: Long, text: String): Boolean {
        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            put("parse_mode", "Markdown")
        }

        val request = Request.Builder()
            .url(apiUrl("sendMessage"))
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return try {
            sendClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // Retry without Markdown if parsing failed
                    val errorBody = response.body?.string() ?: ""
                    if (response.code == 400 && errorBody.contains("parse")) {
                        return sendPlainMessage(chatId, text)
                    }
                    Log.w(TAG, "sendMessage failed: ${response.code} $errorBody")
                    false
                } else {
                    true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendMessage error: ${e.message}")
            false
        }
    }

    private fun sendPlainMessage(chatId: Long, text: String): Boolean {
        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
        }

        val request = Request.Builder()
            .url(apiUrl("sendMessage"))
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return try {
            sendClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "sendPlainMessage error: ${e.message}")
            false
        }
    }

    suspend fun sendChatAction(chatId: Long, action: String = "typing"): Unit =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("chat_id", chatId)
                put("action", action)
            }

            val request = Request.Builder()
                .url(apiUrl("sendChatAction"))
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            try {
                sendClient.newCall(request).execute().close()
            } catch (_: Exception) {
            }
        }

    private fun parseUpdates(array: JSONArray): List<TelegramUpdate> {
        val updates = mutableListOf<TelegramUpdate>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val message = obj.optJSONObject("message") ?: continue
            val text = message.optString("text", "").takeIf { it.isNotBlank() } ?: continue
            val chat = message.getJSONObject("chat")
            val from = message.optJSONObject("from")
            updates.add(
                TelegramUpdate(
                    updateId = obj.getLong("update_id"),
                    chatId = chat.getLong("id"),
                    text = text,
                    fromUsername = from?.optString("username", null),
                    fromFirstName = from?.optString("first_name", null),
                )
            )
        }
        return updates
    }

    private fun splitMessage(text: String): List<String> {
        if (text.length <= MAX_MESSAGE_LENGTH) return listOf(text)

        val chunks = mutableListOf<String>()
        var remaining = text
        while (remaining.length > MAX_MESSAGE_LENGTH) {
            var splitAt = remaining.lastIndexOf("\n\n", MAX_MESSAGE_LENGTH)
            if (splitAt <= 0) splitAt = remaining.lastIndexOf("\n", MAX_MESSAGE_LENGTH)
            if (splitAt <= 0) splitAt = remaining.lastIndexOf(". ", MAX_MESSAGE_LENGTH)
            if (splitAt <= 0) splitAt = MAX_MESSAGE_LENGTH

            chunks.add(remaining.substring(0, splitAt).trimEnd())
            remaining = remaining.substring(splitAt).trimStart()
        }
        if (remaining.isNotBlank()) chunks.add(remaining)
        return chunks
    }
}
