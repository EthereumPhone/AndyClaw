package org.ethereumphone.andyclaw.heartbeat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTriggerPolicyTest {

    private var now = 1_000_000L
    private val policy = NotificationTriggerPolicy(minGapMs = { 5 * 60_000L }, clock = { now })

    private fun posted(
        key: String = "k1",
        pkg: String = "com.chat",
        ongoing: Boolean = false,
        summary: Boolean = false,
        onlyAlertOnce: Boolean = false,
        category: String? = null,
        acted: Boolean = false,
    ) = NotificationTriggerPolicy.Posted(key, pkg, ongoing, summary, onlyAlertOnce, category, acted)

    @Test
    fun `a new alerting notification wakes the agent`() {
        assertTrue(policy.shouldTrigger(posted()))
    }

    @Test
    fun `ongoing, summary, progress and media notifications do not`() {
        assertFalse(policy.shouldTrigger(posted(ongoing = true)))
        assertFalse(policy.shouldTrigger(posted(key = "s", summary = true)))
        assertFalse(policy.shouldTrigger(posted(key = "p", category = android.app.Notification.CATEGORY_PROGRESS)))
        assertFalse(policy.shouldTrigger(posted(key = "t", category = android.app.Notification.CATEGORY_TRANSPORT)))
    }

    @Test
    fun `the agent's own echo does not wake it`() {
        assertFalse(policy.shouldTrigger(posted(pkg = "org.ethereumhpone.messenger", acted = true)))
    }

    @Test
    fun `a silent update is not news, a new message in the same chat is`() {
        assertTrue(policy.shouldTrigger(posted()))
        now += 10 * 60_000L
        assertFalse(policy.shouldTrigger(posted(onlyAlertOnce = true)))
        assertTrue(policy.shouldTrigger(posted()))
    }

    @Test
    fun `no sooner than the minimum gap`() {
        assertTrue(policy.shouldTrigger(posted(key = "a")))
        now += 60_000L
        assertFalse(policy.shouldTrigger(posted(key = "b")))
        now += 5 * 60_000L
        assertTrue(policy.shouldTrigger(posted(key = "c")))
    }
}
