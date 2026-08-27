package org.ethereumphone.andyclaw.ingest

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * `agent-first-plan.md` Phase 3.3's definition of done: **zero LLM calls in the extraction
 * path** — assert this in a test.
 *
 * A test that ran an extraction and checked no request went out would only prove that this
 * particular input did not trigger one. So the assertion is structural instead, and it is
 * made two ways:
 *
 * 1. **Nothing in the extraction path can reach a model.** Each class's compiled bytes —
 *    including the synthetic classes Kotlin generates for its lambdas — are scanned for any
 *    reference to an LLM client or an HTTP stack. A constant-pool scan sees references
 *    inside method bodies, which a signature check would not.
 * 2. **Nothing in it can suspend.** Every model call in this app is a suspend function, and
 *    a pure `(String) -> List<Reservation>` cannot become one without changing its
 *    signature, which is a change a reviewer sees.
 *
 * `GmailIngestSource` and `CalendarIngestSource` are deliberately absent from the list.
 * They fetch, they suspend, they hold an OkHttp client — and they decide nothing. The split
 * between them and the parsers is what makes this property checkable at all.
 */
class IngestNoModelCallTest {

    private val extractionPath = listOf(
        ReservationExtractor::class.java,
        JsonLdReservationParser::class.java,
        BcbpParser::class.java,
        PkPassParser::class.java,
        PdfTextExtractor::class.java,
        ICalParser::class.java,
        GoogleCalendarEventParser::class.java,
        PredictedContextMapper::class.java,
        IsoDates::class.java,
    )

    /** Anything that could put a request on the wire or a prompt in front of a model. */
    private val forbidden = listOf(
        "org/ethereumphone/andyclaw/llm/",
        "okhttp3/",
        "java/net/Socket",
        "java/net/HttpURLConnection",
        "java/net/URLConnection",
    )

    @Test
    fun `no class in the extraction path can reach a model or the network`() {
        for (type in extractionPath) {
            for ((name, bytes) in classFamily(type)) {
                val text = String(bytes, Charsets.ISO_8859_1)
                for (marker in forbidden) {
                    if (text.contains(marker)) {
                        fail("$name references '$marker' — the extraction path must not be able to call out")
                    }
                }
            }
        }
    }

    @Test
    fun `no extraction entry point suspends`() {
        for (type in extractionPath) {
            for (method in type.declaredMethods) {
                val last = method.parameterTypes.lastOrNull()
                if (last != null && last.name == "kotlin.coroutines.Continuation") {
                    fail("${type.simpleName}.${method.name} is a suspend function; extraction must stay pure")
                }
            }
        }
    }

    @Test
    fun `extraction decodes base64 with the JVM implementation`() {
        // `android.util.Base64` is a stub under this project's unit tests
        // (`unitTests.isReturnDefaultValues = true`) and returns an empty array, so a
        // parser that used it would pass every test against nothing at all.
        for (type in extractionPath + GmailIngestSource::class.java) {
            for ((name, bytes) in classFamily(type)) {
                val text = String(bytes, Charsets.ISO_8859_1)
                if (text.contains("android/util/Base64")) {
                    fail("$name uses android.util.Base64, which is a no-op stub in unit tests")
                }
            }
        }
    }

    @Test
    fun `the scan actually finds the classes it claims to check`() {
        // Without this, a rename would turn every assertion above into a vacuous pass.
        for (type in extractionPath) {
            assertTrue("no class file found for ${type.name}", classFamily(type).isNotEmpty())
        }
        // And the marker check must be capable of failing: a class that does use OkHttp.
        val source = classFamily(GmailIngestSource::class.java)
            .any { (_, bytes) -> String(bytes, Charsets.ISO_8859_1).contains("okhttp3/") }
        assertTrue("the OkHttp marker no longer matches anything — the scan is not proving much", source)
    }

    /**
     * A class's own bytes plus every synthetic class the compiler emitted beside it.
     *
     * Kotlin compiles a lambda to a separate class named after the enclosing one, and a
     * reference that lived only there would be invisible to a scan of the outer class
     * alone. Resolved through the class loader with a fully-qualified path rather than
     * `Class.getResource`, whose relative-name resolution comes back empty under this test
     * runtime.
     */
    private fun classFamily(type: Class<*>): List<Pair<String, ByteArray>> {
        val path = type.name.replace('.', '/') + ".class"
        val loader = type.classLoader ?: return emptyList()
        val resource = loader.getResource(path) ?: return emptyList()

        val file = runCatching { File(resource.toURI()) }.getOrNull()
        if (file == null || !file.isFile) {
            // Packaged in a jar: the outer class is still checkable on its own.
            val bytes = loader.getResourceAsStream(path)?.use { it.readBytes() } ?: return emptyList()
            return listOf(type.simpleName to bytes)
        }

        val prefix = type.simpleName + "$"
        val dir = file.parentFile ?: return listOf(file.name to file.readBytes())
        val siblings = dir.listFiles { f: File ->
            f.isFile && f.name.endsWith(".class") && (f.name == file.name || f.name.startsWith(prefix))
        } ?: return listOf(file.name to file.readBytes())
        return siblings.map { it.name to it.readBytes() }
    }
}
