package org.ethereumphone.andyclaw.ingest

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.ethereumphone.andyclaw.ambient.PredictedContextRepository
import org.ethereumphone.andyclaw.ambient.PredictedKind
import org.ethereumphone.andyclaw.ambient.db.PredictedContextDao
import org.ethereumphone.andyclaw.ambient.db.entity.PredictedContextEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The orchestration: sources in, [org.ethereumphone.andyclaw.ambient.PredictedContext] out.
 *
 * Driven against fake sources, which is why they are interfaces — the parsers are covered
 * on their own, and what is unproven without this is the part that decides how often to
 * fetch, what to write, and what happens when the same flight arrives twice.
 */
class AmbientIngestorTest {

    private val utc = ZoneId.of("UTC")
    private var now = 1_788_000_000_000L

    private class FakeDao : PredictedContextDao {
        val rows = mutableListOf<PredictedContextEntity>()
        override suspend fun insert(entry: PredictedContextEntity) {
            require(rows.none { it.sourceKey == entry.sourceKey })
            rows += entry
        }
        override suspend fun update(entry: PredictedContextEntity) {
            val i = rows.indexOfFirst { it.id == entry.id }
            if (i >= 0) rows[i] = entry
        }
        override suspend fun findBySourceKey(sourceKey: String) = rows.firstOrNull { it.sourceKey == sourceKey }
        override suspend fun findById(id: String) = rows.firstOrNull { it.id == id }
        override suspend fun findBySourceKeyLike(pattern: String) =
            rows.filter { it.sourceKey.startsWith(pattern.removeSuffix("%").replace("\\", "")) }
        override suspend fun deleteById(id: String) {
            rows.removeAll { it.id == id }
        }
        override suspend fun deleteBySourceLike(pattern: String) {
            rows.removeAll { it.source.startsWith(pattern.removeSuffix("%").replace("\\", "")) }
        }
        override suspend fun inWindow(from: Long, to: Long) = rows.filter { it.startMs in from..to }
        override suspend fun getAll() = rows.sortedBy { it.startMs }
        override fun observeAll(): Flow<List<PredictedContextEntity>> = flowOf(rows)
        override suspend fun dismiss(id: String, atMs: Long) {}
        override suspend fun deleteEndedBefore(beforeMs: Long) {
            rows.removeAll { (it.endMs ?: it.startMs) < beforeMs }
        }
        override suspend fun deleteAll() = rows.clear()
        override suspend fun count() = rows.size
    }

    private val confirmation = """
        <script type="application/ld+json">
        {
          "@type": "FlightReservation",
          "reservationNumber": "PNR001",
          "reservationFor": {
            "@type": "Flight",
            "flightNumber": "400",
            "airline": { "iataCode": "LH", "name": "Lufthansa" },
            "departureAirport": { "iataCode": "MUC" },
            "arrivalAirport": { "iataCode": "LHR" },
            "departureTime": "2026-09-01T09:40:00Z",
            "departureTerminal": "2"
          }
        }
        </script>
    """.trimIndent()

    private class CountingMail(var messages: List<MailMessage>) : MailSource {
        var calls = 0
        val fetched = mutableListOf<String>()
        var unavailable: IngestProblem? = null
        override suspend fun fetch(alreadySeen: (String) -> Boolean): SourceResult<List<MailMessage>> {
            calls++
            unavailable?.let { return SourceResult.Unavailable(it, it.name) }
            val fresh = messages.filterNot { alreadySeen(it.id) }
            fetched += fresh.map { it.id }
            return SourceResult.Fetched(fresh)
        }
    }

    private class CountingCalendar(var events: List<CalendarEvent>) : CalendarSource {
        var calls = 0
        var unavailable: IngestProblem? = null
        override suspend fun fetch(fromMs: Long, toMs: Long, zone: ZoneId): SourceResult<List<CalendarEvent>> {
            calls++
            unavailable?.let { return SourceResult.Unavailable(it, it.name) }
            return SourceResult.Fetched(events)
        }
    }

    private val state = IngestStateStore.InMemory()
    private val seen = IngestSeenStore.InMemory()

