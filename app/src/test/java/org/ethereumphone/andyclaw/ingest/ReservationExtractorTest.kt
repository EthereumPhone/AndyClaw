package org.ethereumphone.andyclaw.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.time.Instant
import org.ethereumphone.andyclaw.ambient.TimePrecision

/**
 * The whole extraction path, end to end: a mail in, typed data out.
 *
 * The case that matters most is the last one — a booking confirmation and the boarding pass
 * that arrives two days later are one card, and they only converge because the dedupe key is
 * derived from the flight rather than from the message it was found in.
 */
class ReservationExtractorTest {

    private val utc = ZoneId.of("UTC")
    private val august2026 = LocalDate.of(2026, 8, 14).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val bcbp = buildString {
        append("M1")
        append("HOPPER/GRACE".padEnd(20))
        append('E')
        append("PNR001".padEnd(7))
        append("MUC")
        append("LHR")
        append("LH ")
        append("0400 ")
        append("226")
        append('C')
        append("014A")
        append("0031 ")
        append('1')
        append("00")
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
            "departureTime": "2026-08-14T09:40:00Z",
            "departureTerminal": "2"
          }
        }
        </script>
    """.trimIndent()

    private fun pkpass(): ByteArray {
        val passJson = """
            {
              "formatVersion": 1,
              "organizationName": "Lufthansa",
              "barcodes": [{"format":"PKBarcodeFormatAztec","message":"$bcbp"}],
              "boardingPass": {
                "transitType": "PKTransitTypeAir",
                "headerFields": [{"key":"gate","value":"K14"}]
              }
            }
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pass.json"))
            zip.write(passJson.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun mail(id: String, vararg parts: MailPart) =
        MailMessage(id = id, subject = "Your booking", from = "no-reply@lh.com", parts = parts.toList())

    @Test
    fun `a confirmation mail yields a flight`() {
        val result = ReservationExtractor.extract(
            mail("m1", MailPart("text/html", text = confirmation)),
            august2026,
            utc,
        )
        val flight = result.reservations.single() as FlightReservation
        assertEquals("400", flight.flightNumber)
        assertEquals("2", flight.departureTerminal)
        assertNull("nothing scannable was attached", flight.boardingPass)
    }

    @Test
    fun `a pkpass attachment yields a flight with a payload`() {
        val result = ReservationExtractor.extract(
            mail("m2", MailPart("application/vnd.apple.pkpass", filename = "pass.pkpass", bytes = pkpass())),
            august2026,
            utc,
        )
        val flight = result.reservations.single() as FlightReservation
        assertEquals(bcbp, flight.boardingPass!!.payload)
        assertEquals("K14", flight.departureGate)
    }

    @Test
    fun `the confirmation and the boarding pass become one card`() {
        val result = ReservationExtractor.extractAll(
            listOf(
                mail("m1", MailPart("text/html", text = confirmation)),
                mail("m2", MailPart("application/vnd.apple.pkpass", filename = "bp.pkpass", bytes = pkpass())),
            ),
            august2026,
            utc,
        )

        val flight = result.reservations.single() as FlightReservation
        // From the confirmation…
        assertEquals("2", flight.departureTerminal)
        assertEquals("Lufthansa", flight.airlineName)
        // …and from the pass.
        assertEquals("K14", flight.departureGate)
        assertEquals("14A", flight.seat)
        assertEquals(bcbp, flight.boardingPass!!.payload)
    }

    @Test
    fun `an ics attachment becomes a calendar event`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:invite-1
            SUMMARY:Kickoff
            DTSTART:20260901T090000Z
            DTEND:20260901T100000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = ReservationExtractor.extract(
            mail("m3", MailPart("text/calendar", filename = "invite.ics", text = ics)),
            august2026,
            utc,
        )
        assertEquals("Kickoff", result.calendarEvents.single().summary)
    }

    @Test
    fun `an attachment with no declared type is sniffed`() {
        // Mail clients mislabel attachments constantly; sniffing is a fact about the bytes,
        // not a guess about the sender's intent.
        val result = ReservationExtractor.extract(
            mail("m4", MailPart("application/octet-stream", filename = "download", bytes = pkpass())),
            august2026,
            utc,
        )
        assertNotNull((result.reservations.single() as FlightReservation).boardingPass)
    }

    @Test
    fun `an ordinary mail yields nothing`() {
        val result = ReservationExtractor.extract(
            mail("m5", MailPart("text/plain", text = "Hi, are we still on for Thursday?")),
            august2026,
            utc,
        )
        assertTrue(result.isEmpty)
    }

    @Test
    fun `a hotel and a flight in one mail both come out`() {
        val body = """
            <script type="application/ld+json">
            [
              { "@type": "FlightReservation", "reservationNumber": "F1",
                "reservationFor": { "@type": "Flight", "flightNumber": "10",
                  "airline": { "iataCode": "BA" }, "departureTime": "2026-09-01T09:00:00Z" } },
              { "@type": "LodgingReservation", "reservationNumber": "H1",
                "checkinTime": "2026-09-01T15:00:00Z",
                "reservationFor": { "@type": "Hotel", "name": "Ace Hotel" } }
            ]
            </script>
        """.trimIndent()

        val result = ReservationExtractor.extract(mail("m6", MailPart("text/html", text = body)), august2026, utc)
        assertEquals(2, result.reservations.size)
    }

    @Test
    fun `the same mail ingested twice still yields one of each`() {
        val message = mail("m7", MailPart("text/html", text = confirmation))
        val result = ReservationExtractor.extractAll(listOf(message, message), august2026, utc)
        assertEquals(1, result.reservations.size)
    }

    @Test
    fun `a mail with no parts is harmless`() {
        assertTrue(ReservationExtractor.extract(mail("m8"), august2026, utc).isEmpty)
    }

    // ── Local days, precise times ─────────────────────────────────────

    private fun passMail(id: String, receivedMs: Long) =
        MailMessage(id, receivedMs = receivedMs, parts = listOf(MailPart("application/vnd.apple.pkpass", filename = "bp.pkpass", bytes = pkpass())))

    private fun bookingMail(id: String, receivedMs: Long, departure: String) =
        MailMessage(id, receivedMs = receivedMs, parts = listOf(MailPart("text/html", text = confirmation.replace("2026-08-14T09:40:00Z", departure))))

    @Test
    fun `in Berlin a pass read after its confirmation is one card at the booked time`() {
        // Gmail lists newest first, so the pass is read first. Before: the pass's midnight
        // (the 13th in UTC) and the 09:40 booking (the 14th in UTC) were two cards.
        val berlin = ZoneId.of("Europe/Berlin")
        val result = ReservationExtractor.extractAll(
            listOf(passMail("pass", 2_000), bookingMail("booking", 1_000, "2026-08-14T09:40:00+02:00")),
            august2026,
            berlin,
        )

        val flight = result.reservations.single() as FlightReservation
        assertEquals(Instant.parse("2026-08-14T07:40:00Z").toEpochMilli(), flight.departureTimeMs)
        assertEquals(TimePrecision.EXACT, flight.departurePrecision)
        assertEquals("flight:LH400:2026-08-14", flight.sourceKey)
        assertEquals("K14", flight.departureGate)
        assertEquals(bcbp, flight.boardingPass!!.payload)
    }

    @Test
    fun `just after midnight in Berlin the booked time still wins over the pass's day`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val result = ReservationExtractor.extractAll(
            listOf(passMail("pass", 2_000), bookingMail("booking", 1_000, "2026-08-14T00:30:00+02:00")),
            august2026,
            berlin,
        )
        val flight = result.reservations.single() as FlightReservation
        assertEquals("not 00:00", Instant.parse("2026-08-13T22:30:00Z").toEpochMilli(), flight.departureTimeMs)
        assertEquals("flight:LH400:2026-08-14", flight.sourceKey)
    }

    @Test
    fun `an evening flight in New York keeps its own day`() {
        // 21:30 in New York is the next day in UTC; the barcode says the 14th, and so does the key.
        val newYork = ZoneId.of("America/New_York")
        val result = ReservationExtractor.extractAll(
            listOf(bookingMail("booking", 1_000, "2026-08-14T21:30:00-04:00"), passMail("pass", 2_000)),
            august2026,
            newYork,
        )
        val flight = result.reservations.single() as FlightReservation
        assertEquals("flight:LH400:2026-08-14", flight.sourceKey)
        assertEquals(Instant.parse("2026-08-15T01:30:00Z").toEpochMilli(), flight.departureTimeMs)
    }

    @Test
    fun `a boarding pass alone knows the day and not the hour`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val flight = ReservationExtractor.extract(passMail("pass", 2_000), august2026, berlin)
            .reservations.single() as FlightReservation

        assertNull("no midnight is invented", flight.departureTimeMs)
        assertEquals(TimePrecision.DATE_ONLY, flight.departurePrecision)
        assertEquals("2026-08-14", flight.departureDate)
        assertEquals("flight:LH400:2026-08-14", flight.sourceKey)

        val card = PredictedContextMapper.fromReservation(flight, "gmail:pass", berlin)!!
        assertEquals(Instant.parse("2026-08-13T22:00:00Z").toEpochMilli(), card.startMs)
        assertEquals("spans the whole local day", Instant.parse("2026-08-14T22:00:00Z").toEpochMilli(), card.endMs)
        assertTrue(card.payloadJson.contains("\"date_only\":true"))
        assertTrue(card.payloadJson.contains("\"departure_date\":\"2026-08-14\""))
        assertTrue("no departure time to show", !card.payloadJson.contains("departure_ms"))
    }

    @Test
    fun `the newer of two messages decides whether the booking stands`() {
        val cancelled = confirmation.replace(
            "\"reservationNumber\": \"PNR001\",",
            "\"reservationNumber\": \"PNR001\", \"reservationStatus\": \"ReservationCancelled\",",
        )
        val result = ReservationExtractor.extractAll(
            listOf(
                MailMessage("cancel", receivedMs = 2_000, parts = listOf(MailPart("text/html", text = cancelled))),
                MailMessage("booking", receivedMs = 1_000, parts = listOf(MailPart("text/html", text = confirmation))),
            ),
            august2026,
            utc,
        )
        assertTrue((result.reservations.single() as FlightReservation).cancelled)
    }

    @Test
    fun `signed mail is not combined with unsigned mail`() {
        val result = ReservationExtractor.extractAll(
            listOf(
                MailMessage("spoof", receivedMs = 2_000, authenticated = false,
                    parts = listOf(MailPart("text/html", text = confirmation.replace("\"departureTerminal\": \"2\"", "\"departureTerminal\": \"9\", \"departureGate\": \"Z99\"")))),
                MailMessage("real", receivedMs = 1_000, authenticated = true, parts = listOf(MailPart("text/html", text = confirmation))),
            ),
            august2026,
            utc,
        )
        val flight = result.reservations.single() as FlightReservation
        assertTrue(flight.authenticated)
        assertEquals("2", flight.departureTerminal)
        assertNull(flight.departureGate)
        assertEquals("real", flight.sourceMessageId)
    }
}
