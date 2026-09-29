package org.ethereumphone.andyclaw.services

import org.ethereumphone.andyclaw.services.SessionMessagesCap.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMessagesCapTest {

    private fun rows(n: Int, len: Int = 10, role: String = "user") =
        (0 until n).map { Row(role, "x".repeat(len), it.toLong()) }

    @Test
    fun `a small session comes back whole and unmarked`() {
        val out = SessionMessagesCap.cap(rows(5))
        assertEquals(5, out.size)
        assertTrue(out.none { it.truncated })
        assertTrue(out.all { it.omittedBefore == 0 })
        assertEquals((0L until 5L).toList(), out.map { it.timestamp })
    }

    @Test
    fun `only the newest rows are kept, in order, and the first says how many were left out`() {
        val out = SessionMessagesCap.cap(rows(10), maxRows = 3)
        assertEquals(listOf(7L, 8L, 9L), out.map { it.timestamp })
        assertEquals(7, out.first().omittedBefore)
        assertEquals(0, out.last().omittedBefore)
    }

    @Test
    fun `a long row is cut to its cap and marked, a tool row to the smaller cap`() {
        val input = listOf(
            Row("tool", "t".repeat(5_000), 1),
            Row("assistant", "a".repeat(50_000), 2),
        )
        val out = SessionMessagesCap.cap(input)
        assertEquals(SessionMessagesCap.MAX_CHARS_PER_TOOL_ROW, out[0].content.length)
        assertTrue(out[0].truncated)
        assertEquals(SessionMessagesCap.MAX_CHARS_PER_ROW, out[1].content.length)
        assertTrue(out[1].truncated)
    }

    @Test
    fun `the total budget stops at the first row that does not fit, leaving no holes`() {
        val input = listOf(
            Row("user", "a".repeat(10), 1),
            Row("tool", "b".repeat(100), 2), // does not fit
            Row("user", "c".repeat(10), 3),
            Row("assistant", "d".repeat(10), 4),
        )
        val out = SessionMessagesCap.cap(input, maxTotalChars = 50)
        assertEquals(listOf(3L, 4L), out.map { it.timestamp })
        assertEquals(2, out.first().omittedBefore)
    }

    @Test
    fun `a huge session stays inside the binder budget`() {
        val out = SessionMessagesCap.cap(rows(5_000, len = 40_000, role = "tool") + rows(5_000, len = 40_000))
        val total = out.sumOf { it.content.length }
        assertTrue(total <= SessionMessagesCap.MAX_TOTAL_CHARS)
        assertTrue(out.size <= SessionMessagesCap.MAX_ROWS)
        assertFalse(out.isEmpty())
    }

    @Test
    fun `an empty session is empty`() {
        assertTrue(SessionMessagesCap.cap(emptyList()).isEmpty())
    }
}
