package org.ethereumphone.andyclaw.services

import android.os.DeadObjectException
import android.os.RemoteException
import org.ethereumphone.andyclaw.autopilot.AutopilotEvent.Kind
import org.ethereumphone.andyclaw.ui.chat.ToolResultFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** IPC-07: the preview survives a busy launcher, slows down after a run, and tool rows stay small. */
class FrameStreamPolicyTest {

    @Test
    fun `only a dead launcher ends the frame stream`() {
        assertTrue(FrameStreamPolicy.launcherGone(DeadObjectException(), binderAlive = true))
        assertTrue(FrameStreamPolicy.launcherGone(RemoteException(), binderAlive = false))
        // A TransactionTooLargeException behind a big tool result, say: this frame is lost, not the stream.
        assertFalse(FrameStreamPolicy.launcherGone(RemoteException(), binderAlive = true))
    }

    @Test
    fun `the stream runs fast during a run and slows once it is over`() {
        assertEquals(FrameStreamPolicy.RUN_INTERVAL_MS, FrameStreamPolicy.intervalAfter(Kind.STARTED))
        assertEquals(FrameStreamPolicy.IDLE_INTERVAL_MS, FrameStreamPolicy.intervalAfter(Kind.DONE))
        assertEquals(FrameStreamPolicy.IDLE_INTERVAL_MS, FrameStreamPolicy.intervalAfter(Kind.FAILED))
        assertNull(FrameStreamPolicy.intervalAfter(Kind.ACTING))
        assertNull(FrameStreamPolicy.intervalAfter(Kind.SETTLED))
        assertTrue(FrameStreamPolicy.RUN_INTERVAL_MS < FrameStreamPolicy.IDLE_INTERVAL_MS)
    }

    @Test
    fun `a huge tool result reaches the launcher cut to 8 K with how much was left out`() {
        val tree = buildString { while (length < 200_000) append("node id=com.app:id/row text=\"row\"\n") }
        val formatted = ToolResultFormatter.formatForLauncher("agent_display_look", tree)
        assertTrue(formatted.detail.length <= 8_192)
        val left = Regex("""… \((\d+) more\)$""").find(formatted.detail)!!.groupValues[1].toInt()
        assertEquals(tree.trimEnd().length, formatted.detail.length - "… ($left more)".length + left)
    }

    @Test
    fun `a small detail is untouched`() {
        assertEquals("ok", ToolResultFormatter.capDetail("ok"))
        val exact = "x".repeat(ToolResultFormatter.LAUNCHER_DETAIL_MAX_CHARS)
        assertEquals(exact, ToolResultFormatter.capDetail(exact))
        val over = "x".repeat(ToolResultFormatter.LAUNCHER_DETAIL_MAX_CHARS + 1)
        val cut = ToolResultFormatter.capDetail(over)
        assertTrue(cut.length <= ToolResultFormatter.LAUNCHER_DETAIL_MAX_CHARS)
        assertTrue(cut.endsWith(" more)"))
    }
}
