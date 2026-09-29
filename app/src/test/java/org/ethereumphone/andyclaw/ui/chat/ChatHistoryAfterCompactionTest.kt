package org.ethereumphone.andyclaw.ui.chat

import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.llm.MessageContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A context summary is written after the messages compaction kept — and, for auto-compaction,
 * after the user message that triggered it. Rebuilding the model's history from the summary
 * onward dropped both, on the next turn and after every reload.
 */
class ChatHistoryAfterCompactionTest {

    private var n = 0
    private fun ui(role: String, content: String, kept: Int = 0, transient: Boolean = false) =
        ChatUiMessage(id = "${n++}", role = role, content = content, keptBefore = kept, transient = transient)

    private fun text(m: Message): String = when (val c = m.content) {
        is MessageContent.Text -> c.value
        is MessageContent.Blocks -> c.blocks.filterIsInstance<ContentBlock.TextBlock>().joinToString { it.text }
    }

    @Test
    fun `auto-compaction keeps its tail and the triggering request`() {
        val msgs = listOf(
            ui("user", "old q"), ui("assistant", "old a"),
            ui("user", "recent q"), ui("tool", "tool out"), ui("assistant", "recent a"),
            ui("user", "the request"),
            ui("context_summary", "SUMMARY", kept = 3),
            ui("assistant", "the answer"),
        )
        val history = ChatViewModel.buildLlmHistory(msgs)
        assertTrue(text(history[0]).startsWith("<context_summary>"))
        assertTrue(text(history[0]).contains("SUMMARY"))
        assertEquals(listOf("recent q", "recent a", "the request", "the answer"), history.drop(1).map(::text))
        assertEquals(listOf("user", "user", "assistant", "user", "assistant"), history.map { it.role })
    }

    @Test
    fun `the kept tail can reach back past an older summary`() {
        val msgs = listOf(
            ui("user", "a"), ui("assistant", "b"),
            ui("context_summary", "S1", kept = 2),
            ui("user", "c"), ui("assistant", "d"),
            ui("context_summary", "S2", kept = 3),
            ui("user", "e"),
        )
        val history = ChatViewModel.buildLlmHistory(msgs)
        assertTrue(text(history[0]).contains("S2"))
        assertEquals(listOf("b", "c", "d", "e"), history.drop(1).map(::text))
    }

    @Test
    fun `a summary written before the count existed keeps nothing, as before`() {
        val msgs = listOf(ui("user", "a"), ui("assistant", "b"), ui("context_summary", "S"), ui("user", "c"))
        assertEquals(listOf("c"), ChatViewModel.buildLlmHistory(msgs).drop(1).map(::text))
    }

    @Test
    fun `slash command echoes and system notes never reach the model`() {
        val msgs = listOf(
            ui("user", "q"), ui("assistant", "a"),
            ui("user", "/compact", transient = true), ui("system", "note"),
        )
        assertEquals(listOf("q", "a"), ChatViewModel.buildLlmHistory(msgs).map(::text))
    }

    @Test
    fun `kept count round-trips through the row`() {
        assertEquals(4, ChatViewModel.parseKeptBefore("${ChatViewModel.SUMMARY_KEPT_PREFIX}4"))
        assertEquals(0, ChatViewModel.parseKeptBefore(null))
        assertEquals(0, ChatViewModel.parseKeptBefore("toolu_123"))
        assertEquals(0, ChatViewModel.parseKeptBefore("kept:-3"))
    }
}
