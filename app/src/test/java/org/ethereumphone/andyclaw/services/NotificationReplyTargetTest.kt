package org.ethereumphone.andyclaw.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reply may fall back only to a notification that is provably the same chat. */
class NotificationReplyTargetTest {

    private val target = ReplyTarget("k1", "com.whatsapp", "g|chat", "alice")

    @Test
    fun `same package, group and shortcut is the same conversation`() {
        assertTrue(isSameConversation(target, ReplyTarget("k2", "com.whatsapp", "g|chat", "alice")))
    }

    @Test
    fun `another chat of the same app is not`() {
        assertFalse(isSameConversation(target, ReplyTarget("k2", "com.whatsapp", "g|chat", "bob")))
        assertFalse(isSameConversation(target, ReplyTarget("k2", "com.whatsapp", "g|other", "alice")))
        assertFalse(isSameConversation(target, ReplyTarget("k2", "org.telegram", "g|chat", "alice")))
    }

    @Test
    fun `without a shortcut id nothing matches`() {
        val bare = target.copy(shortcutId = null)
        assertFalse(isSameConversation(bare, ReplyTarget("k2", "com.whatsapp", "g|chat", null)))
        assertFalse(isSameConversation(bare, ReplyTarget("k2", "com.whatsapp", "g|chat", "alice")))
    }

    @Test
    fun `the target is not its own fallback`() {
        assertFalse(isSameConversation(target, target))
    }
}
