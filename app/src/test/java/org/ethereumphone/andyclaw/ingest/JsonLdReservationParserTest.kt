package org.ethereumphone.andyclaw.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * schema.org markup, parsed exactly.
 *
 * Every assertion here is an exact value rather than "not null", because the whole claim of
 * `agent-os-design.md` §5 is that these fields are *known* and not inferred. A test that
 * only checks something came out would pass against a parser that got the gate wrong.
 */
class JsonLdReservationParserTest {

    private val utc = ZoneId.of("UTC")

    private val flightMail = """
        <html><body>
        <p>Your booking is confirmed.</p>
        <script type="application/ld+json">
        {
          "@context": "http://schema.org",
          "@type": "FlightReservation",
          "reservationNumber": "ABC123",
          "underName": { "@type": "Person", "name": "Ada Lovelace" },
          "reservationFor": {
            "@type": "Flight",
            "flightNumber": "400",
            "airline": { "@type": "Airline", "name": "Lufthansa", "iataCode": "LH" },
            "departureAirport": { "@type": "Airport", "iataCode": "MUC", "name": "Munich" },
            "arrivalAirport": { "@type": "Airport", "iataCode": "LHR", "name": "Heathrow" },
            "departureTime": "2026-09-01T09:40:00+02:00",
            "arrivalTime": "2026-09-01T10:35:00+01:00",
            "departureTerminal": "2",
            "departureGate": "K14"
          }
        }
        </script>
        </body></html>
    """.trimIndent()

    @Test
    fun `a flight confirmation parses field for field`() {
        val flight = JsonLdReservationParser.parse(flightMail, utc).single() as FlightReservation

        assertEquals("ABC123", flight.reservationNumber)
        assertEquals("Lufthansa", flight.airlineName)
        assertEquals("LH", flight.airlineIata)
        assertEquals("400", flight.flightNumber)
        assertEquals("MUC", flight.departureAirport)
        assertEquals("Munich", flight.departureAirportName)
        assertEquals("LHR", flight.arrivalAirport)
        assertEquals("2", flight.departureTerminal)
        assertEquals("K14", flight.departureGate)
        assertEquals("Ada Lovelace", flight.passengerName)
        // 2026-09-01T09:40+02:00 is 07:40 UTC.
        assertEquals(1_788_248_400_000L, flight.departureTimeMs)
    }

    @Test
    fun `a flight's dedupe key names the flight, not the booking`() {
        val flight = JsonLdReservationParser.parse(flightMail, utc).single()
        assertEquals("flight:LH400:2026-09-01", flight.sourceKey)
    }

    @Test
    fun `a hotel confirmation parses`() {
        val body = """
            <script type="application/ld+json">
            {
              "@type": "LodgingReservation",
              "reservationNumber": "HT-99",
              "checkinTime": "2026-09-01T15:00:00+01:00",
              "checkoutTime": "2026-09-04T11:00:00+01:00",
              "reservationFor": {
                "@type": "LodgingBusiness",
                "name": "The Savoy",
                "address": {
                  "@type": "PostalAddress",
                  "streetAddress": "Strand",
                  "addressLocality": "London",
                  "postalCode": "WC2R 0EZ",
                  "addressCountry": "GB"
                }
              }
            }
            </script>
        """.trimIndent()

        val hotel = JsonLdReservationParser.parse(body, utc).single() as LodgingReservation
        assertEquals("HT-99", hotel.reservationNumber)
        assertEquals("The Savoy", hotel.name)
        assertEquals("Strand, London, WC2R 0EZ, GB", hotel.address)
        assertNotNull(hotel.checkinMs)
        assertTrue(hotel.checkoutMs!! > hotel.checkinMs!!)
        assertEquals("lodging:thesavoy:2026-09-01", hotel.sourceKey)
    }

    @Test
    fun `an event reservation parses`() {
        val body = """
            {
              "@type": "EventReservation",
              "reservationNumber": "EV-7",
              "ticketToken": "qrCode:1234",
              "reservationFor": {
                "@type": "MusicEvent",
                "name": "Kraftwerk",
                "startDate": "2026-10-02T20:00:00+02:00",
                "endDate": "2026-10-02T23:00:00+02:00",
                "location": { "@type": "Place", "name": "Olympiahalle" }
              }
            }
        """.trimIndent()

        val event = JsonLdReservationParser.parse(body, utc).single() as EventReservation
        assertEquals("Kraftwerk", event.eventName)
        assertEquals("Olympiahalle", event.location)
        assertEquals("qrCode:1234", event.ticketToken)
        assertEquals("event:kraftwerk:2026-10-02", event.sourceKey)
    }

