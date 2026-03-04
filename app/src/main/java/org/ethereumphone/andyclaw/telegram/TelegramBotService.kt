package org.ethereumphone.andyclaw.telegram

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ethereumphone.andyclaw.NodeApp

/**
 * Orchestrates the Telegram bot long-polling loop.
 *
 * Polls [TelegramBotClient.getUpdates] continuously, dispatches incoming
 * messages to [TelegramAgentRunner], and sends responses back via
 * [TelegramBotClient.sendMessage]. Messages for the same chat are
 * processed sequentially to avoid interleaved tool execution.
 */
class TelegramBotService(
    private val app: NodeApp,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "TelegramBotService"
        private const val RETRY_DELAY_MS = 5_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L
    }

    private val client = TelegramBotClient(token = { app.securePrefs.telegramBotToken.value })
    private val runner = TelegramAgentRunner(app, client)

    private var pollingJob: Job? = null
    private val chatMutexes = mutableMapOf<Long, Mutex>()

    val isRunning: Boolean get() = pollingJob?.isActive == true

    fun start() {
        if (isRunning) {
            Log.d(TAG, "Already running, ignoring start()")
            return
        }

        val token = app.securePrefs.telegramBotToken.value
        if (token.isBlank()) {
            Log.w(TAG, "No Telegram bot token configured, not starting")
            return
        }

        Log.i(TAG, "Starting Telegram bot polling")
        pollingJob = scope.launch { pollLoop() }
    }

    fun stop() {
        Log.i(TAG, "Stopping Telegram bot polling")
        pollingJob?.cancel()
        pollingJob = null
        runner.clearAllHistory()
        chatMutexes.clear()
    }

    private suspend fun pollLoop() {
        var offset: Long? = null
        var retryDelay = RETRY_DELAY_MS

        while (true) {
            try {
                val updates = client.getUpdates(offset)

                if (updates.isNotEmpty()) {
                    retryDelay = RETRY_DELAY_MS

                    for (update in updates) {
                        offset = update.updateId + 1
                        scope.launch { handleUpdate(update) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Poll error, retrying in ${retryDelay}ms: ${e.message}")
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun handleUpdate(update: TelegramUpdate) {
        when (update) {
            is TelegramUpdate.MessageUpdate -> handleMessageUpdate(update)
            is TelegramUpdate.CallbackQueryUpdate -> handleCallbackQueryUpdate(update)
        }
    }

    private suspend fun handleMessageUpdate(update: TelegramUpdate.MessageUpdate) {
        val chatId = update.chatId
        val text = update.text

        app.telegramChatStore.record(chatId, update.fromUsername, update.fromFirstName)

        if (text.startsWith("/start")) {
            val aiName = app.userStoryManager.getAiName() ?: "AndyClaw"
            client.sendMessage(chatId, "Hello! I'm $aiName. How can I help you?")
            return
        }

        if (text == "/clear") {
            runner.clearHistory(chatId)
            client.sendMessage(chatId, "Conversation history cleared.")
            return
        }

        val mutex = chatMutexes.getOrPut(chatId) { Mutex() }
        mutex.withLock {
            try {
                client.sendChatAction(chatId)
                val response = runner.run(chatId, text)
                if (response.isNotBlank()) {
                    client.sendMessage(chatId, response)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process message for chat $chatId", e)
                client.sendMessage(chatId, "Sorry, something went wrong processing your message.")
            }
        }
    }

    /**
     * Handles inline keyboard button presses. This must NOT acquire the per-chat
     * mutex because the agent run that sent the approval buttons is currently
     * suspended inside that mutex waiting for this callback.
     */
    private suspend fun handleCallbackQueryUpdate(update: TelegramUpdate.CallbackQueryUpdate) {
        val parts = update.data.split("::", limit = 2)
        val action = parts.getOrNull(0)
        val requestId = parts.getOrNull(1)

        if (action == null || requestId == null) {
            Log.w(TAG, "Malformed callback data: ${update.data}")
            client.answerCallbackQuery(update.callbackQueryId)
            return
        }

        val approved = action == "approve"
        runner.resolveApproval(requestId, approved)

        client.answerCallbackQuery(
            update.callbackQueryId,
            text = if (approved) "Approved" else "Declined",
        )

        val resultText = if (approved) {
            "Approved — proceeding with execution."
        } else {
            "Declined — operation cancelled."
        }
        client.editMessageText(update.chatId, update.messageId, resultText)
    }
}
