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
}
