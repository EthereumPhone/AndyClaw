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
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import java.util.zip.ZipInputStream
import kotlin.coroutines.resumeWithException

/**
 * HTTP client for the ClawHub public skill registry API (v1).
 *
 * Talks to the registry at [registryUrl] (default: `https://clawhub.com`)
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

    // ── Resolve ─────────────────────────────────────────────────────

    /**
     * Resolve a skill slug (and optional content hash) to a version.
     * Used to check if a local skill matches a published version.
     */
    suspend fun resolve(slug: String, hash: String? = null): ClawHubResolveResponse {
        val url = apiUrl(V1_RESOLVE) {
            addQueryParameter("slug", slug)
            if (hash != null) addQueryParameter("hash", hash)
        }
        val body = get(url)
        return json.decodeFromString(ClawHubResolveResponse.serializer(), body)
    }

    // ── Download ────────────────────────────────────────────────────

    /**
     * Download a skill bundle (ZIP) and extract it to [targetDir].
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

        val responseBody: ResponseBody = response.body ?: run {
            log.warning("Download returned empty body for $slug")
            response.close()
            return@withContext false
        }

        try {
            targetDir.mkdirs()
            extractZip(responseBody, targetDir)
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
     */
    private fun extractZip(body: ResponseBody, targetDir: File) {
        ZipInputStream(body.byteStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name).canonicalFile

                // Zip-slip guard: ensure extracted path stays within targetDir
                if (!outFile.path.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Zip entry escapes target dir: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        zis.copyTo(out)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        const val DEFAULT_REGISTRY = "https://clawhub.com"

        // V1 API paths
        private const val V1_SEARCH = "/api/v1/search"
        private const val V1_SKILLS = "/api/v1/skills"
        private const val V1_RESOLVE = "/api/v1/resolve"
        private const val V1_DOWNLOAD = "/api/v1/download"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
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
