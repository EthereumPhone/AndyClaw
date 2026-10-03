package org.ethereumphone.andyclaw.ambient

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.ethereumphone.andyclaw.ambient.PredictedContextPayload.str
import org.ethereumphone.andyclaw.ambient.db.PredictedContextDao
import org.ethereumphone.andyclaw.ambient.db.entity.PredictedContextEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** SQL `LIKE` with a `\` escape, as far as the store uses it: literal characters and a `%` tail. */
internal fun like(pattern: String): Regex {
    val sb = StringBuilder("^")
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when {
            c == '\\' && i + 1 < pattern.length -> { sb.append(Regex.escape(pattern[i + 1].toString())); i++ }
            c == '%' -> sb.append(".*")
            c == '_' -> sb.append(".")
            else -> sb.append(Regex.escape(c.toString()))
        }
        i++
    }
    return Regex(sb.append("$").toString(), RegexOption.IGNORE_CASE)
}

/** Deduplication on the real world, and querying by time-to-relevance. */
class PredictedContextRepositoryTest {

    private open class FakeDao : PredictedContextDao {
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

        override suspend fun findBySourceKey(sourceKey: String): PredictedContextEntity? = rows.firstOrNull { it.sourceKey == sourceKey }
        override suspend fun findById(id: String) = rows.firstOrNull { it.id == id }
        override suspend fun findBySourceKeyLike(pattern: String) = rows.filter { like(pattern).matches(it.sourceKey) }
        override suspend fun deleteById(id: String) {
            rows.removeAll { it.id == id }
        }
        override suspend fun deleteBySourceLike(pattern: String) {
            rows.removeAll { like(pattern).matches(it.source) }
        }

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
    private val utc = ZoneId.of("UTC")

    private fun repo(dao: PredictedContextDao) = PredictedContextRepository(dao, zone = { utc }) { now }

    private fun flight(startsInMs: Long, gate: String? = null, payload: String = "{}") = PredictedContext(
        id = "",
        kind = PredictedKind.FLIGHT,
        title = "LH 400 MUC → LHR",
        subtitle = gate?.let { "Gate $it" },
        startMs = now + startsInMs,
        payloadJson = payload,
        sourceKey = "flight:LH400:2026-09-01",
        source = "gmail",
    )

    /** A [FakeDao] whose next lookup waits on [gate], to hold a merge open mid-flight. */
    private class GatedDao : FakeDao() {
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun findBySourceKey(sourceKey: String): PredictedContextEntity? {
            val found = super.findBySourceKey(sourceKey)
            gate?.let { g -> gate = null; g.await() }
            return found
        }
    }

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
        // Rescoped: this used to pin that even a gate change kept the card away. Only the same
        // facts read again do; a new gate is exactly when the card should come back (below).
        val dao = FakeDao()
        val repo = repo(dao)
        val row = repo.put(flight(6 * hour, payload = """{"gate":"A14","time_precision":"exact"}"""))
        repo.dismiss(row.id)

        repo.put(flight(6 * hour, payload = """{"gate":"A14","time_precision":"exact"}"""))
        assertNotNull("a dismissed card must stay dismissed", repo.all().single().dismissedMs)
    }

    @Test
    fun `a gate change brings a dismissed card back`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        val row = repo.put(flight(6 * hour, payload = """{"gate":"A14"}"""))
        repo.dismiss(row.id)

        repo.put(flight(6 * hour, payload = """{"gate":"B22"}"""))
        assertNull(repo.all().single().dismissedMs)
    }

    @Test
    fun `a boarding pass arriving brings a dismissed card back`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.dismiss(repo.put(flight(6 * hour)).id)

        repo.put(flight(6 * hour, payload = """{"barcode_payload":"M1HOPPER"}"""))
        assertNull(repo.all().single().dismissedMs)
    }

    @Test
    fun `a re-ingest that is missing a field keeps the field`() = runTest {
        // One attachment that failed to download used to erase the barcode the last ingest had.
        val dao = FakeDao()
        val repo = repo(dao)
        repo.put(flight(6 * hour, payload = """{"barcode_payload":"M1HOPPER","gate":"K14"}"""))
        repo.put(flight(6 * hour, payload = """{"terminal":"2"}"""))

        val payload = PredictedContextPayload.parse(repo.all().single().payloadJson)
        assertEquals("M1HOPPER", payload.str("barcode_payload"))
        assertEquals("K14", payload.str("gate"))
        assertEquals("2", payload.str("terminal"))
        assertEquals("Terminal 2 · Gate K14", repo.all().single().subtitle)
    }

    @Test
    fun `a date-only pass does not replace an exact departure`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.put(flight(6 * hour, payload = """{"time_precision":"exact","departure_ms":${now + 6 * hour}}"""))
        repo.put(
            flight(-3 * hour, payload = """{"time_precision":"date_only","date_only":true,"seat":"14A"}""")
                .copy(endMs = now + 21 * hour)
        )

        val row = repo.all().single()
        assertEquals(now + 6 * hour, row.startMs)
        assertNull("no whole-day span next to a real time", row.endMs)
        val payload = PredictedContextPayload.parse(row.payloadJson)
        assertEquals("exact", payload.str("time_precision"))
        assertEquals("14A", payload.str("seat"))
        assertNull(payload["date_only"])
    }

    @Test
    fun `a flight ingested before the update keeps its exact time when the pass arrives`() = runTest {
        // v69 recorded no precision: an exact departure is simply a time that is not midnight.
        val dao = FakeDao()
        val repo = repo(dao)
        repo.put(flight(6 * hour, payload = """{"departure_ms":${now + 6 * hour}}"""))
        repo.put(
            flight(-3 * hour, payload = """{"time_precision":"date_only","date_only":true,"seat":"14A"}""")
                .copy(endMs = now + 21 * hour)
        )

        val row = repo.all().single()
        assertEquals(now + 6 * hour, row.startMs)
        assertEquals("14A", PredictedContextPayload.parse(row.payloadJson).str("seat"))
    }

    @Test
    fun `an exact departure replaces a date-only one`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.put(
            flight(-3 * hour, payload = """{"time_precision":"date_only","date_only":true}""").copy(endMs = now + 21 * hour)
        )
        repo.put(flight(6 * hour, payload = """{"time_precision":"exact","departure_ms":${now + 6 * hour}}"""))

        val row = repo.all().single()
        assertEquals(now + 6 * hour, row.startMs)
        assertNull(PredictedContextPayload.parse(row.payloadJson)["date_only"])
        assertNull("the date-only card's day span went with it", row.endMs)
    }

    @Test
    fun `a row an older build keyed by the UTC day is adopted, not duplicated`() = runTest {
        // A Berlin 00:30 departure: the old rule dated it the 31st (UTC), the new one the 1st.
        val dao = FakeDao()
        val repo = repo(dao)
        val legacy = repo.put(flight(6 * hour).copy(sourceKey = "flight:LH400:2026-08-31"))
        repo.dismiss(legacy.id)

        repo.put(flight(6 * hour).copy(sourceKey = "flight:LH400:2026-09-01"))

        val row = dao.rows.single()
        assertEquals("the card keeps its id", legacy.id, row.id)
        assertNotNull("and the user's dismissal", row.dismissedMs)
    }

    @Test
    fun `a boarding pass known only by its day is adopted by the booking within a day and a half`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.put(flight(20 * hour, payload = """{"time_precision":"exact"}""").copy(sourceKey = "flight:LH400:2023-11-15"))
        repo.put(
            flight(4 * hour, payload = """{"time_precision":"date_only","date_only":true,"barcode_payload":"M1"}""")
                .copy(sourceKey = "flight:LH400:2023-11-14")
        )
        assertEquals(1, dao.rows.size)
        assertEquals("M1", PredictedContextPayload.parse(dao.rows.single().payloadJson).str("barcode_payload"))
    }

    @Test
    fun `the same flight number a day later is a different flight`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.put(flight(2 * hour, payload = """{"time_precision":"exact"}""").copy(sourceKey = "flight:LH400:2023-11-14"))
        repo.put(flight(26 * hour, payload = """{"time_precision":"exact"}""").copy(sourceKey = "flight:LH400:2023-11-15"))
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun `a dismissal landing in the middle of a merge is not lost`() = runTest {
        val dao = GatedDao()
        val repo = repo(dao)
        val row = repo.put(flight(6 * hour))

        val gate = CompletableDeferred<Unit>()
        dao.gate = gate
        val merge = launch { repo.put(flight(8 * hour)) }
        runCurrent() // the merge has read the row and is waiting inside the store
        val dismissal = launch { repo.dismiss(row.id) }
        runCurrent()
        gate.complete(Unit)
        merge.join()
        dismissal.join()

        assertNotNull("the user's dismissal survives the merge", dao.rows.single().dismissedMs)
    }

    @Test
    fun `a cancellation hides the card and an older confirmation cannot bring it back`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.put(flight(6 * hour, payload = """{"observed_ms":100}"""))
        repo.put(flight(6 * hour, payload = """{"cancelled":true,"observed_ms":200}"""))
        assertTrue(repo.all().single().cancelled)
        assertTrue(repo.relevantNow(now).isEmpty())

        repo.put(flight(6 * hour, payload = """{"observed_ms":100}"""))
        assertTrue("the older confirmation read again", repo.all().single().cancelled)

        repo.put(flight(6 * hour, payload = """{"observed_ms":300}"""))
        assertTrue("a newer booking stands", !repo.all().single().cancelled)
    }

    @Test
    fun `a cancellation older than the confirmation after it is ignored`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.put(flight(6 * hour, payload = """{"observed_ms":300}"""))
        repo.put(flight(6 * hour, payload = """{"cancelled":true,"observed_ms":200}"""))
        assertTrue(!repo.all().single().cancelled)
    }

    @Test
    fun `unsigned mail can neither change nor add to a signed card`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.put(flight(6 * hour, payload = """{"authenticated":true,"terminal":"2"}"""))

        repo.put(flight(9 * hour, payload = """{"authenticated":false,"gate":"Z99","cancelled":true}"""))

        val row = repo.all().single()
        assertEquals(now + 6 * hour, row.startMs)
        assertNull("a spoofed gate is not added", PredictedContextPayload.parse(row.payloadJson)["gate"])
        assertTrue(!row.cancelled)
    }

    @Test
    fun `a mail signed by another domain cannot cancel the card, nor become its signer`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.put(flight(6 * hour, payload = """{"authenticated":true,"from_domain":"lufthansa.com","observed_ms":100}"""))

        repo.put(flight(6 * hour, payload = """{"authenticated":true,"from_domain":"lufthansa-info.example","cancelled":true,"observed_ms":200}"""))
        assertTrue(!repo.all().single().cancelled)

        // An update from it lands, but the row stays the airline's, so a second mail cannot cancel either.
        repo.put(flight(6 * hour, payload = """{"authenticated":true,"from_domain":"lufthansa-info.example","terminal":"1","observed_ms":300}"""))
        repo.put(flight(6 * hour, payload = """{"authenticated":true,"from_domain":"lufthansa-info.example","cancelled":true,"observed_ms":400}"""))
        val row = repo.all().single()
        assertTrue(!row.cancelled)
        assertEquals("lufthansa.com", PredictedContextPayload.parse(row.payloadJson)["from_domain"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })

        // The airline itself, from a subdomain, still can.
        repo.put(flight(6 * hour, payload = """{"authenticated":true,"from_domain":"mail.lufthansa.com","cancelled":true,"observed_ms":500}"""))
        assertTrue(repo.all().single().cancelled)
    }

    @Test
    fun `signed mail replaces what unsigned mail said`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.put(flight(9 * hour, payload = """{"authenticated":false,"gate":"Z99"}"""))
        repo.put(flight(6 * hour, payload = """{"authenticated":true,"terminal":"2"}"""))

        val row = repo.all().single()
        assertEquals(now + 6 * hour, row.startMs)
        assertNull(PredictedContextPayload.parse(row.payloadJson)["gate"])
    }

    @Test
    fun `the calendar outranks an invitation that came by mail`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        val event = PredictedContext(
            id = "", kind = PredictedKind.CALENDAR, title = "Kickoff", startMs = now + hour,
            sourceKey = "calendar:kickoff", source = "gcal",
        )
        repo.put(event)
        repo.put(event.copy(startMs = now + 3 * hour, source = "gmail-ics", payloadJson = """{"authenticated":true}"""))

        assertEquals(now + hour, repo.all().single().startMs)
        assertEquals("gcal", repo.all().single().source)
    }

    @Test
    fun `reading the same calendar again is not a change`() = runTest {
        val dao = FakeDao()
        var clock = now
        val repo = PredictedContextRepository(dao, zone = { utc }) { clock }
        val event = PredictedContext(
            id = "", kind = PredictedKind.CALENDAR, title = "Standup", startMs = now + hour,
            sourceKey = "calendar:s", source = "gcal", payloadJson = """{"summary":"Standup","observed_ms":1}""",
        )
        repo.put(event)
        clock += 60_000
        repo.put(event.copy(payloadJson = """{"observed_ms":2,"summary":"Standup"}"""))

        assertEquals("updatedMs marks real changes only", now, dao.rows.single().updatedMs)
    }

    @Test
    fun `disconnecting an account forgets its rows and only its rows`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.put(flight(6 * hour).copy(source = "gmail:m1"))
        repo.put(flight(6 * hour).copy(sourceKey = "calendar:x", kind = PredictedKind.CALENDAR, source = "device-calendar"))

        repo.clearSource("gmail")
        assertEquals(listOf("device-calendar"), dao.rows.map { it.source })
    }

    @Test
    fun `an event gone from the calendar loses its card`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        val base = PredictedContext(id = "", kind = PredictedKind.CALENDAR, title = "a", startMs = now + hour, sourceKey = "calendar:a", source = "gcal")
        repo.put(base)
        repo.put(base.copy(title = "b", sourceKey = "calendar:b"))
        repo.put(base.copy(title = "c", sourceKey = "calendar:c", source = "device-calendar"))

        val removed = repo.reconcile("gcal", now, now + 24 * hour, keep = setOf("calendar:a"))

        assertEquals(1, removed)
        assertEquals(setOf("calendar:a", "calendar:c"), dao.rows.map { it.sourceKey }.toSet())
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
