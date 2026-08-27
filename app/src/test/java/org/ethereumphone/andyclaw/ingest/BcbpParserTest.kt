package org.ethereumphone.andyclaw.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * IATA Resolution 792, format M — the string inside the Aztec square.
 *
 * The fixture is the example that appears throughout the spec's own documentation, built
 * here field by field so the offsets are visible rather than assumed.
 */
class BcbpParserTest {

    /** `M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 226F001A0025 100` */
    private fun pass(
        legs: String = "1",
        name: String = "DESMARAIS/LUC",
        ticket: Char = 'E',
        pnr: String = "ABC123",
        from: String = "YUL",
        to: String = "FRA",
        carrier: String = "AC",
        flight: String = "0834",
        julian: String = "226",
        compartment: String = "F",
        seat: String = "001A",
        sequence: String = "0025",
        status: String = "1",
        sizeHex: String = "00",
    ) = buildString {
        append('M')
        append(legs)
        append(name.padEnd(20))
        append(ticket)
        append(pnr.padEnd(7))
        append(from.padEnd(3))
        append(to.padEnd(3))
        append(carrier.padEnd(3))
        append(flight.padEnd(5))
        append(julian)
        append(compartment)
        append(seat.padEnd(4))
        append(sequence.padEnd(5))
        append(status)
        append(sizeHex)
    }

    /** 14 August 2026 — day 226 of that year, so the Julian day resolves without ambiguity. */
    private val august2026 = LocalDate.of(2026, 8, 14).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun `the mandatory section is exactly sixty characters`() {
        assertEquals(BcbpParser.MANDATORY_LENGTH, pass().length)
    }

    @Test
    fun `every field comes out where the spec says it is`() {
        val parsed = BcbpParser.parse(pass(), august2026)!!

        assertEquals("DESMARAIS/LUC", parsed.passengerName)
        assertEquals("ABC123", parsed.recordLocator)
        assertEquals("YUL", parsed.fromAirport)
        assertEquals("FRA", parsed.toAirport)
        assertEquals("AC", parsed.carrier)
        assertEquals("834", parsed.flightNumber)
        assertEquals("F", parsed.cabin)
        assertEquals("1A", parsed.seat)
        assertEquals("25", parsed.sequenceNumber)
        assertEquals(BcbpParser.FORMAT, parsed.format)
    }

    @Test
    fun `the payload is kept exactly as it was found`() {
        // It is the string a gate reader has to see. Tidying it is a way to make a
        // boarding pass that does not scan.
        val raw = pass()
        assertEquals(raw, BcbpParser.parse(raw, august2026)!!.payload)
    }

    @Test
    fun `the julian day resolves to the nearest year`() {
        val parsed = BcbpParser.parse(pass(julian = "226"), august2026)!!
        assertEquals("2026-08-14", parsed.flightDate)
    }

    @Test
    fun `a january day seen in december belongs to next year`() {
        val december = LocalDate.of(2026, 12, 30).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val parsed = BcbpParser.parse(pass(julian = "003"), december)!!
        assertEquals("2027-01-03", parsed.flightDate)
    }

    @Test
    fun `a december day seen in january belongs to last year`() {
        val january = LocalDate.of(2027, 1, 2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val parsed = BcbpParser.parse(pass(julian = "360"), january)!!
        assertEquals("2026-12-26", parsed.flightDate)
    }

    @Test
    fun `text that merely starts with M1 is not a boarding pass`() {
        // A PDF is full of sixty-character runs. Reading a seat number out of one would be
        // worse than reading nothing.
        assertNull(BcbpParser.parse("M1" + "x".repeat(58), august2026))
        assertNull(BcbpParser.parse(pass(ticket = 'X'), august2026))
        assertNull(BcbpParser.parse(pass(from = "12"), august2026))
        assertNull(BcbpParser.parse(pass(julian = "abc"), august2026))
        assertNull(BcbpParser.parse(pass(legs = "9"), august2026))
    }

    @Test
    fun `too short is not a boarding pass`() {
        assertNull(BcbpParser.parse(pass().dropLast(1), august2026))
        assertNull(BcbpParser.parse("", august2026))
    }

    @Test
    fun `it is found inside surrounding text`() {
        val text = "Boarding pass for Mr Desmarais    ${pass()}    Please arrive 45 minutes early"
        val found = BcbpParser.find(text, august2026)
        assertNotNull(found)
        assertEquals("ABC123", found!!.recordLocator)
    }

    @Test
    fun `several passes in one document all come out`() {
        val a = pass(name = "LOVELACE/ADA", pnr = "AAA111", seat = "012C")
        val b = pass(name = "BABBAGE/CHAS", pnr = "BBB222", seat = "012D")
        val found = BcbpParser.findAll("junk $a more junk $b tail", august2026)

        assertEquals(2, found.size)
        assertEquals(listOf("AAA111", "BBB222"), found.map { it.recordLocator })
        assertEquals(listOf("12C", "12D"), found.map { it.seat })
    }

    @Test
    fun `an infant with no seat has no seat`() {
        val parsed = BcbpParser.parse(pass(seat = "    "), august2026)!!
        assertNull(parsed.seat)
    }

    @Test
    fun `finding nothing returns nothing`() {
        assertNull(BcbpParser.find("There is no boarding pass in this sentence at all.", august2026))
        assertTrue(BcbpParser.findAll("nothing here", august2026).isEmpty())
    }
}