    private fun ingestor(
        mail: MailSource,
        calendar: CalendarSource,
        dao: PredictedContextDao,
        enabled: Boolean = true,
        deviceCalendar: CalendarSource? = null,
    ) = AmbientIngestor(
        mail = mail,
        calendar = calendar,
        contexts = PredictedContextRepository(dao, zone = { utc }) { now },
        clock = { now },
        zone = { utc },
        enabled = { enabled },
        deviceCalendar = deviceCalendar,
        stateStore = state,
        seen = seen,
    )

    @Test
    fun `a confirmation mail becomes a card`() = runBlocking {
        val dao = FakeDao()
        val report = ingestor(
            CountingMail(listOf(MailMessage("m1", parts = listOf(MailPart("text/html", text = confirmation))))),
            CountingCalendar(emptyList()),
            dao,
        ).ingest(AmbientSignal.MANUAL)

        assertTrue(report.ran)
        assertEquals(1, report.reservations)
        assertEquals(1, report.contextsWritten)

        val row = dao.rows.single()
        assertEquals(PredictedKind.FLIGHT.name, row.kind)
        assertEquals("flight:LH400:2026-09-01", row.sourceKey)
        assertEquals("UNTRUSTED", row.provenance)
        assertTrue(row.title.contains("MUC"))
    }

    @Test
    fun `calendar events become cards too`() = runBlocking {
        val dao = FakeDao()
        val report = ingestor(
            CountingMail(emptyList()),
            CountingCalendar(
                listOf(CalendarEvent(uid = "abc", summary = "Standup", startMs = now + 3_600_000))
            ),
            dao,
        ).ingest(AmbientSignal.MANUAL)
        assertEquals("gcal", dao.rows.single().source)

        assertEquals(1, report.calendarEvents)
        assertEquals("calendar:abc", dao.rows.single().sourceKey)
    }

