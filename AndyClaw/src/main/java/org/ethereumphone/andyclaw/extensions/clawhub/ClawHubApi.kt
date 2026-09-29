package org.ethereumphone.andyclaw.extensions.clawhub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.Interceptor
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import java.util.zip.ZipInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * HTTP client for the ClawHub public skill registry API (v1).
 *
 * Talks to the registry at [registryUrl] (default: `https://clawhub.ai`)
 * and provides typed wrappers around search, browse, resolve, and download
 * endpoints.
 *
 * All network calls are suspending and run on [Dispatchers.IO].
 *
 * @param registryUrl  Base URL for the ClawHub API (no trailing slash).
 * @param httpClient   Shared OkHttp client instance. A default with sensible
 *                     timeouts is created if omitted.
 */
class ClawHubApi(
    private val registryUrl: String = DEFAULT_REGISTRY,
    private val httpClient: OkHttpClient = defaultClient(),
) {

    private val log = Logger.getLogger("ClawHubApi")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── Search ──────────────────────────────────────────────────────

    /**
     * Search for skills by query string (vector + keyword search).
     *
     * @param query  Natural-language search query.
     * @param limit  Maximum number of results (server default if null).
     */
    suspend fun search(query: String, limit: Int? = null): ClawHubSearchResponse {
        val url = apiUrl(V1_SEARCH) {
            addQueryParameter("q", query)
            if (limit != null) addQueryParameter("limit", limit.toString())
        }
        val body = get(url)
        return json.decodeFromString(ClawHubSearchResponse.serializer(), body)
    }

    // ── Browse ──────────────────────────────────────────────────────

    /**
     * List skills with optional pagination cursor.
     */
    suspend fun listSkills(cursor: String? = null): ClawHubSkillListResponse {
        val url = apiUrl(V1_SKILLS) {
            if (cursor != null) addQueryParameter("cursor", cursor)
        }
        val body = get(url)
        return json.decodeFromString(ClawHubSkillListResponse.serializer(), body)
    }

    /**
     * Get detailed information about a single skill by slug.
     */
    suspend fun getSkill(slug: String): ClawHubSkillDetail {
        val url = apiUrl("$V1_SKILLS/$slug")
        val body = get(url)
        return json.decodeFromString(ClawHubSkillDetail.serializer(), body)
    }

    /**
     * List versions for a skill with optional pagination cursor.
     */
    suspend fun listVersions(slug: String, cursor: String? = null): ClawHubVersionListResponse {
        val url = apiUrl("$V1_SKILLS/$slug/versions") {
            if (cursor != null) addQueryParameter("cursor", cursor)
        }
        val body = get(url)
        return json.decodeFromString(ClawHubVersionListResponse.serializer(), body)
    }

    // ── Version detail ─────────────────────────────────────────────

    /**
     * Get version detail including security analysis status.
     *
     * Uses `GET /api/v1/skills/{slug}/versions/{version}`.
     * The response includes a `security` object with the LLM analysis
     * verdict ("clean", "suspicious", "malicious", "pending", "error").
     */
    suspend fun getVersionDetail(
        slug: String,
        version: String,
    ): ClawHubVersionDetail {
        val url = apiUrl("$V1_SKILLS/$slug/versions/$version")
        val body = get(url)
        return json.decodeFromString(ClawHubVersionDetail.serializer(), body)
    }

    // ── Download ────────────────────────────────────────────────────

    /**
     * Download a skill bundle (ZIP) and extract it to [targetDir].
     *
     * Uses `GET /api/v1/download?slug={slug}&version={version}`.
     *
     * @param slug      Skill slug.
     * @param version   Specific version to download (latest if null).
     * @param targetDir Directory to extract the skill files into.
     * @return true if the download and extraction succeeded.
     */
    suspend fun downloadAndExtract(
        slug: String,
        version: String? = null,
        targetDir: File,
    ): Boolean = withContext(Dispatchers.IO) {
        val url = apiUrl(V1_DOWNLOAD) {
            addQueryParameter("slug", slug)
            if (version != null) addQueryParameter("version", version)
        }

        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).await()

        if (!response.isSuccessful) {
            log.warning("Download failed for $slug: HTTP ${response.code}")
            response.close()
            return@withContext false
        }

        val contentType = response.header("Content-Type") ?: ""
        if (!contentType.contains("zip", ignoreCase = true) &&
            !contentType.contains("octet-stream", ignoreCase = true)
        ) {
            log.warning(
                "Download for $slug returned unexpected Content-Type: $contentType " +
                    "(expected application/zip)",
            )
        }

        val responseBody: ResponseBody = response.body ?: run {
            log.warning("Download returned empty body for $slug")
            response.close()
            return@withContext false
        }

        try {
            targetDir.mkdirs()
            val count = extractZip(responseBody, targetDir)
            if (count == 0) {
                log.warning("ZIP for $slug contained no extractable entries")
                return@withContext false
            }
            unwrapSingleRootDir(targetDir)
            true
        } catch (e: Exception) {
            log.warning("Failed to extract skill $slug: ${e.message}")
            false
        } finally {
            response.close()
        }
    }

    // ── Internals ───────────────────────────────────────────────────

    private fun apiUrl(
        path: String,
        configure: HttpUrl.Builder.() -> Unit = {},
    ): HttpUrl {
        return registryUrl.toHttpUrl().newBuilder()
            .encodedPath(path)
            .apply(configure)
            .build()
    }

    private suspend fun get(url: HttpUrl): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        val response = httpClient.newCall(request).await()

        return response.use { resp ->
            if (!resp.isSuccessful) {
                throw ClawHubApiException(
                    "ClawHub API error: HTTP ${resp.code} for ${url.encodedPath}",
                    resp.code,
                )
            }
            resp.body?.string() ?: throw ClawHubApiException(
                "ClawHub API returned empty body for ${url.encodedPath}",
                resp.code,
            )
        }
    }

    /**
     * Extract a ZIP response body into [targetDir], writing files relative
     * to the target. Skips entries outside the target (zip-slip guard).
     *
     * @return number of file entries extracted.
     */
    private fun extractZip(body: ResponseBody, targetDir: File): Int =
        extractZipStream(body.byteStream(), targetDir)

    /**
     * If a ZIP was extracted with a single top-level directory wrapping all
     * content (e.g., `skill-name/SKILL.md`), move everything up one level
     * so that SKILL.md sits directly in [targetDir].
     */
    private fun unwrapSingleRootDir(targetDir: File) {
        val children = targetDir.listFiles() ?: return
        if (children.size != 1 && children.none { it.name == "SKILL.md" }) {
            val dirs = children.filter { it.isDirectory }
            if (dirs.size == 1) {
                val wrapper = dirs[0]
                if (File(wrapper, "SKILL.md").isFile) {
                    for (child in wrapper.listFiles().orEmpty()) {
                        child.renameTo(File(targetDir, child.name))
                    }
                    wrapper.deleteRecursively()
                }
            }
        }
    }

    companion object {
        const val DEFAULT_REGISTRY = "https://clawhub.ai"

        /**
         * Caps on what one skill bundle may unpack to. A skill is a SKILL.md plus a
         * handful of scripts and references — kilobytes, and `TermuxSkillSync`
         * refuses anything over 2 MB anyway — so these only stop a zip bomb from
         * filling `filesDir` (and with it every other store in the app).
         */
        internal const val MAX_EXTRACTED_BYTES = 50L * 1024 * 1024
        internal const val MAX_ZIP_ENTRIES = 2_000

        /**
         * Extract [input] into [targetDir]. Throws [SecurityException] — and the
         * caller discards the partial extraction — on an entry that escapes the
         * target or when the bundle exceeds [maxBytes]/[maxEntries].
         *
         * The containment check is on `canonicalTarget + separator`: a bare
         * `startsWith(canonicalTarget)` let skill `foo` write into a sibling
         * `foo-bar/` via `../foo-bar/x`.
         *
         * @return number of file entries extracted.
         */
        internal fun extractZipStream(
            input: java.io.InputStream,
            targetDir: File,
            maxBytes: Long = MAX_EXTRACTED_BYTES,
            maxEntries: Int = MAX_ZIP_ENTRIES,
        ): Int {
            val root = targetDir.canonicalFile
            var count = 0
            var entries = 0
            var total = 0L
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (++entries > maxEntries) {
                        throw SecurityException("Zip has more than $maxEntries entries")
                    }
                    val outFile = File(root, entry.name).canonicalFile
                    val inside = outFile.path.startsWith(root.path + File.separator)

                    if (entry.isDirectory) {
                        if (!inside && outFile != root) {
                            throw SecurityException("Zip entry escapes target dir: ${entry.name}")
                        }
                        outFile.mkdirs()
                    } else {
                        if (!inside) {
                            throw SecurityException("Zip entry escapes target dir: ${entry.name}")
                        }
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            // Count what is actually inflated; the header's size is
                            // the archive's claim and can lie.
                            val buf = ByteArray(8 * 1024)
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > maxBytes) {
                                    throw SecurityException("Zip expands past $maxBytes bytes")
                                }
                                out.write(buf, 0, n)
                            }
                        }
                        count++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            return count
        }

        private const val V1_SEARCH = "/api/v1/search"
        private const val V1_SKILLS = "/api/v1/skills"
        private const val V1_DOWNLOAD = "/api/v1/download"

        private const val MAX_RETRIES = 5
        private const val INITIAL_BACKOFF_MS = 2000L
        private const val MAX_RETRY_DELAY_MS = 30_000L

        private fun defaultClient(): OkHttpClient {
            val tracker = RateLimitTracker()
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .addInterceptor(rateLimitInterceptor(tracker))
                .build()
        }

        /**
         * Combined interceptor: proactively throttles when rate limit headers
         * indicate the budget is running low, and retries with exponential
         * backoff (plus jitter) on HTTP 429.
         *
         * The previous implementation used Retry-After directly as the delay,
         * but the server often returns Retry-After: 1 even when the sliding
         * window won't reset for tens of seconds. Now we use
         * `max(exponentialBackoff, serverHint)` so the delay always escalates.
         */
        private fun rateLimitInterceptor(tracker: RateLimitTracker): Interceptor =
            Interceptor { chain ->
                val log = Logger.getLogger("ClawHubApi")
                val isDownload = chain.request().url.encodedPath.contains("/download")

                val preDelay = tracker.preemptiveDelayMs(isDownload)
                if (preDelay > 0) {
                    log.fine(
                        "Throttling: waiting ${preDelay}ms before " +
                            "${chain.request().url.encodedPath}",
                    )
                    Thread.sleep(preDelay)
                }

                var response = chain.proceed(chain.request())
                tracker.update(response, isDownload)
                var attempt = 0

                while (response.code == 429 && attempt < MAX_RETRIES) {
                    attempt++

                    val retryAfterSecs = response.header("Retry-After")?.toLongOrNull()
                    val exponentialMs = INITIAL_BACKOFF_MS * (1L shl (attempt - 1))
                    val serverHintMs = if (retryAfterSecs != null && retryAfterSecs > 0) {
                        retryAfterSecs * 1000 + 500
                    } else {
                        0L
                    }
                    val jitter = Random.nextLong(100, 1000)
                    val delayMs = (maxOf(exponentialMs, serverHintMs) + jitter)
                        .coerceAtMost(MAX_RETRY_DELAY_MS)

                    log.info(
                        "Rate limited (429), retrying in ${delayMs}ms " +
                            "(attempt $attempt/$MAX_RETRIES)",
                    )
                    response.close()
                    Thread.sleep(delayMs)
                    response = chain.proceed(chain.request())
                    tracker.update(response, isDownload)
                }

                response
            }
    }
}

