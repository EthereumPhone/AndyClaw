package org.ethereumphone.andyclaw.ledger

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.ethereumphone.andyclaw.ledger.db.LedgerDao
import org.ethereumphone.andyclaw.ledger.db.entity.LedgerEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The overflow count survives a failed overflow-row append instead of vanishing with it. */
class LedgerRecorderOverflowTest {

    private class GatedDao : LedgerDao {
        val rows = mutableListOf<LedgerEntryEntity>()
        val gate = CompletableDeferred<Unit>()
        var failOverflowRows = 0

        override suspend fun insert(entry: LedgerEntryEntity) {
            gate.await()
            if (entry.intent == "ledger overflow" && failOverflowRows > 0) {
                failOverflowRows--
                error("disk full")
            }
            rows += entry
        }

        override suspend fun getAll(): List<LedgerEntryEntity> = rows.sortedBy { it.seq }
        override suspend fun getRecent(limit: Int) = rows.sortedByDescending { it.seq }.take(limit)
        override fun observeRecent(limit: Int): Flow<List<LedgerEntryEntity>> = flowOf(rows.toList())
        override suspend fun getBySession(sessionId: String) = rows.filter { it.sessionId == sessionId }
        override suspend fun getLast() = rows.maxByOrNull { it.seq }
        override suspend fun count() = rows.size
        override suspend fun deleteThroughSeq(seq: Long) { rows.removeAll { it.seq <= seq } }
    }

    private fun draft(intent: String) = LedgerDraft(
        sessionId = "s",
        kind = LedgerKind.TOOL,
        intent = intent,
        provenance = "USER",
        outcome = LedgerOutcome.OK,
    )

    @Test
    fun `a failed overflow row is retried with the next write`() = runTest {
        val dao = GatedDao().apply { failOverflowRows = 1 }
        val recorder = LedgerRecorder(backgroundScope, LedgerRepository(dao), bufferSize = 1)

        recorder.record(draft("a"))
        testScheduler.runCurrent()          // the writer takes "a" and blocks on the gate
        recorder.record(draft("b"))         // fills the one-slot buffer
        recorder.record(draft("c"))         // dropped
        dao.gate.complete(Unit)
        assertTrue(recorder.drain())        // "b" written; its overflow row fails

        recorder.record(draft("d"))
        assertTrue(recorder.drain())

        val intents = dao.rows.sortedBy { it.seq }.map { it.intent }
        assertEquals(listOf("a", "b", "ledger overflow", "d"), intents)
        val overflow = dao.rows.single { it.intent == "ledger overflow" }
        assertTrue(overflow.actionsJson, overflow.actionsJson.contains("1 row(s) were dropped"))
    }
}
