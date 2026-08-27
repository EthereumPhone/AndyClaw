package org.ethereumphone.andyclaw.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Apple Wallet passes: a zip, a `pass.json`, and the barcode payload it already carries. */
class PkPassParserTest {

    private val utc = ZoneId.of("UTC")
    private val august2026 = LocalDate.of(2026, 8, 14).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val bcbp = buildString {
        append("M1")
        append("LOVELACE/ADA".padEnd(20))
        append('E')
        append("XYZ789".padEnd(7))
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

    private fun passJson(
        transit: String = "PKTransitTypeAir",
        barcodes: String = """"barcodes":[{"format":"PKBarcodeFormatAztec","message":"$bcbp","messageEncoding":"ISO-8859-1"}],""",
    ) = """
        {
          "formatVersion": 1,
          "organizationName": "Lufthansa",
          "description": "Boarding pass",
          "serialNumber": "SN-1",
          "relevantDate": "2026-08-14T07:10:00Z",
          $barcodes
          "boardingPass": {
            "transitType": "$transit",
            "headerFields": [{"key":"gate","label":"GATE","value":"K14"}],
            "primaryFields": [
              {"key":"origin","label":"MUNICH","value":"MUC"},
              {"key":"destination","label":"LONDON","value":"LHR"}
            ],
            "secondaryFields": [
              {"key":"terminal","label":"TERMINAL","value":"2"},
              {"key":"boardingTime","label":"BOARDS","value":"2026-08-14T08:10:00Z"}
            ],
            "auxiliaryFields": [{"key":"seat","label":"SEAT","value":"14A"}]
          }
        }
    """.trimIndent()

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `pass json comes out of the archive`() {
        val bytes = zip("manifest.json" to "{}", "pass.json" to passJson(), "icon.png" to "not really a png")
        assertNotNull(PkPassParser.readPassJson(bytes))
        assertTrue(PkPassParser.looksLikePkPass(bytes))
    }

    @Test
    fun `fields and barcodes parse`() {
        val pass = PkPassParser.parse(zip("pass.json" to passJson()), utc)!!

        assertEquals("Lufthansa", pass.organizationName)
        assertEquals("SN-1", pass.serialNumber)
        assertTrue(pass.isAir)
        assertEquals(1, pass.barcodes.size)
        assertEquals("PKBarcodeFormatAztec", pass.barcodes[0].format)
        assertEquals("K14", pass.fields["gate"])
        assertEquals("14A", pass.fields["seat"])
        assertEquals("GATE", pass.fieldLabels["gate"])
    }

    @Test
    fun `the barcode is the authority for the itinerary`() {
        val pass = PkPassParser.parse(zip("pass.json" to passJson()), utc)!!
        val flight = PkPassParser.toFlightReservation(pass, august2026, utc)!!

        assertEquals("XYZ789", flight.reservationNumber)
        assertEquals("LH", flight.airlineIata)
        assertEquals("400", flight.flightNumber)
        assertEquals("MUC", flight.departureAirport)
        assertEquals("LHR", flight.arrivalAirport)
        assertEquals("LOVELACE/ADA", flight.passengerName)
        // 14A from the barcode, which agrees with the pass field here.
        assertEquals("14A", flight.seat)
    }

    @Test
    fun `the pass fills in what the barcode has no room for`() {
        val pass = PkPassParser.parse(zip("pass.json" to passJson()), utc)!!
        val flight = PkPassParser.toFlightReservation(pass, august2026, utc)!!

        assertEquals("K14", flight.departureGate)
        assertEquals("2", flight.departureTerminal)
        assertEquals("Lufthansa", flight.airlineName)
    }

    @Test
    fun `the scannable payload survives unaltered`() {
        val pass = PkPassParser.parse(zip("pass.json" to passJson()), utc)!!
        val flight = PkPassParser.toFlightReservation(pass, august2026, utc)!!

        assertEquals(bcbp, flight.boardingPass!!.payload)
        // And it keeps Wallet's own format name, which says which symbology to render.
        assertEquals("PKBarcodeFormatAztec", flight.boardingPass!!.format)
    }

    @Test
    fun `the legacy singular barcode key is still read`() {
        val legacy = passJson(
            barcodes = """"barcode":{"format":"PKBarcodeFormatPDF417","message":"$bcbp"},"""
        )
        val pass = PkPassParser.parse(zip("pass.json" to legacy), utc)!!
        assertEquals(1, pass.barcodes.size)
        assertEquals("PKBarcodeFormatPDF417", pass.barcodes[0].format)
    }

    @Test
    fun `a pass whose barcode is not a BCBP still yields the payload`() {
        val opaque = passJson(
            barcodes = """"barcodes":[{"format":"PKBarcodeFormatQR","message":"https://example.com/checkin/42"}],"""
        )
        val pass = PkPassParser.parse(zip("pass.json" to opaque), utc)!!
        val flight = PkPassParser.toFlightReservation(pass, august2026, utc)!!

        assertEquals("https://example.com/checkin/42", flight.boardingPass!!.payload)
        // The itinerary then comes from the pass fields alone.
        assertEquals("MUC", flight.departureAirport)
        assertEquals("LHR", flight.arrivalAirport)
    }

    @Test
    fun `a non-air pass with no barcode is not a flight`() {
        val storeCard = """{"formatVersion":1,"organizationName":"Cafe","storeCard":{}}"""
        val pass = PkPassParser.parse(zip("pass.json" to storeCard), utc)!!
        assertFalse(pass.isAir)
        assertNull(PkPassParser.toFlightReservation(pass, august2026, utc))
    }

    @Test
    fun `an archive with no pass json parses to nothing`() {
        assertNull(PkPassParser.parse(zip("manifest.json" to "{}"), utc))
        assertNull(PkPassParser.readPassJson(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `bytes that are not a zip are not a pass`() {
        assertFalse(PkPassParser.looksLikePkPass("not a zip".toByteArray()))
        assertNull(PkPassParser.parse("not a zip".toByteArray(), utc))
    }
}
