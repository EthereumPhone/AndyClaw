package org.ethereumphone.andyclaw.ambient

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.ethereumphone.andyclaw.ambient.db.PredictedContextDao
import org.ethereumphone.andyclaw.ambient.db.entity.PredictedContextEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deduplication on the real world, and querying by time-to-relevance. */
class PredictedContextRepositoryTest {

    private class FakeDao : PredictedContextDao {
        val rows = mutableListOf<PredictedContextEntity>()

        override suspend fun insert(entry: PredictedContextEntity) {
            require(rows.none { it.sourceKey == entry.sourceKey }) { "duplicate sourceKey" }
            rows += entry
        }

        override suspend fun update(entry: PredictedContextEntity) {
            val i = rows.indexOfFirst { it.id == entry.id }
            require(i >= 0)
            rows[i] = entry
        }

        override suspend fun findBySourceKey(sourceKey: String) = rows.firstOrNull { it.sourceKey == sourceKey }
        override suspend fun findById(id: String) = rows.firstOrNull { it.id == id }

        override suspend fun inWindow(from: Long, to: Long) = rows.filter {
            it.startMs in from..to ||
                (it.endMs != null && it.endMs in from..to) ||
                (it.startMs <= from && it.endMs != null && it.endMs >= to)
        }.sortedBy { it.startMs }

        override suspend fun getAll() = rows.sortedBy { it.startMs }
        override fun observeAll(): Flow<List<PredictedContextEntity>> = flowOf(rows.sortedBy { it.startMs })
        override suspend fun dismiss(id: String, atMs: Long) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(dismissedMs = atMs)
        }
        override suspend fun deleteEndedBefore(beforeMs: Long) {
            rows.removeAll { (it.endMs ?: it.startMs) < beforeMs }
        }
        override suspend fun deleteAll() = rows.clear()
        override suspend fun count() = rows.size
    }

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun repo(dao: PredictedContextDao) = PredictedContextRepository(dao) { now }

    private fun flight(startsInMs: Long, gate: String? = null) = PredictedContext(
        id = "",
        kind = PredictedKind.FLIGHT,
        title = "LH 400 MUC → LHR",
        subtitle = gate?.let { "Gate $it" },
        startMs = now + startsInMs,
        sourceKey = "flight:LH400:2026-09-01",
        source = "gmail",
    )

    @Test
    fun `the same flight from three mails is one row`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)

        repo.put(flight(6 * hour))
        repo.put(flight(6 * hour, gate = "A14"))
        repo.put(flight(6 * hour, gate = "A14"))

        assertEquals(1, dao.count())
        assertEquals("Gate A14", repo.all().single().subtitle)
    }

    @Test
    fun `an update keeps the original creation time`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        val created = repo.put(flight(6 * hour)).createdMs
        val updated = repo.put(flight(6 * hour, gate = "A14"))

        assertEquals(created, updated.createdMs)
    }

    @Test
    fun `re-ingesting the same thing does not undo a dismissal`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        val row = repo.put(flight(6 * hour))
        repo.dismiss(row.id)

        repo.put(flight(6 * hour, gate = "A14"))
        assertNotNull("a dismissed card must stay dismissed", repo.all().single().dismissedMs)
    }

    @Test
    fun `a changed time brings a dismissed card back`() = runTest {
        // A gate change or a delay is exactly when the card should return.
        val dao = FakeDao()
        val repo = repo(dao)
        val row = repo.put(flight(6 * hour))
        repo.dismiss(row.id)

        repo.put(flight(8 * hour))
        assertNull(repo.all().single().dismissedMs)
    }

    @Test
    fun `relevantNow ranks and filters`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)

        repo.put(flight(2 * hour))
        repo.put(
            PredictedContext(
                id = "",
                kind = PredictedKind.CALENDAR,
                title = "Standup",
                startMs = now + 20 * 60_000,
                sourceKey = "calendar:standup",
            )
        )
        repo.put(
            PredictedContext(
                id = "",
                kind = PredictedKind.EVENT,
                title = "Concert next month",
                startMs = now + 30 * 24 * hour,
                sourceKey = "calendar:concert",
            )
        )

        val relevant = repo.relevantNow(now)
        assertEquals(2, relevant.size)
        // The flight leads, and that is the curve working rather than a surprise: two
        // hours before a flight you should already be at the airport, while twenty minutes
        // before a standup you have not needed to move yet. Relevance is how far into a
        // thing's *own* window we are, not how few minutes are left on the clock.
        assertEquals("LH 400 MUC → LHR", relevant[0].context.title)
        assertEquals("Standup", relevant[1].context.title)
        assertTrue(relevant.none { it.context.title.contains("Concert") })
    }

    @Test
    fun `what is over and past its tail is purged`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.put(flight(-2 * 24 * hour))
        repo.put(flight(6 * hour).copy(sourceKey = "flight:LH999:2026-09-05"))

        repo.purgeExpired(now)
        assertEquals(1, dao.count())
    }

    @Test
    fun `ids are derived from the dedupe key, not generated`() = runTest {
        // A pruned-and-recreated row has to be the same card, or a dismissal and any held
        // reference point at something that no longer exists.
        assertEquals(
            PredictedContextRepository.idFor("flight:LH400:2026-09-01"),
            PredictedContextRepository.idFor("flight:LH400:2026-09-01"),
        )
        assertTrue(
            PredictedContextRepository.idFor("a") != PredictedContextRepository.idFor("b")
        )
    }

    @Test
    fun `upcoming lists what has not happened yet, soonest first`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.put(flight(-5 * hour))
        repo.put(flight(3 * hour).copy(sourceKey = "b"))
        repo.put(flight(1 * hour).copy(sourceKey = "c"))

        val upcoming = repo.upcoming(now)
        assertEquals(2, upcoming.size)
        assertTrue(upcoming[0].startMs < upcoming[1].startMs)
    }
}
