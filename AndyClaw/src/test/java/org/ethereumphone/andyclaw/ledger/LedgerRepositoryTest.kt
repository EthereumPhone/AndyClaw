package org.ethereumphone.andyclaw.ledger

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.ethereumphone.andyclaw.ledger.db.LedgerDao
import org.ethereumphone.andyclaw.ledger.db.entity.LedgerEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The writer. Room is not available in a plain unit test, so the DAO is faked — which is
 * the reason [LedgerRepository] takes one rather than a database.
 */
class LedgerRepositoryTest {

    /** An in-memory stand-in that behaves the way the real table is declared to. */
    private class FakeDao : LedgerDao {
        val rows = mutableListOf<LedgerEntryEntity>()

        override suspend fun insert(entry: LedgerEntryEntity) {
            // `OnConflictStrategy.ABORT` on a unique `seq`, reproduced: two rows claiming
            // one position is the failure the mutex exists to prevent, and a fake that
            // quietly allowed it would hide exactly that bug.
            require(rows.none { it.seq == entry.seq }) { "duplicate seq ${entry.seq}" }
            require(rows.none { it.id == entry.id }) { "duplicate id ${entry.id}" }
            rows += entry
        }

        override suspend fun getAll(): List<LedgerEntryEntity> = rows.sortedBy { it.seq }
        override suspend fun getRecent(limit: Int) = rows.sortedByDescending { it.seq }.take(limit)
        override fun observeRecent(limit: Int): Flow<List<LedgerEntryEntity>> =
            flowOf(rows.sortedByDescending { it.seq }.take(limit))
        override suspend fun getBySession(sessionId: String) =
            rows.filter { it.sessionId == sessionId }.sortedBy { it.seq }
        override suspend fun getLast() = rows.maxByOrNull { it.seq }
        override suspend fun count() = rows.size
        override suspend fun deleteThroughSeq(seq: Long) {
            rows.removeAll { it.seq <= seq }
        }
    }

    // Shared across every repository a test builds, so a "restarted process" gets fresh
    // ids the way `UUID.randomUUID()` would rather than replaying the first run's.
    private var tick = 0L
    private var nextId = 0

    private fun repo(
        dao: LedgerDao,
        maxEntries: Int = LedgerRepository.DEFAULT_MAX_ENTRIES,
    ) = LedgerRepository(
        dao = dao,
        clock = { 1_700_000_000_000L + tick++ },
        idGenerator = { "id-${nextId++}" },
        maxEntries = maxEntries,
    )

    private fun draft(intent: String = "do the thing", session: String = "s1") = LedgerDraft(
        sessionId = session,
        kind = LedgerKind.TOOL,
        intent = intent,
        provenance = "USER",
        outcome = LedgerOutcome.OK,
        routeRung = 0,
        actions = listOf(LedgerAction("send_sms", true, 5)),
    )

    @Test
    fun `the first row starts from genesis and the chain grows from there`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)

        val first = repo.append(draft("one"))
        val second = repo.append(draft("two"))

        assertEquals(LedgerChain.GENESIS, first.prevHash)
        assertEquals(first.hash, second.prevHash)
        assertEquals(1L, first.seq)
        assertEquals(2L, second.seq)
        assertTrue(repo.verify().ok)
    }

    @Test
    fun `every row carries provenance rung outcome and cost`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.append(
            draft().copy(
                provenance = "UNTRUSTED",
                routeRung = 3,
                outcome = LedgerOutcome.BLOCKED,
                costUsd = 0.0042,
                flowRef = "signal.send@2",
            )
        )

        val row = repo.recent().single()
        assertEquals("UNTRUSTED", row.provenance)
        assertEquals(3, row.routeRung)
        assertEquals(LedgerOutcome.BLOCKED, row.outcome)
        assertEquals(0.0042, row.costUsd!!, 1e-9)
        assertEquals("signal.send@2", row.flowRef)
    }

    @Test
    fun `an unknown cost stays unknown`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.append(draft().copy(costUsd = null))
        assertNull(repo.recent().single().costUsd)
    }

    @Test
    fun `concurrent appends produce one unbroken chain`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)

        // A heartbeat mid-turn while the launcher chat is also running is the ordinary
        // case on this device, not a stress test.
        (1..40).map { i -> async { repo.append(draft("call $i")) } }.awaitAll()

        assertEquals(40, dao.count())
        val verification = repo.verify()
        assertTrue(verification.reason ?: "", verification.ok)
        assertEquals(39, verification.checkedLinks)
    }

    @Test
    fun `a restarted process picks the chain up where it left off`() = runTest {
        val dao = FakeDao()
        repo(dao).append(draft("before"))
        repo(dao).append(draft("before again"))

        // A fresh repository over the same table: no shared state, only what is on disk.
        val reopened = repo(dao)
        val next = reopened.append(draft("after a restart"))

        assertEquals(3L, next.seq)
        assertTrue(reopened.verify().ok)
    }

    @Test
    fun `rows are grouped by session`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.append(draft(session = "a"))
        repo.append(draft(session = "b"))
        repo.append(draft(session = "a"))

        assertEquals(2, repo.session("a").size)
        assertEquals(1, repo.session("b").size)
    }

    @Test
    fun `retention drops the oldest rows and what is left still verifies`() = runTest {
        val dao = FakeDao()
        // Cap of 5 with the built-in slack: pruning happens in batches, not per row.
        val repo = repo(dao, maxEntries = 5)
        repeat(1_100) { repo.append(draft("call $it")) }

        assertTrue("table should have been pruned", dao.count() < 1_100)
        val verification = repo.verify()
        assertTrue(verification.reason ?: "", verification.ok)
        // The surviving rows are the newest ones, and the chain continues from where the
        // prefix was cut rather than restarting.
        assertEquals(1_100L, verification.lastSeq)
        assertTrue(verification.firstSeq > 1L)
    }

    @Test
    fun `the intent is truncated rather than stored whole`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.append(draft(intent = "x".repeat(5_000)))
        assertEquals(LedgerDraft.MAX_INTENT_CHARS, repo.recent().single().intent.length)
    }
}
