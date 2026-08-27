package org.ethereumphone.andyclaw.ledger

import org.ethereumphone.andyclaw.ledger.db.entity.LedgerEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The property the ledger exists to have: you cannot change it without it showing.
 */
class LedgerChainTest {

    private fun row(
        seq: Long,
        prevHash: String,
        intent: String = "send anna a message",
        outcome: String = "OK",
        cost: Double? = 0.0012,
    ): LedgerEntryEntity {
        val e = LedgerEntryEntity(
            id = "id-$seq",
            seq = seq,
            sessionId = "session-1",
            ts = 1_700_000_000_000L + seq,
            kind = "TOOL",
            intent = intent,
            provenanceClass = "USER",
            routeRung = 0,
            flowRef = null,
            actionsJson = LedgerChain.encodeActions(listOf(LedgerAction("send_sms", true, 12))),
            framesJson = LedgerChain.encodeStrings(emptyList()),
            outcome = outcome,
            modelIdsJson = LedgerChain.encodeStrings(listOf("anthropic/claude-sonnet-4-6")),
            costUsd = cost,
            inputTokens = 100,
            outputTokens = 20,
            durationMs = 12,
            prevHash = prevHash,
            hash = "",
        )
        return e.copy(hash = LedgerChain.hashOf(e))
    }

    private fun chain(n: Int): List<LedgerEntryEntity> {
        val out = mutableListOf<LedgerEntryEntity>()
        var prev = LedgerChain.GENESIS
        for (i in 1..n) {
            val r = row(i.toLong(), prev)
            out += r
            prev = r.hash
        }
        return out
    }

    @Test
    fun `a well-formed chain verifies`() {
        val result = LedgerChain.verify(chain(5))
        assertTrue(result.reason ?: "", result.ok)
        assertEquals(4, result.checkedLinks)
        assertEquals(1L, result.firstSeq)
        assertEquals(5L, result.lastSeq)
    }

    @Test
    fun `editing a field breaks the row that holds it`() {
        val rows = chain(5).toMutableList()
        // The most tempting edit there is: a blocked call rewritten as one that ran.
        rows[2] = rows[2].copy(outcome = "OK", intent = "something else")

        val result = LedgerChain.verify(rows)
        assertFalse(result.ok)
        assertEquals(3L, result.brokenAtSeq)
    }

    @Test
    fun `editing a field and re-hashing the row breaks the link to the next one`() {
        val rows = chain(5).toMutableList()
        val tampered = rows[2].copy(costUsd = 0.0)
        rows[2] = tampered.copy(hash = LedgerChain.hashOf(tampered))

        // Row 3 now hashes correctly on its own — and row 4 still names the old hash.
        val result = LedgerChain.verify(rows)
        assertFalse(result.ok)
        assertEquals(4L, result.brokenAtSeq)
    }

    @Test
    fun `removing a row from the middle is detected`() {
        val rows = chain(5).toMutableList()
        rows.removeAt(2)
        val result = LedgerChain.verify(rows)
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("removed from the middle"))
    }

    @Test
    fun `dropping the oldest rows is not tampering`() {
        // Retention drops a prefix. What is left cannot prove its own first link, and
        // reporting that as tampering would make the check useless.
        val rows = chain(5).drop(2)
        val result = LedgerChain.verify(rows)
        assertTrue(result.ok)
        assertEquals(2, result.checkedLinks)
        assertEquals(3L, result.firstSeq)
    }

    @Test
    fun `the empty chain verifies`() {
        assertTrue(LedgerChain.verify(emptyList()).ok)
    }

    @Test
    fun `field boundaries cannot be moved without changing the hash`() {
        // The reason the canonical form is length-prefixed rather than delimited. Under a
        // bare separator these two rows would encode identically.
        val a = row(1, LedgerChain.GENESIS, intent = "ab", outcome = "OK")
        val b = row(1, LedgerChain.GENESIS, intent = "a", outcome = "bOK")
        assertNotEquals(a.hash, b.hash)
    }

    @Test
    fun `a null cost and a zero cost are different rows`() {
        val unknown = row(1, LedgerChain.GENESIS, cost = null)
        val free = row(1, LedgerChain.GENESIS, cost = 0.0)
        assertNotEquals(unknown.hash, free.hash)
    }

    @Test
    fun `cost hashing does not depend on how the double was arrived at`() {
        // `0.1 + 0.2` is not `0.3`, and `toString` shows it. A chain that breaks because a
        // cost was computed rather than parsed would be unreproducible.
        val computed = row(1, LedgerChain.GENESIS, cost = 0.1 + 0.2)
        val literal = row(1, LedgerChain.GENESIS, cost = 0.3)
        assertEquals(literal.hash, computed.hash)
    }

    @Test
    fun `actions survive the round trip`() {
        val actions = listOf(
            LedgerAction("send_sms", ok = true, durationMs = 12),
            LedgerAction("agent_send_transaction", ok = false, durationMs = 340, note = "[Provenance] blocked"),
        )
        assertEquals(actions, LedgerChain.decodeActions(LedgerChain.encodeActions(actions)))
    }

    @Test
    fun `unreadable json decodes to nothing rather than throwing`() {
        assertEquals(emptyList<LedgerAction>(), LedgerChain.decodeActions("{not json"))
        assertEquals(emptyList<String>(), LedgerChain.decodeStrings("{not json"))
    }
}
