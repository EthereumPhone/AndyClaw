package org.ethereumphone.andyclaw.ingest

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Text out of a PDF, for exactly one purpose: finding the boarding-pass payload in it.
 *
 * This is not a PDF renderer and must not grow into one. An airline's PDF boarding pass
 * carries the same IATA string that is in the barcode as literal text in a content stream,
 * so pulling the strings out of the streams and handing them to [BcbpParser] recovers the
 * scannable payload deterministically — no image decoding, no barcode reader, and no model.
 *
 * What it does: find each `stream … endstream`, inflate it when the object dictionary says
 * `/FlateDecode`, and collect the PDF string literals in order. What it deliberately does
 * not do: fonts, encodings, layout, or positioning. Strings are concatenated in stream
 * order with nothing between them, because a payload split across a `TJ` array by kerning
 * has to come back out as one run — and [BcbpParser] validates hard enough that the
 * occasional false neighbouring join cannot produce a boarding pass that is not there.
 */
object PdfTextExtractor {

    /** Bound the work: a boarding pass is a page or two, and a 50 MB PDF is not one. */
    private const val MAX_INPUT_BYTES = 24 * 1024 * 1024
    private const val MAX_INFLATED_BYTES = 8 * 1024 * 1024

    fun looksLikePdf(bytes: ByteArray): Boolean =
        bytes.size >= 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-"

    fun extract(pdf: ByteArray): String {
        if (pdf.isEmpty() || pdf.size > MAX_INPUT_BYTES) return ""
        val latin = String(pdf, Charsets.ISO_8859_1)
        val out = StringBuilder()

        var i = 0
        while (true) {
            val start = latin.indexOf(STREAM, i)
            if (start < 0) break
            // "endstream" ends in "stream"; do not mistake it for the start of one.
            if (start >= 3 && latin.startsWith(END_STREAM, start - 3)) {
                i = start + STREAM.length
                continue
            }

            var dataStart = start + STREAM.length
            if (dataStart < latin.length && latin[dataStart] == '\r') dataStart++
            if (dataStart < latin.length && latin[dataStart] == '\n') dataStart++

            val end = latin.indexOf(END_STREAM, dataStart)
            if (end < 0) break

            val dictStart = latin.lastIndexOf("<<", start)
            val dict = if (dictStart >= 0) latin.substring(dictStart, start) else ""
            val raw = pdf.copyOfRange(dataStart, end.coerceAtLeast(dataStart))
            val content = if (dict.contains("/FlateDecode")) inflate(raw) else raw

            if (content != null && content.isNotEmpty()) {
                out.append(collectStrings(String(content, Charsets.ISO_8859_1)))
            }
            i = end + END_STREAM.length
        }

        // An uncompressed PDF written by hand has no object streams worth walking; read the
        // literals straight out of the file rather than returning nothing.
        if (out.isEmpty()) out.append(collectStrings(latin))
        return out.toString()
    }

    // ── Streams ───────────────────────────────────────────────────────

    private fun inflate(data: ByteArray): ByteArray? {
        for (nowrap in listOf(false, true)) {
            val inflater = Inflater(nowrap)
            try {
                inflater.setInput(data)
                val buffer = ByteArray(16 * 1024)
                val out = ByteArrayOutputStream()
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_INFLATED_BYTES) break
                }
                if (out.size() > 0) return out.toByteArray()
            } catch (e: Exception) {
                // Try the other window setting, then give up on this stream.
            } finally {
                inflater.end()
            }
        }
        return null
    }

    // ── Strings ───────────────────────────────────────────────────────

    /** Every `(literal)` and `<hex>` string in [content], in order, decoded. */
    internal fun collectStrings(content: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < content.length) {
            when (content[i]) {
                '(' -> {
                    val (text, next) = readLiteral(content, i)
                    out.append(text)
                    i = next
                }
                '<' -> {
                    // `<<` opens a dictionary, not a hex string.
                    if (i + 1 < content.length && content[i + 1] == '<') {
                        i += 2
                    } else {
                        val close = content.indexOf('>', i + 1)
                        if (close < 0) return out.toString()
                        out.append(decodeHex(content.substring(i + 1, close)))
                        i = close + 1
                    }
                }
                else -> i++
            }
        }
        return out.toString()
    }

    /** Reads a `(…)` literal starting at [start]. Returns the text and the index after it. */
    private fun readLiteral(content: String, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var depth = 0
        var i = start
        while (i < content.length) {
            val c = content[i]
            when {
                c == '\\' -> {
                    if (i + 1 >= content.length) return sb.toString() to content.length
                    when (val e = content[i + 1]) {
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'b' -> { sb.append('\b'); i += 2 }
                        'f' -> { sb.append('\u000C'); i += 2 }
                        '(', ')', '\\' -> { sb.append(e); i += 2 }
                        '\n' -> i += 2                       // line continuation
                        '\r' -> i += if (i + 2 < content.length && content[i + 2] == '\n') 3 else 2
                        in '0'..'7' -> {
                            var j = i + 1
                            var value = 0
                            var digits = 0
                            while (j < content.length && digits < 3 && content[j] in '0'..'7') {
                                value = value * 8 + (content[j] - '0')
                                j++; digits++
                            }
                            sb.append((value and 0xFF).toChar())
                            i = j
                        }
                        else -> { sb.append(e); i += 2 }
                    }
                }
                c == '(' -> { depth++; if (depth > 1) sb.append(c); i++ }
                c == ')' -> {
                    depth--
                    if (depth == 0) return sb.toString() to (i + 1)
                    sb.append(c); i++
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString() to content.length
    }

    private fun decodeHex(raw: String): String {
        val digits = raw.filter { it.isDigit() || it in "abcdefABCDEF" }
        val padded = if (digits.length % 2 == 0) digits else digits + "0"
        val sb = StringBuilder(padded.length / 2)
        var i = 0
        while (i + 1 < padded.length) {
            sb.append(padded.substring(i, i + 2).toInt(16).toChar())
            i += 2
        }
        return sb.toString()
    }

    private const val STREAM = "stream"
    private const val END_STREAM = "endstream"
}
