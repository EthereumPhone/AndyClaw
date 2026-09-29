package org.ethereumphone.andyclaw.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramOwnerTest {

    @Test
    fun `the verified chat is the owner`() {
        assertTrue(TelegramOwner.isOwner(verifiedOwnerChatId = 42L, chatId = 42L))
    }

    @Test
    fun `any other chat is not`() {
        assertFalse(TelegramOwner.isOwner(verifiedOwnerChatId = 42L, chatId = 43L))
    }

    @Test
    fun `with no verified owner nobody is the owner, not even chat 0`() {
        assertFalse(TelegramOwner.isOwner(verifiedOwnerChatId = 0L, chatId = 42L))
        assertFalse(TelegramOwner.isOwner(verifiedOwnerChatId = 0L, chatId = 0L))
    }
}
