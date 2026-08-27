package org.ethereumphone.andyclaw.ingest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.ethereumphone.andyclaw.ambient.PredictedKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsed reservation to ambient card, with the barcode payload intact. */
class PredictedContextMapperTest {

    private fun payload(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject
    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    private val boardingPass = BoardingPass(
        payload = "M1HOPPER/GRACE        EPNR001 MUCLHRLH 0400 226C014A0031 100",
        format = "PKBarcodeFormatAztec",
        seat = "14A",
        sequenceNumber = "31",
        cabin = "C",
    )

    private val flight = FlightReservation(
        reservationNumber = "PNR001",
        airlineName = "Lufthansa",
        airlineIata = "LH",
        flightNumber = "400",
        departureAirport = "MUC",
        arrivalAirport = "LHR",
        departureTimeMs = 1_788_248_400_000L,
        departureTerminal = "2",
        departureGate = "K14",
        passengerName = "HOPPER/GRACE",
        seat = "14A",
        boardingPass = boardingPass,
    )

    @Test
    fun `a flight becomes a boarding-pass card`() {
        val context = PredictedContextMapper.fromReservation(flight, "gmail")!!

        assertEquals(PredictedKind.FLIGHT, context.kind)
        assertEquals("LH 400  MUC → LHR", context.title)
        assertEquals("Terminal 2 · Gate K14 · Seat 14A", context.subtitle)
        assertEquals(flight.departureTimeMs, context.startMs)
        assertEquals("flight:LH400:2026-09-01", context.sourceKey)
    }

    @Test
    fun `the barcode payload survives the trip unaltered`() {
        // The single value the card exists to display. Reformatting it is a way to make a
        // boarding pass that does not scan.
        val context = PredictedContextMapper.fromReservation(flight, "gmail")!!
        val json = payload(context.payloadJson)
        assertEquals(boardingPass.payload, json.str("barcode_payload"))
        assertEquals("PKBarcodeFormatAztec", json.str("barcode_format"))
        assertEquals("K14", json.str("gate"))
    }

    @Test
    fun `ingested content is untrusted`() {
        // A confirmation mail is written by whoever sent it. The class travels with the row
        // so nothing downstream can mistake a parsed field for something the user said.
        val context = PredictedContextMapper.fromReservation(flight, "gmail")!!
        assertEquals("UNTRUSTED", context.provenance)
    }

    @Test
    fun `a reservation with no time is dropped rather than given one`() {
        assertNull(PredictedContextMapper.fromReservation(flight.copy(departureTimeMs = null), "gmail"))
        assertNull(
            PredictedContextMapper.fromReservation(
                LodgingReservation(reservationNumber = "X", name = "Hotel", checkinMs = null),
                "gmail",
            )
        )
    }

    @Test
    fun `a hotel becomes a lodging card that spans the stay`() {
        val hotel = LodgingReservation(
            reservationNumber = "HT-9",
            name = "The Savoy",
            address = "Strand, London",
            checkinMs = 1_788_282_000_000L,
            checkoutMs = 1_788_541_200_000L,
        )
        val context = PredictedContextMapper.fromReservation(hotel, "gmail")!!

        assertEquals(PredictedKind.LODGING, context.kind)
        assertEquals("The Savoy", context.title)
        assertEquals(hotel.checkinMs, context.startMs)
        assertEquals(hotel.checkoutMs, context.endMs)
    }

    @Test
    fun `a calendar event becomes a calendar card`() {
        val event = CalendarEvent(
            uid = "abc@google.com",
            summary = "Design review",
            location = "Room 4",
            startMs = 1_788_251_400_000L,
            endMs = 1_788_255_000_000L,
        )
        val context = PredictedContextMapper.fromCalendarEvent(event, "gcal")!!

        assertEquals(PredictedKind.CALENDAR, context.kind)
        assertEquals("Design review", context.title)
        assertEquals("calendar:abc@google.com", context.sourceKey)
        assertEquals("Room 4", context.location)
    }

    @Test
    fun `an event with no title is not a card`() {
        assertNull(
            PredictedContextMapper.fromCalendarEvent(
                CalendarEvent(uid = "u", summary = null, startMs = 1L),
                "gcal",
            )
        )
    }

    @Test
    fun `absent fields are absent from the payload`() {
        val bare = FlightReservation(
            reservationNumber = null,
            airlineIata = "BA",
            flightNumber = "10",
            departureTimeMs = 1_788_248_400_000L,
        )
        val json = payload(PredictedContextMapper.fromReservation(bare, "gmail")!!.payloadJson)
        assertTrue(json["gate"] == null)
        assertTrue(json["barcode_payload"] == null)
        assertEquals("BA", json.str("airline_iata"))
    }
}
