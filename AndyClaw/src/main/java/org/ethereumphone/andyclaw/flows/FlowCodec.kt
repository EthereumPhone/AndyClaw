package org.ethereumphone.andyclaw.flows

import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * The canonical byte form of a flow, and the content address derived from it.
 *
 * Flows are content-addressed, so "the same flow" has to mean the same bytes no matter
 * who wrote the file: key order, whitespace and omitted optional fields must not change
 * the address. Everything therefore goes through [canonicalJson] — fields in
 * declaration order, defaults always written, no pretty printing — and the hash is
 * taken over that, never over the file as it happened to be formatted on disk.
 */
object FlowCodec {

    /**
     * Reading is lenient about **unknown** keys on purpose: a flow written by a newer
     * build must not crash an older one (`CLAUDE.md` §3, on-disk state). It is strict
     * about everything else — an unknown opcode is a refusal, not a shrug.
     */
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    /** Same settings, indented — for anything a person is expected to read. */
    val prettyJson: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun canonicalJson(flow: Flow): String = json.encodeToString(Flow.serializer(), flow)

    fun canonicalBytes(flow: Flow): ByteArray = canonicalJson(flow).toByteArray(Charsets.UTF_8)

    fun parse(text: String): Flow = json.decodeFromString(Flow.serializer(), text)

    /** Parse, or null when the text is not a flow this build understands. */
    fun parseOrNull(text: String): Flow? = try {
        parse(text)
    } catch (e: Exception) {
        null
    }

    /** The flow's content address: sha256 over [canonicalBytes], lowercase hex. */
    fun contentHash(flow: Flow): String = sha256Hex(canonicalBytes(flow))

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}