/**
 * Suspend wrapper for OkHttp's async [Call.enqueue].
 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, _, _ -> response.close() }
        }
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }
    })
}

/**
 * Exception thrown when a ClawHub API call returns an unexpected status.
 */
class ClawHubApiException(
    message: String,
    val httpCode: Int,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Tracks server-reported rate limit state (remaining budget and reset time)
 * from `X-RateLimit-Remaining` and `RateLimit-Reset` response headers.
 *
 * Maintains separate counters for read vs download endpoints because the
 * ClawHub server enforces different limits per category (120/min read,
 * 20/min download per anonymous IP).
 *
 * Thread-safe via atomics — safe to share across OkHttp dispatcher threads.
 */
private class RateLimitTracker {
    private val readRemaining = AtomicInteger(Int.MAX_VALUE)
    private val readResetAtMs = AtomicLong(0L)
    private val downloadRemaining = AtomicInteger(Int.MAX_VALUE)
    private val downloadResetAtMs = AtomicLong(0L)

    fun update(response: Response, isDownload: Boolean) {
        val remaining = response.header("X-RateLimit-Remaining")?.toIntOrNull() ?: return
        val resetDelaySecs = response.header("RateLimit-Reset")?.toLongOrNull()
            ?: response.header("Retry-After")?.toLongOrNull()
            ?: return
        val resetAtMs = System.currentTimeMillis() + (resetDelaySecs * 1000)

        if (isDownload) {
            downloadRemaining.set(remaining)
            downloadResetAtMs.set(resetAtMs)
        } else {
            readRemaining.set(remaining)
            readResetAtMs.set(resetAtMs)
        }
    }

    /**
     * Returns milliseconds to wait before the next request, or 0 if the
     * budget is healthy. Starts throttling when remaining requests drop
     * below category-specific thresholds.
     */
    fun preemptiveDelayMs(isDownload: Boolean): Long {
        val remaining: Int
        val resetAtMs: Long
        val threshold: Int

        if (isDownload) {
            remaining = downloadRemaining.get()
            resetAtMs = downloadResetAtMs.get()
            threshold = THROTTLE_THRESHOLD_DOWNLOAD
        } else {
            remaining = readRemaining.get()
            resetAtMs = readResetAtMs.get()
            threshold = THROTTLE_THRESHOLD_READ
        }

        if (remaining >= threshold) return 0

        val windowRemainingMs = resetAtMs - System.currentTimeMillis()
        if (windowRemainingMs <= 0) return 0

        return if (remaining <= 0) {
            windowRemainingMs + Random.nextLong(100, 500)
        } else {
            (windowRemainingMs / remaining).coerceAtLeast(200)
        }
    }

    companion object {
        const val THROTTLE_THRESHOLD_READ = 10
        const val THROTTLE_THRESHOLD_DOWNLOAD = 3
    }
}
