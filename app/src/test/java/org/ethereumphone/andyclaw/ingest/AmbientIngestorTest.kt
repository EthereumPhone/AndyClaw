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

    private class CountingMail(private val messages: List<MailMessage>) : MailSource {
        var calls = 0
        override suspend fun fetch(): List<MailMessage> {
            calls++
            return messages
        }
    }

    private class CountingCalendar(private val events: List<CalendarEvent>) : CalendarSource {
        var calls = 0
        override suspend fun fetch(fromMs: Long, toMs: Long, zone: ZoneId): List<CalendarEvent> {
            calls++
            return events
        }
    }

    private fun ingestor(
        mail: MailSource,
        calendar: CalendarSource,
        dao: PredictedContextDao,
        enabled: Boolean = true,
    ) = AmbientIngestor(
        mail = mail,
        calendar = calendar,
        contexts = PredictedContextRepository(dao) { now },
        clock = { now },
        zone = utc,
        enabled = { enabled },
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
            { _, _, _ -> throw IllegalStateException("calendar is down") },
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
}
