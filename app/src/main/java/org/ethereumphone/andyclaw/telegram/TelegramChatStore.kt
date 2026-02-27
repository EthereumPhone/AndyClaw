package org.ethereumphone.andyclaw.telegram

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Persists known Telegram chat IDs and associated metadata (username, first name)
 * so that any AgentLoop (heartbeat, chat, reminder) can proactively send messages.
 */
class TelegramChatStore(context: Context) {

    companion object {
        private const val TAG = "TelegramChatStore"
        private const val FILENAME = "telegram_chats.json"
    }

    data class ChatInfo(
        val chatId: Long,
        val username: String?,
        val firstName: String?,
    )

    private val file = File(context.filesDir, FILENAME)
    private val chats = mutableMapOf<Long, ChatInfo>()

    init {
        load()
    }

    @Synchronized
    fun record(chatId: Long, username: String?, firstName: String?) {
        val info = ChatInfo(chatId, username, firstName)
        chats[chatId] = info
        save()
    }

    @Synchronized
    fun getAll(): List<ChatInfo> = chats.values.toList()

    @Synchronized
    fun get(chatId: Long): ChatInfo? = chats[chatId]

    @Synchronized
    fun getOwnerChatId(): Long? {
        return chats.values.firstOrNull()?.chatId
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = root.getJSONObject(key)
                val chatId = obj.getLong("chatId")
                chats[chatId] = ChatInfo(
                    chatId = chatId,
                    username = obj.optString("username", null),
                    firstName = obj.optString("firstName", null),
                )
            }
            Log.i(TAG, "Loaded ${chats.size} known Telegram chat(s)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load chat store: ${e.message}")
        }
    }

    private fun save() {
        try {
            val root = JSONObject()
            for ((id, info) in chats) {
                val obj = JSONObject().apply {
                    put("chatId", info.chatId)
                    if (info.username != null) put("username", info.username)
                    if (info.firstName != null) put("firstName", info.firstName)
                }
                root.put(id.toString(), obj)
            }
            file.writeText(root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save chat store: ${e.message}")
        }
    }
}