    @Test
    fun `a graph with several reservations yields all of them`() {
        val body = """
            {
              "@context": "http://schema.org",
              "@graph": [
                { "@type": "FlightReservation", "reservationNumber": "A1",
                  "reservationFor": { "@type": "Flight", "flightNumber": "100",
                    "airline": { "iataCode": "BA" },
                    "departureAirport": { "iataCode": "LHR" },
                    "arrivalAirport": { "iataCode": "JFK" },
                    "departureTime": "2026-09-01T09:00:00Z" } },
                { "@type": "LodgingReservation", "reservationNumber": "B2",
                  "checkinTime": "2026-09-01T18:00:00Z",
                  "reservationFor": { "@type": "Hotel", "name": "Ace" } }
              ]
            }
        """.trimIndent()

        val parsed = JsonLdReservationParser.parse(body, utc)
        assertEquals(2, parsed.size)
        assertTrue(parsed.any { it is FlightReservation })
        assertTrue(parsed.any { it is LodgingReservation })
    }

    @Test
    fun `a type given as an array is still recognised`() {
        val body = """
            { "@type": ["Reservation", "FlightReservation"], "reservationNumber": "Z9",
              "reservationFor": { "@type": "Flight", "flightNumber": "22",
                "airline": { "iataCode": "AF" },
                "departureTime": "2026-09-01T09:00:00Z" } }
        """.trimIndent()
        assertEquals(1, JsonLdReservationParser.parse(body, utc).size)
    }

    @Test
    fun `a missing field stays missing`() {
        // The whole point: no gate in the mail means no gate on the card.
        val body = """
            { "@type": "FlightReservation",
              "reservationFor": { "@type": "Flight", "flightNumber": "1",
                "airline": { "iataCode": "SQ" },
                "departureAirport": { "iataCode": "SIN" },
                "arrivalAirport": { "iataCode": "LHR" },
                "departureTime": "2026-09-01T09:00:00Z" } }
        """.trimIndent()
        val flight = JsonLdReservationParser.parse(body, utc).single() as FlightReservation
        assertNull(flight.departureGate)
        assertNull(flight.departureTerminal)
        assertNull(flight.passengerName)
        assertNull(flight.reservationNumber)
    }

    @Test
    fun `a broken block does not take the good ones with it`() {
        val body = """
            <script type="application/ld+json">{ this is not json </script>
            <script type="application/ld+json">
            { "@type": "FlightReservation", "reservationNumber": "OK1",
              "reservationFor": { "@type": "Flight", "flightNumber": "9",
                "airline": { "iataCode": "KL" }, "departureTime": "2026-09-01T09:00:00Z" } }
            </script>
        """.trimIndent()
        assertEquals("OK1", JsonLdReservationParser.parse(body, utc).single().reservationNumber)
    }

    @Test
    fun `mail with no markup yields nothing`() {
        assertTrue(JsonLdReservationParser.parse("Dear customer, your flight is confirmed.", utc).isEmpty())
        assertTrue(JsonLdReservationParser.parse("", utc).isEmpty())
    }

    @Test
    fun `markup that identifies nothing is ignored`() {
        val body = """{ "@type": "FlightReservation", "reservationFor": { "@type": "Flight" } }"""
        assertTrue(JsonLdReservationParser.parse(body, utc).isEmpty())
    }

    @Test
    fun `a local departure time is resolved against the given zone`() {
        val body = """
            { "@type": "FlightReservation", "reservationNumber": "TZ",
              "reservationFor": { "@type": "Flight", "flightNumber": "5",
                "airline": { "iataCode": "LH" }, "departureTime": "2026-09-01T09:40:00" } }
        """.trimIndent()
        val berlin = JsonLdReservationParser.parse(body, ZoneId.of("Europe/Berlin")).single() as FlightReservation
        val tokyo = JsonLdReservationParser.parse(body, ZoneId.of("Asia/Tokyo")).single() as FlightReservation
        assertTrue(berlin.departureTimeMs!! > tokyo.departureTimeMs!!)
    }
}
