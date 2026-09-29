package org.ethereumphone.andyclaw.telegram

/**
 * Who the owner of the Telegram bot is.
 *
 * Only the chat id verified at setup (`SecurePrefs.telegramOwnerChatId`, written by
 * `completeTelegramSetup` and the onboarding dialog). It used to be
 * [TelegramChatStore]'s first recorded chat — whoever reached the store first, which after a
 * re-setup or a lost `telegram_chats.json` could be a stranger, who then got the owner's memory
 * and story, no trigger budget, and the Approve buttons for their own requests.
 *
 * No verified owner (0, the pref's default) means nobody is the owner. That is the safe side: the
 * real owner of a bot set up before the verified id existed is treated as any other chat until
 * they complete setup again.
 */
object TelegramOwner {
    fun isOwner(verifiedOwnerChatId: Long, chatId: Long): Boolean =
        verifiedOwnerChatId != 0L && verifiedOwnerChatId == chatId
}