    @Test
    fun `the same flight in two mails is one card`() = runBlocking {
        val dao = FakeDao()
        val mail = CountingMail(
            listOf(
                MailMessage("m1", parts = listOf(MailPart("text/html", text = confirmation))),
                MailMessage("m2", parts = listOf(MailPart("text/html", text = confirmation))),
            )
        )
        ingestor(mail, CountingCalendar(emptyList()), dao).ingest(AmbientSignal.MANUAL)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `a second signal inside the cooldown does not fetch again`() = runBlocking {
        val dao = FakeDao()
        val mail = CountingMail(emptyList())
        val calendar = CountingCalendar(emptyList())
        val ingestor = ingestor(mail, calendar, dao)

        ingestor.ingest(AmbientSignal.MAIL_NOTIFICATION)
        now += 5_000
        val second = ingestor.ingest(AmbientSignal.MAIL_NOTIFICATION)

        assertFalse(second.ran)
        assertEquals("one fetch, not two", 1, mail.calls)
        assertEquals(1, calendar.calls)
    }

    @Test
    fun `past the cooldown it fetches again`() = runBlocking {
        val dao = FakeDao()
        val mail = CountingMail(emptyList())
        val ingestor = ingestor(mail, CountingCalendar(emptyList()), dao)

        ingestor.ingest(AmbientSignal.MAIL_NOTIFICATION)
        now += AmbientTriggerPolicy.cooldownMs(AmbientSignal.MAIL_NOTIFICATION) + 1
        assertTrue(ingestor.ingest(AmbientSignal.MAIL_NOTIFICATION).ran)
        assertEquals(2, mail.calls)
    }

    @Test
    fun `switched off, nothing is fetched at all`() = runBlocking {
        val dao = FakeDao()
        val mail = CountingMail(listOf(MailMessage("m1", parts = listOf(MailPart("text/html", text = confirmation)))))
        val report = ingestor(mail, CountingCalendar(emptyList()), dao, enabled = false)
            .ingest(AmbientSignal.MANUAL)

        assertFalse(report.ran)
        assertEquals(0, mail.calls)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a source that throws does not lose what the other one found`() = runBlocking {
        val dao = FakeDao()
        val ingestor = ingestor(
            CountingMail(listOf(MailMessage("m1", parts = listOf(MailPart("text/html", text = confirmation))))),
            CalendarSource { _, _, _ -> throw IllegalStateException("calendar is down") },
            dao,
        )

        val report = ingestor.ingest(AmbientSignal.MANUAL)
        assertTrue(report.ran)
        assertEquals("calendar is down", report.skippedReason)
        // The flight was written before the calendar call failed, and it stays written.
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `what is long over is purged as it goes`() = runBlocking {
        val dao = FakeDao()
        dao.rows += PredictedContextEntity(
            id = "old", kind = "FLIGHT", title = "Last week", subtitle = null,
            startMs = now - 7 * 24 * 3_600_000L, endMs = null, location = null,
            payloadJson = "{}", provenance = "UNTRUSTED", source = "gmail",
            sourceKey = "flight:OLD", createdMs = 0, updatedMs = 0, dismissedMs = null,
        )

        ingestor(CountingMail(emptyList()), CountingCalendar(emptyList()), dao)
            .ingest(AmbientSignal.MANUAL)

        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `an ordinary mail writes nothing`() = runBlocking {
        val dao = FakeDao()
        val report = ingestor(
            CountingMail(listOf(MailMessage("m1", parts = listOf(MailPart("text/plain", text = "hi"))))),
            CountingCalendar(emptyList()),
            dao,
        ).ingest(AmbientSignal.MANUAL)

        assertTrue(report.ran)
        assertEquals(0, report.contextsWritten)
        assertTrue(dao.rows.isEmpty())
    }

    // ── Cooldown and failure ─────────────────────────────────────────

    @Test
    fun `a failed fetch does not use up the cooldown`() = runBlocking {
        // It used to: one dropped connection meant six hours without fresh cards.
        val dao = FakeDao()
        val mail = CountingMail(emptyList()).apply { unavailable = IngestProblem.OFFLINE }
        val ingestor = ingestor(mail, CountingCalendar(emptyList()), dao)

        val failed = ingestor.ingest(AmbientSignal.SCHEDULED)
        assertTrue(failed.ran)
        assertEquals(IngestState.OFFLINE, failed.state)

        now += 10_000
        assertFalse("a failure holds retries back briefly", ingestor.ingest(AmbientSignal.MAIL_NOTIFICATION).ran)

        now += AmbientTriggerPolicy.FAILURE_BACKOFF_MS
        mail.unavailable = null
        val retried = ingestor.ingest(AmbientSignal.SCHEDULED)
        assertTrue("the six-hour cooldown was never started", retried.ran)
        assertEquals(IngestState.OK, retried.state)
        assertEquals(now, state.load().lastSuccessMs)
    }

    @Test
    fun `no Google account is not a failure and the phone's calendar still makes cards`() = runBlocking {
        val dao = FakeDao()
        val report = ingestor(
            CountingMail(emptyList()).apply { unavailable = IngestProblem.NO_ACCOUNT },
            CountingCalendar(emptyList()).apply { unavailable = IngestProblem.NO_ACCOUNT },
            dao,
            deviceCalendar = CountingCalendar(listOf(CalendarEvent(uid = "d1", summary = "Dentist", startMs = now + 3_600_000))),
        ).ingest(AmbientSignal.MANUAL)

        assertEquals(IngestState.NO_ACCOUNT, report.state)
        assertEquals("device-calendar", dao.rows.single().source)
        assertEquals("the cooldown still counts from here", now, state.load().lastSuccessMs)
    }

    @Test
    fun `the cooldown survives a restart`() = runBlocking {
        val dao = FakeDao()
        val mail = CountingMail(emptyList())
        ingestor(mail, CountingCalendar(emptyList()), dao).ingest(AmbientSignal.SCHEDULED)
        now += 60_000
        // A new ingestor over the same stored state: what a process restart looks like.
        assertFalse(ingestor(mail, CountingCalendar(emptyList()), dao).ingest(AmbientSignal.SCHEDULED).ran)
        assertEquals(1, mail.calls)
    }

    // ── Mail already read ─────────────────────────────────────────────

    @Test
    fun `a message read in full is not downloaded again`() = runBlocking {
        val dao = FakeDao()
        val mail = CountingMail(listOf(MailMessage("m1", parts = listOf(MailPart("text/html", text = confirmation)))))
        val ingestor = ingestor(mail, CountingCalendar(emptyList()), dao)

        ingestor.ingest(AmbientSignal.MANUAL)
        mail.messages = mail.messages + MailMessage("m2", parts = listOf(MailPart("text/plain", text = "hi")))
        ingestor.ingest(AmbientSignal.MANUAL)

        assertEquals(listOf("m1", "m2"), mail.fetched)
        assertEquals("the card is still there", 1, dao.rows.size)
    }

    @Test
    fun `a message missing an attachment is read again next time`() = runBlocking {
        val dao = FakeDao()
        val partial = MailMessage("m1", parts = listOf(MailPart("text/html", text = confirmation)), complete = false)
        val mail = CountingMail(listOf(partial))
        val ingestor = ingestor(mail, CountingCalendar(emptyList()), dao)

        ingestor.ingest(AmbientSignal.MANUAL)
        ingestor.ingest(AmbientSignal.MANUAL)
        assertEquals(listOf("m1", "m1"), mail.fetched)
    }

    // ── Cancellations and the calendar's authority ───────────────────

    private val cancellation = confirmation.replace(
        "\"reservationNumber\": \"PNR001\",",
        "\"reservationNumber\": \"PNR001\", \"reservationStatus\": \"http://schema.org/ReservationCancelled\",",
    )

    @Test
    fun `a cancellation mail takes the card away`() = runBlocking {
        val dao = FakeDao()
        val repo = PredictedContextRepository(dao, zone = { utc }) { now }
        val mail = CountingMail(listOf(MailMessage("m1", receivedMs = 100, parts = listOf(MailPart("text/html", text = confirmation)))))
        val ingestor = ingestor(mail, CountingCalendar(emptyList()), dao)
        ingestor.ingest(AmbientSignal.MANUAL)
        assertEquals(1, repo.relevantNow(Instant.parse("2026-09-01T08:00:00Z").toEpochMilli()).size)

        mail.messages = mail.messages + MailMessage("m2", receivedMs = 200, parts = listOf(MailPart("text/html", text = cancellation)))
        ingestor.ingest(AmbientSignal.MANUAL)

        assertEquals("one row, now a tombstone", 1, dao.rows.size)
        assertTrue(repo.relevantNow(Instant.parse("2026-09-01T08:00:00Z").toEpochMilli()).isEmpty())
    }

    @Test
    fun `an event deleted from the calendar loses its card`() = runBlocking {
        val dao = FakeDao()
        val calendar = CountingCalendar(listOf(
            CalendarEvent(uid = "keep", summary = "Standup", startMs = now + 3_600_000),
            CalendarEvent(uid = "gone", summary = "Cancelled offsite", startMs = now + 7_200_000),
        ))
        val ingestor = ingestor(CountingMail(emptyList()), calendar, dao)
        ingestor.ingest(AmbientSignal.MANUAL)
        assertEquals(2, dao.rows.size)

        calendar.events = calendar.events.take(1)
        ingestor.ingest(AmbientSignal.MANUAL)
        assertEquals(listOf("calendar:keep"), dao.rows.map { it.sourceKey })
    }

    @Test
    fun `the calendar's version of an event beats the invitation mail`() = runBlocking {
        val dao = FakeDao()
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:kickoff-1
            SUMMARY:Kickoff
            DTSTART:20260901T090000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val moved = Instant.parse("2026-09-01T11:00:00Z").toEpochMilli()
        ingestor(
            CountingMail(listOf(MailMessage("m1", parts = listOf(MailPart("text/calendar", filename = "invite.ics", text = ics))))),
            CountingCalendar(listOf(CalendarEvent(uid = "kickoff-1", summary = "Kickoff", startMs = moved))),
            dao,
        ).ingest(AmbientSignal.MANUAL)

        val row = dao.rows.single()
        assertEquals("the calendar knows it moved", moved, row.startMs)
        assertEquals("gcal", row.source)
    }

    @Test
    fun `mail cards name the message they came from`() = runBlocking {
        val dao = FakeDao()
        ingestor(
            CountingMail(listOf(MailMessage("m42", parts = listOf(MailPart("text/html", text = confirmation))))),
            CountingCalendar(emptyList()),
            dao,
        ).ingest(AmbientSignal.MANUAL)
        assertEquals("gmail:m42", dao.rows.single().source)
    }
}
