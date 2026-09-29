package org.ethereumphone.andyclaw.ingest

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/** A hostile PDF cannot make the extractor hold more than its whole-document budgets. */
class PdfBudgetTest {

    /** Many FlateDecode streams, each inflating to ~4 MB of one long string literal. */
    private fun bombPdf(streams: Int): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write('('.code)
            write(ByteArray(4 * 1024 * 1024) { 'A'.code.toByte() })
            write(')'.code)
        }.toByteArray()
        val deflater = Deflater(Deflater.BEST_COMPRESSION).apply { setInput(body); finish() }
        val compressed = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (!deflater.finished()) compressed.write(buf, 0, deflater.deflate(buf))
        deflater.end()

        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1))
        repeat(streams) { n ->
            out.write("$n 0 obj\n<< /Length ${compressed.size()} /Filter /FlateDecode >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
            out.write(compressed.toByteArray())
            out.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        out.write("%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    @Test
    fun `text out of a zip-bomb pdf is capped`() {
        val text = PdfTextExtractor.extract(bombPdf(streams = 40))
        assertTrue("got ${text.length}", text.length <= PdfTextExtractor.MAX_TEXT_CHARS)
        assertTrue(text.isNotEmpty())
    }
}
