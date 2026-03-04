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

data class InlineButton(val text: String, val callbackData: String)

sealed class TelegramUpdate(val updateId: Long) {
    data class MessageUpdate(
        val id: Long,
        val chatId: Long,
        val text: String,
        val fromUsername: String?,
        val fromFirstName: String?,
    ) : TelegramUpdate(id)

    data class CallbackQueryUpdate(
        val id: Long,
        val callbackQueryId: String,
        val chatId: Long,
        val messageId: Long,
        val data: String,
        val fromUsername: String?,
    ) : TelegramUpdate(id)
}

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
                put("allowed_updates", JSONArray().put("message").put("callback_query"))
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

    /**
     * Sends a message with an inline keyboard and returns the sent message_id,
     * or null if the send failed.
     */
    suspend fun sendMessageWithInlineKeyboard(
        chatId: Long,
        text: String,
        buttons: List<InlineButton>,
    ): Long? = withContext(Dispatchers.IO) {
        val keyboard = JSONArray().put(
            JSONArray().apply {
                for (btn in buttons) {
                    put(JSONObject().apply {
                        put("text", btn.text)
                        put("callback_data", btn.callbackData)
                    })
                }
            }
        )

        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            put("parse_mode", "Markdown")
            put("reply_markup", JSONObject().put("inline_keyboard", keyboard))
        }

        val request = Request.Builder()
            .url(apiUrl("sendMessage"))
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            sendClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    if (response.code == 400 && errorBody.contains("parse")) {
                        return@withContext sendPlainMessageWithInlineKeyboard(chatId, text, buttons)
                    }
                    Log.w(TAG, "sendMessageWithInlineKeyboard failed: ${response.code} $errorBody")
                    return@withContext null
                }
                val json = JSONObject(response.body?.string() ?: return@withContext null)
                json.optJSONObject("result")?.optLong("message_id")
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendMessageWithInlineKeyboard error: ${e.message}")
            null
        }
    }

    private fun sendPlainMessageWithInlineKeyboard(
        chatId: Long,
        text: String,
        buttons: List<InlineButton>,
    ): Long? {
        val keyboard = JSONArray().put(
            JSONArray().apply {
                for (btn in buttons) {
                    put(JSONObject().apply {
                        put("text", btn.text)
                        put("callback_data", btn.callbackData)
                    })
                }
            }
        )

        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            put("reply_markup", JSONObject().put("inline_keyboard", keyboard))
        }

        val request = Request.Builder()
            .url(apiUrl("sendMessage"))
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return try {
            sendClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string() ?: return@use null)
                json.optJSONObject("result")?.optLong("message_id")
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendPlainMessageWithInlineKeyboard error: ${e.message}")
            null
        }
    }

    suspend fun answerCallbackQuery(callbackQueryId: String, text: String? = null): Unit =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("callback_query_id", callbackQueryId)
                if (text != null) put("text", text)
            }

            val request = Request.Builder()
                .url(apiUrl("answerCallbackQuery"))
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            try {
                sendClient.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "answerCallbackQuery error: ${e.message}")
            }
        }

    suspend fun editMessageText(chatId: Long, messageId: Long, text: String): Unit =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("chat_id", chatId)
                put("message_id", messageId)
                put("text", text)
                put("parse_mode", "Markdown")
            }

            val request = Request.Builder()
                .url(apiUrl("editMessageText"))
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            try {
                sendClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: ""
                        if (response.code == 400 && errorBody.contains("parse")) {
                            editMessageTextPlain(chatId, messageId, text)
                            return@withContext
                        }
                        Log.w(TAG, "editMessageText failed: ${response.code} $errorBody")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "editMessageText error: ${e.message}")
            }
        }

    private fun editMessageTextPlain(chatId: Long, messageId: Long, text: String) {
        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("message_id", messageId)
            put("text", text)
        }

        val request = Request.Builder()
            .url(apiUrl("editMessageText"))
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            sendClient.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.w(TAG, "editMessageTextPlain error: ${e.message}")
        }
    }

    private fun parseUpdates(array: JSONArray): List<TelegramUpdate> {
        val updates = mutableListOf<TelegramUpdate>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val updateId = obj.getLong("update_id")

            val message = obj.optJSONObject("message")
            if (message != null) {
                val text = message.optString("text", "").takeIf { it.isNotBlank() } ?: continue
                val chat = message.getJSONObject("chat")
                val from = message.optJSONObject("from")
                updates.add(
                    TelegramUpdate.MessageUpdate(
                        id = updateId,
                        chatId = chat.getLong("id"),
                        text = text,
                        fromUsername = from?.optString("username", null),
                        fromFirstName = from?.optString("first_name", null),
                    )
                )
                continue
            }

            val callbackQuery = obj.optJSONObject("callback_query")
            if (callbackQuery != null) {
                val cbMessage = callbackQuery.optJSONObject("message") ?: continue
                val chat = cbMessage.getJSONObject("chat")
                val from = callbackQuery.optJSONObject("from")
                updates.add(
                    TelegramUpdate.CallbackQueryUpdate(
                        id = updateId,
                        callbackQueryId = callbackQuery.getString("id"),
                        chatId = chat.getLong("id"),
                        messageId = cbMessage.getLong("message_id"),
                        data = callbackQuery.optString("data", ""),
                        fromUsername = from?.optString("username", null),
                    )
                )
            }
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
