package org.ethereumphone.andyclaw.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.Deflater

/**
 * The PDF path: recover the boarding-pass payload out of an airline's attachment without a
 * renderer, a barcode reader, or a model.
 */
class PdfBoardingPassTest {

    private val august2026 = LocalDate.of(2026, 8, 14).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val bcbp = buildString {
        append("M1")
        append("TURING/ALAN".padEnd(20))
        append('E')
        append("QRS456".padEnd(7))
        append("LHR")
        append("CDG")
        append("BA ")
        append("0304 ")
        append("226")
        append('Y')
        append("021F")
        append("0007 ")
        append('1')
        append("00")
    }

    private fun uncompressedPdf(content: String): ByteArray {
        val body = "BT /F1 12 Tf 72 720 Td ($content) Tj ET"
        return buildString {
            append("%PDF-1.4\n")
            append("4 0 obj\n<< /Length ${body.length} >>\nstream\n")
            append(body)
            append("\nendstream\nendobj\n")
            append("trailer\n<< /Root 1 0 R >>\n%%EOF\n")
        }.toByteArray(Charsets.ISO_8859_1)
    }

    private fun deflatedPdf(content: String): ByteArray {
        val body = "BT /F1 12 Tf 72 720 Td ($content) Tj ET".toByteArray(Charsets.ISO_8859_1)
        val deflater = Deflater()
        deflater.setInput(body)
        deflater.finish()
        val buffer = ByteArray(4096)
        val compressed = ByteArrayOutputStream()
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()

        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n4 0 obj\n<< /Length ${compressed.size()} /Filter /FlateDecode >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
        out.write(compressed.toByteArray())
        out.write("\nendstream\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    @Test
    fun `an uncompressed pdf gives up its payload`() {
        val text = PdfTextExtractor.extract(uncompressedPdf(bcbp))
        val pass = BcbpParser.find(text, august2026)

        assertNotNull(pass)
        assertEquals("QRS456", pass!!.recordLocator)
        assertEquals("LHR", pass.fromAirport)
        assertEquals("CDG", pass.toAirport)
        assertEquals("21F", pass.seat)
        assertEquals(bcbp, pass.payload)
    }

    @Test
    fun `a FlateDecode stream is inflated first`() {
        val text = PdfTextExtractor.extract(deflatedPdf(bcbp))
        val pass = BcbpParser.find(text, august2026)

        assertNotNull(pass)
        assertEquals("QRS456", pass!!.recordLocator)
        assertEquals("304", pass.flightNumber)
    }

    @Test
    fun `a payload split across a kerned TJ array is rejoined`() {
        // Text showing operators break a string wherever the typesetter felt like it, so
        // the extractor concatenates rather than separating.
        val half = bcbp.length / 2
        val body = "BT [(${bcbp.take(half)}) -20 (${bcbp.drop(half)})] TJ ET"
        val pdf = buildString {
            append("%PDF-1.4\n5 0 obj\n<< /Length ${body.length} >>\nstream\n")
            append(body)
            append("\nendstream\nendobj\n%%EOF\n")
        }.toByteArray(Charsets.ISO_8859_1)

        val pass = BcbpParser.find(PdfTextExtractor.extract(pdf), august2026)
        assertNotNull(pass)
        assertEquals("QRS456", pass!!.recordLocator)
    }

    @Test
    fun `escaped parentheses inside a literal survive`() {
        val text = PdfTextExtractor.collectStrings("""(a \(b\) c)""")
        assertEquals("a (b) c", text)
    }

    @Test
    fun `octal escapes decode`() {
        assertEquals("A", PdfTextExtractor.collectStrings("""(\101)"""))
    }

    @Test
    fun `a dictionary is not read as a hex string`() {
        // `<<` opens a dictionary; treating it as a hex string would inject junk into the
        // text and could manufacture a run that starts with M.
        assertEquals("ok", PdfTextExtractor.collectStrings("<< /Type /Page >>(ok)"))
    }

    @Test
    fun `a hex string decodes`() {
        assertEquals("Hi", PdfTextExtractor.collectStrings("<4869> Tj"))
    }

    @Test
    fun `endstream is not mistaken for the start of a stream`() {
        val pdf = uncompressedPdf(bcbp)
        // Two streams' worth of scanning must not walk off the end or double-read.
        val text = PdfTextExtractor.extract(pdf)
        assertEquals(1, BcbpParser.findAll(text, august2026).size)
    }

    @Test
    fun `a pdf with no boarding pass in it yields none`() {
        val text = PdfTextExtractor.extract(uncompressedPdf("Thank you for flying with us."))
        assertNull(BcbpParser.find(text, august2026))
    }

    @Test
    fun `bytes that are not a pdf are recognised as such`() {
        assertFalse(PdfTextExtractor.looksLikePdf("hello".toByteArray()))
        assertTrue(PdfTextExtractor.looksLikePdf(uncompressedPdf("x")))
        assertEquals("", PdfTextExtractor.extract(ByteArray(0)))
    }
}
