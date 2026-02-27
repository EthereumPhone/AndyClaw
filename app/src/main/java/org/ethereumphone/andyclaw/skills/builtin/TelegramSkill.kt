package org.ethereumphone.andyclaw.skills.builtin

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.telegram.TelegramBotClient
import org.ethereumphone.andyclaw.telegram.TelegramChatStore

class TelegramSkill(
    private val chatStore: TelegramChatStore,
    private val botToken: () -> String,
    private val botEnabled: () -> Boolean,
) : AndyClawSkill {

    companion object {
        private const val TAG = "TelegramSkill"
    }

    override val id = "telegram"
    override val name = "Telegram"

    override val baseManifest = SkillManifest(
        description = "Send messages to the user via Telegram bot. Use this to proactively reach the user on Telegram, e.g. for reminders, alerts, or follow-ups.",
        tools = listOf(
            ToolDefinition(
                name = "send_telegram_message",
                description = "Send a message to a Telegram chat. If no chat_id is provided, sends to the most recent chat (the bot owner). Use this for proactive messages like reminders, alerts, or scheduled follow-ups.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "text" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The message text to send"),
                        )),
                        "chat_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Target Telegram chat ID. Omit to send to the default (most recent) chat."),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("text"))),
                )),
            ),
            ToolDefinition(
                name = "list_telegram_chats",
                description = "List all known Telegram chats that have messaged this bot. Returns chat IDs with associated usernames.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(emptyMap()),
                )),
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    private val client by lazy {
        TelegramBotClient(token = botToken)
    }

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        if (!botEnabled()) {
            return SkillResult.Error("Telegram bot is not enabled. The user must enable it in Settings.")
        }
        if (botToken().isBlank()) {
            return SkillResult.Error("No Telegram bot token configured.")
        }

        return when (tool) {
            "send_telegram_message" -> sendMessage(params)
            "list_telegram_chats" -> listChats()
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private suspend fun sendMessage(params: JsonObject): SkillResult {
        val text = params["text"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: text")

        val chatId = params["chat_id"]?.jsonPrimitive?.longOrNull
            ?: chatStore.getOwnerChatId()
            ?: return SkillResult.Error(
                "No chat_id provided and no known Telegram chats. " +
                "The user must message the bot on Telegram first before you can send proactive messages."
            )

        return try {
            val success = client.sendMessage(chatId, text)
            if (success) {
                Log.i(TAG, "Sent Telegram message to chat $chatId")
                SkillResult.Success(buildJsonObject {
                    put("sent", true)
                    put("chat_id", chatId)
                }.toString())
            } else {
                SkillResult.Error("Failed to send Telegram message to chat $chatId")
            }
        } catch (e: Exception) {
            SkillResult.Error("Failed to send Telegram message: ${e.message}")
        }
    }

    private fun listChats(): SkillResult {
        val chats = chatStore.getAll()
        if (chats.isEmpty()) {
            return SkillResult.Success("No known Telegram chats. No one has messaged the bot yet.")
        }

        val result = buildJsonArray {
            for (chat in chats) {
                add(buildJsonObject {
                    put("chat_id", chat.chatId)
                    if (chat.username != null) put("username", chat.username)
                    if (chat.firstName != null) put("first_name", chat.firstName)
                })
            }
        }
        return SkillResult.Success(result.toString())
    }
}
