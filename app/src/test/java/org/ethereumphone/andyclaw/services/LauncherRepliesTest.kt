package org.ethereumphone.andyclaw.services

import org.ethereumphone.andyclaw.frames.FrameRef
import org.ethereumphone.andyclaw.heartbeat.HeartbeatLogEntry
import org.ethereumphone.andyclaw.heartbeat.HeartbeatToolCall
import org.ethereumphone.andyclaw.ledger.LedgerAction
import org.ethereumphone.andyclaw.ledger.LedgerEntry
import org.ethereumphone.andyclaw.ledger.LedgerKind
import org.ethereumphone.andyclaw.ledger.LedgerOutcome
import org.ethereumphone.andyclaw.ledger.ReplaySession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** IPC-04, SET-19, CHAT-12: every list the launcher reads fits one binder reply. */
class LauncherRepliesTest {

    private val binderBudgetBytes = 512 * 1024

    private fun row(seq: Long, kind: LedgerKind = LedgerKind.TOOL, intent: String = "x".repeat(256), frames: List<String> = emptyList()) =
        LedgerEntry(
            id = "row-$seq", seq = seq, sessionId = "s-${seq / 10}", ts = 1_790_594_663_000 + seq,
            kind = kind, intent = intent, provenance = "USER", routeRung = if (frames.isEmpty()) null else 4,
            flowRef = null, actions = listOf(LedgerAction("agent_display_tap", true, 120)), frames = frames,
            outcome = LedgerOutcome.OK, modelIds = listOf("anthropic/claude-sonnet-5"), costUsd = null,
            inputTokens = 12_000, outputTokens = 800, durationMs = 5_321, prevHash = "a".repeat(64), hash = "b".repeat(64),
        )

    private fun recording(seq: Long) = row(seq, frames = (0 until 600).map { "session_frames/s-$seq/%06d-1790594663123.jpg".format(it) })

    /** ~300 rows with 256-char intents and three 600-frame recordings: ~984 KiB before the cap. */
    private fun heavyLedger(): List<LedgerEntry> =
        (300L downTo 1L).map { seq ->
            when {
                seq % 100 == 0L -> recording(seq)
                seq % 10 == 0L -> row(seq, LedgerKind.TURN)
                else -> row(seq)
            }
        }

    @Test
    fun `a heavy ledger list fits in one binder reply, newest rows first`() {
        val rows = heavyLedger()
        val uncapped = JSONArray(rows.map { LauncherReplies.ledgerEntry(it) }).toString()
        assertTrue("the test must be heavy enough to matter", uncapped.length * 2 > binderBudgetBytes)

        val json = LauncherReplies.ledgerEntries(rows, limit = 300)
        assertTrue("reply ${json.length * 2} bytes", json.length * 2 < binderBudgetBytes)
        val parsed = JSONArray(json)
        assertTrue(parsed.length() in 1 until 300)
        assertEquals("row-300", parsed.getJSONObject(0).getString("id"))
        // Contiguous: the rows returned are the newest ones, none skipped.
        for (i in 0 until parsed.length()) assertEquals("row-${300 - i}", parsed.getJSONObject(i).getString("id"))
    }

    @Test
    fun `list rows carry frameCount`() {
        val o = LauncherReplies.ledgerEntry(recording(100))
        assertEquals(600, o.getInt("frameCount"))
        assertEquals(600, o.getJSONArray("frames").length())
        assertEquals(0, LauncherReplies.ledgerEntry(row(1)).getInt("frameCount"))
        // Unknown cost stays an explicit null.
        assertTrue(o.isNull("costUsd"))
    }

    @Test
    fun `a long run's replay keeps its newest steps, in order, and says it was cut`() {
        val entries = (1L..400L).map { row(it) } + row(401, LedgerKind.TURN)
        val frames = (0 until 600).map { FrameRef("s", it, 1_790_594_663_000 + it, "s/%06d.jpg".format(it), 40_000) }
        val replay = ReplaySession("s", "go", 0, 1, entries, frames, emptyList())

        val json = LauncherReplies.ledgerSession(replay, maxRows = 400).toString()
        assertTrue(json.length * 2 < binderBudgetBytes)
        val o = JSONObject(json)
        assertTrue(o.getBoolean("truncated"))
        assertEquals(600, o.getJSONArray("frames").length())
        val shown = o.getJSONArray("entries")
        assertTrue(shown.length() in 1 until 400)
        assertEquals("row-401", shown.getJSONObject(shown.length() - 1).getString("id"))
        for (i in 1 until shown.length()) {
            assertTrue(shown.getJSONObject(i - 1).getLong("seq") < shown.getJSONObject(i).getLong("seq"))
        }
    }

    @Test
    fun `a short run's replay comes back whole`() {
        val entries = (1L..5L).map { row(it, intent = "short") }
        val o = LauncherReplies.ledgerSession(ReplaySession("s", "go", 0, 1, entries, emptyList(), listOf("gone.jpg")), maxRows = 400)
        assertFalse(o.getBoolean("truncated"))
        assertEquals(5, o.getJSONArray("entries").length())
        assertEquals("gone.jpg", o.getJSONArray("missingFrames").getString(0))
    }

    @Test
    fun `a busy heartbeat log fits too, newest first`() {
        val entries = (100 downTo 1).map { i ->
            HeartbeatLogEntry(
                timestampMs = i.toLong(), outcome = "success", prompt = "p".repeat(200), responseText = "r".repeat(1000),
                toolCalls = (1..10).map { HeartbeatToolCall("tool$it", "\"quoted\" ".repeat(55)) }, durationMs = 10,
            )
        }
        val json = LauncherReplies.heartbeatLogs(entries)
        assertTrue(json.length * 2 < binderBudgetBytes)
        val parsed = JSONArray(json)
        assertTrue(parsed.length() in 1 until 100)
        assertEquals(100L, parsed.getJSONObject(0).getLong("timestampMs"))
    }

    @Test
    fun `an empty list is an empty array`() {
        assertEquals("[]", LauncherReplies.heartbeatLogs(emptyList()))
        assertEquals("[]", LauncherReplies.ledgerEntries(emptyList(), 100))
    }

    @Test
    fun `restored messages carry only what was said`() {
        val rows = listOf(
            SessionMessagesCap.Row("user", "hi", 1),
            SessionMessagesCap.Row("context_summary", "Summary of earlier conversation", 2),
            SessionMessagesCap.Row("assistant", "yo", 3),
            SessionMessagesCap.Row("tool", "{}", 4),
            SessionMessagesCap.Row("system", "sys", 5),
        )
        val out = SessionMessagesCap.capForLauncher(rows)
        assertEquals(listOf("user", "assistant"), out.map { it.role })
        assertTrue(out.all { it.omittedBefore == 0 })
    }
}
