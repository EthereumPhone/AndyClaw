package org.ethereumphone.andyclaw.skills.builtin

import okhttp3.ResponseBody
import java.io.InputStream

/**
 * Size limits for what a tool reads before it hands text to the model.
 *
 * A tool result goes into the prompt, so anything past a few hundred KB is useless to the model
 * anyway — and reading a whole remote file or local file into a String first was an
 * OutOfMemoryError, which is an Error, not an Exception, so no tool's `catch` saw it and the app
 * crashed instead.
 */
internal object BoundedText {

    /** Default ceiling for a byte read that ends up in a tool result. */
    const val MAX_READ_BYTES = 256 * 1024

    data class Read(val text: String, val truncated: Boolean)

    /** Reads at most [maxBytes] of [body] as UTF-8, never buffering the rest. */
    fun read(body: ResponseBody?, maxBytes: Int = MAX_READ_BYTES): Read {
        if (body == null) return Read("", false)
        return read(body.byteStream(), maxBytes)
    }

    /** Reads at most [maxBytes] of [input] as UTF-8. Does not close [input]. */
    fun read(input: InputStream, maxBytes: Int = MAX_READ_BYTES): Read {
        val limit = maxBytes.coerceAtLeast(0)
        // One byte over the limit tells a file of exactly [limit] bytes from a longer one.
        val buf = ByteArray(limit + 1)
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        val truncated = total > limit
        return Read(String(buf, 0, minOf(total, limit), Charsets.UTF_8), truncated)
    }

    /**
     * MIME types whose bytes are text the model can read. Anything else (images, archives,
     * APKs, office binaries) is refused rather than decoded into mojibake that fills the
     * context window.
     */
    fun isTextMime(mimeType: String?): Boolean {
        val m = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        if (m.startsWith("text/")) return true
        if (m.endsWith("+json") || m.endsWith("+xml")) return true
        return m in setOf(
            "application/json", "application/xml", "application/javascript",
            "application/x-javascript", "application/ecmascript", "application/x-sh",
            "application/x-yaml", "application/yaml", "application/toml", "application/sql",
            "application/x-ndjson", "application/csv", "application/x-httpd-php",
        )
    }

    private val SCRIPT_STYLE = Regex("(?is)<(script|style|head)\\b[^>]*>.*?</\\1\\s*>")
    private val BLOCK_BREAK = Regex("(?i)<\\s*(br|/p|/div|/li|/tr|/h[1-6])\\b[^>]*>")
    private val TAG = Regex("<[^>]*>")
    private val BLANK_LINES = Regex("\n[ \t]*\n([ \t]*\n)+")
    private val SPACES = Regex("[ \t\\x0B\\f\\r]+")

    /**
     * Cheap HTML → text for mail bodies: drops script/style/head, turns block ends into line
     * breaks, strips tags and decodes the handful of entities that matter. Not a parser — it
     * only has to stop raw markup from eating the model's context.
     */
    fun htmlToText(html: String): String = html
        .replace(SCRIPT_STYLE, "")
        .replace(BLOCK_BREAK, "\n")
        .replace(TAG, "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .replace(SPACES, " ")
        .replace(BLANK_LINES, "\n\n")
        .trim()

    /** Clamps a model-supplied count to [min, max], with [default] when absent. */
    fun clampLimit(requested: Int?, default: Int, max: Int, min: Int = 1): Int =
        (requested ?: default).coerceIn(min, max)
}
