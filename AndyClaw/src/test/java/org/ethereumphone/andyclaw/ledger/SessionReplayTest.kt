package org.ethereumphone.andyclaw.ledger

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.ethereumphone.andyclaw.frames.FrameRetention
import org.ethereumphone.andyclaw.frames.SessionFrameStore
import org.ethereumphone.andyclaw.ledger.db.LedgerDao
import org.ethereumphone.andyclaw.ledger.db.entity.LedgerEntryEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The join: rows from one component, frames from another, one playable session. */
class SessionReplayTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakeDao : LedgerDao {
        val rows = mutableListOf<LedgerEntryEntity>()
        override suspend fun insert(entry: LedgerEntryEntity) { rows += entry }
        override suspend fun getAll() = rows.sortedBy { it.seq }
        override suspend fun getRecent(limit: Int) = rows.sortedByDescending { it.seq }.take(limit)
        override fun observeRecent(limit: Int): Flow<List<LedgerEntryEntity>> = flowOf(rows)
        override suspend fun getBySession(sessionId: String) =
            rows.filter { it.sessionId == sessionId }.sortedBy { it.seq }
        override suspend fun getLast() = rows.maxByOrNull { it.seq }
        override suspend fun count() = rows.size
        override suspend fun deleteThroughSeq(seq: Long) { rows.removeAll { it.seq <= seq } }
    }

    private var now = 1_700_000_000_000L

    private fun jpeg(b: Int) = ByteArray(32) { b.toByte() }

    @Test
    fun `a completed display session replays frame by frame`() = runTest {
        val dao = FakeDao()
        val ledger = LedgerRepository(dao, clock = { now })
        val frames = SessionFrameStore(temp.newFolder("f"), FrameRetention()) { now += 1_000; now }

        val session = frames.beginSession("s-1")
        val written = (1..4).map { jpeg(it) }
        val ids = written.map { session.write(it)!! }
        session.close()

        ledger.append(
            LedgerDraft(
                sessionId = "s-1",
                kind = LedgerKind.TOOL,
                intent = "book the taxi",
                provenance = "USER",
                outcome = LedgerOutcome.OK,
                routeRung = 4,
                actions = listOf(LedgerAction("agent_display_capture", true, 4_000)),
                frames = ids,
            )
        )
        ledger.append(
            LedgerDraft(
                sessionId = "s-1",
                kind = LedgerKind.TURN,
                intent = "book the taxi",
                provenance = "USER",
                outcome = LedgerOutcome.OK,
                modelIds = listOf("anthropic/claude-sonnet-4-6"),
            )
        )

        val replay = SessionReplay(ledger, frames).of("s-1")

        assertEquals("book the taxi", replay.intent)
        assertEquals(1, replay.steps.size)
        assertEquals(4, replay.frames.size)
        assertTrue(replay.missingFrames.isEmpty())
        assertEquals(ids, replay.frames.map { it.id })

        // Frame by frame, in order, byte for byte.
        val played = replay.frames.map { SessionReplay(ledger, frames).frameBytes(it.id)!! }
        written.zip(played).forEach { (a, b) -> assertArrayEquals(a, b) }
    }

    @Test
    fun `evicted frames are reported rather than silently skipped`() = runTest {
        val dao = FakeDao()
        val ledger = LedgerRepository(dao, clock = { now })
        val frames = SessionFrameStore(temp.newFolder("f"), FrameRetention()) { now += 1_000; now }

        val session = frames.beginSession("s-2")
        val ids = (1..3).map { session.write(jpeg(it))!! }
        session.close()

        ledger.append(
            LedgerDraft(
                sessionId = "s-2",
                kind = LedgerKind.TOOL,
                intent = "drive the app",
                provenance = "USER",
                outcome = LedgerOutcome.OK,
                frames = ids,
            )
        )

        // The ledger is append-only; the frame store is not. Retention will do this.
        frames.deleteSession("s-2")

        val replay = SessionReplay(ledger, frames).of("s-2")
        assertTrue(replay.frames.isEmpty())
        assertEquals(ids, replay.missingFrames)
    }

    @Test
    fun `a session with no display work still replays as its steps`() = runTest {
        val dao = FakeDao()
        val ledger = LedgerRepository(dao, clock = { now })
        val frames = SessionFrameStore(temp.newFolder("f"))

        ledger.append(
            LedgerDraft(
                sessionId = "s-3",
                kind = LedgerKind.TURN,
                intent = "what is my balance",
                provenance = "USER",
                outcome = LedgerOutcome.OK,
            )
        )

        val replay = SessionReplay(ledger, frames).of("s-3")
        assertEquals("what is my balance", replay.intent)
        assertTrue(replay.frames.isEmpty())
        assertTrue(replay.steps.isEmpty())
        assertEquals(LedgerKind.TURN, replay.turn!!.kind)
    }

    @Test
    fun `an unknown session replays as nothing`() = runTest {
        val dao = FakeDao()
        val replay = SessionReplay(
            LedgerRepository(dao, clock = { now }),
            SessionFrameStore(temp.newFolder("f")),
        ).of("never-existed")
        assertTrue(replay.isEmpty)
    }
}
