package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class WebSearchSkill(private val context: Context) : AndyClawSkill {
    override val id = "web_search"
    override val name = "Web Search"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override val baseManifest = SkillManifest(
        description = "Search the web for real-time information and fetch webpage contents. " +
                "Use this to answer questions about current events, exchange rates, weather, " +
                "prices, news, or any information that requires up-to-date web data.",
        tools = listOf(
            ToolDefinition(
                name = "web_search",
                description = "Search the web using DuckDuckGo and return a list of results " +
                        "with titles, URLs, and snippets. Use this when you need current " +
                        "information from the internet, such as exchange rates, news, " +
                        "weather, prices, or any factual question that may have changed " +
                        "since your training data.",
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(
                            mapOf(
                                "query" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive(
                                            "The search query to look up on the web"
                                        ),
                                    )
                                ),
                                "max_results" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive(
                                            "Maximum number of results to return (default: 5, max: 15)"
                                        ),
                                    )
                                ),
                            )
                        ),
                        "required" to JsonArray(listOf(JsonPrimitive("query"))),
                    )
                ),
            ),
            ToolDefinition(
                name = "fetch_webpage",
                description = "Fetch a webpage URL and extract its readable text content. " +
                        "Use this to read the full content of a page found via web_search, " +
                        "or to retrieve data from a known URL. Returns the extracted text " +
                        "content (HTML tags stripped).",
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(
                            mapOf(
                                "url" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive(
                                            "The full URL of the webpage to fetch"
                                        ),
                                    )
                                ),
                                "max_length" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive(
                                            "Maximum number of characters to return (default: 5000, max: 20000)"
                                        ),
                                    )
                                ),
                            )
                        ),
                        "required" to JsonArray(listOf(JsonPrimitive("url"))),
                    )
                ),
            ),
        ),
        permissions = listOf(android.Manifest.permission.INTERNET),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "web_search" -> webSearch(params)
            "fetch_webpage" -> fetchWebpage(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private suspend fun webSearch(params: JsonObject): SkillResult = withContext(Dispatchers.IO) {
        val query = params["query"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext SkillResult.Error("Missing required parameter: query")
        val maxResults = (params["max_results"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 15)

        try {
            val results = searchDuckDuckGo(query, maxResults)

            val response = buildJsonObject {
                put("query", query)
                put("result_count", results.size)
                put("results", results)
            }
            SkillResult.Success(response.toString())
        } catch (e: Exception) {
            SkillResult.Error("Web search failed: ${e.message}")
        }
    }

    private suspend fun fetchWebpage(params: JsonObject): SkillResult =
        withContext(Dispatchers.IO) {
            val url = params["url"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext SkillResult.Error("Missing required parameter: url")
            val maxLength =
                (params["max_length"]?.jsonPrimitive?.intOrNull ?: 5000).coerceIn(100, 20000)

            if (url.toHttpUrlOrNull() == null) {
                return@withContext SkillResult.Error("Invalid URL: $url")
            }

            try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    )
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .addHeader("Accept-Language", "en-US,en;q=0.9")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext SkillResult.Error(
                        "Failed to fetch URL (HTTP ${response.code}): $url"
                    )
                }

                val body = response.body?.string()
                    ?: return@withContext SkillResult.Error("Empty response from URL: $url")

                val contentType = response.header("Content-Type") ?: ""
                val text = if (contentType.contains("text/html", ignoreCase = true) ||
                    contentType.contains("application/xhtml", ignoreCase = true)
                ) {
                    extractReadableText(body)
                } else {
                    body
                }

                val truncated = if (text.length > maxLength) {
                    text.take(maxLength) + "\n\n[Content truncated at $maxLength characters]"
                } else {
                    text
                }

                val result = buildJsonObject {
                    put("url", url)
                    put("content_length", text.length)
                    put("truncated", text.length > maxLength)
                    put("content", truncated)
                }
                SkillResult.Success(result.toString())
            } catch (e: Exception) {
                SkillResult.Error("Failed to fetch webpage: ${e.message}")
            }
        }

    /**
     * Searches DuckDuckGo via the HTML-lite endpoint and parses results.
     * This avoids any need for a browser or API key.
     */
    private fun searchDuckDuckGo(query: String, maxResults: Int): JsonArray {
        val request = Request.Builder()
            .url("https://html.duckduckgo.com/html/")
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .post(
                FormBody.Builder()
                    .add("q", query)
                    .add("b", "")
                    .add("kl", "")
                    .build()
            )
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("DuckDuckGo returned HTTP ${response.code}")
        }

        val html = response.body?.string() ?: throw Exception("Empty response from DuckDuckGo")
        return parseDuckDuckGoResults(html, maxResults)
    }

    /**
     * Parses DuckDuckGo HTML-lite search result page.
     * Extracts result titles, URLs, and snippets from the result blocks.
     */
    private fun parseDuckDuckGoResults(html: String, maxResults: Int): JsonArray {
        val results = mutableListOf<JsonObject>()

        // DuckDuckGo HTML lite wraps each result in a div with class "result"
        // Each result contains:
        //   - <a class="result__a" href="...">Title</a>
        //   - <a class="result__snippet" ...>Snippet text</a>
        //   - <a class="result__url" href="...">display url</a>

        val resultPattern = Pattern.compile(
            """<div[^>]*class="[^"]*result\b[^"]*"[^>]*>(.*?)</div>\s*(?=<div[^>]*class="[^"]*result|$)""",
            Pattern.DOTALL
        )

        val titlePattern = Pattern.compile(
            """<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""",
            Pattern.DOTALL
        )

        val snippetPattern = Pattern.compile(
            """<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""",
            Pattern.DOTALL
        )

        val urlPattern = Pattern.compile(
            """<a[^>]*class="result__url"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""",
            Pattern.DOTALL
        )

        val resultMatcher = resultPattern.matcher(html)
        while (resultMatcher.find() && results.size < maxResults) {
            val block = resultMatcher.group(1) ?: continue

            val titleMatcher = titlePattern.matcher(block)
            val snippetMatcher = snippetPattern.matcher(block)
            val urlMatcher = urlPattern.matcher(block)

            if (titleMatcher.find()) {
                val rawUrl = titleMatcher.group(1) ?: ""
                val title = stripHtmlTags(titleMatcher.group(2) ?: "")

                val snippet = if (snippetMatcher.find()) {
                    stripHtmlTags(snippetMatcher.group(1) ?: "")
                } else ""

                // DuckDuckGo uses redirect URLs; extract the actual destination
                val actualUrl = extractDdgUrl(rawUrl)

                // Skip ad results and DuckDuckGo internal links
                if (actualUrl.isNotEmpty() &&
                    !actualUrl.startsWith("https://duckduckgo.com") &&
                    title.isNotEmpty()
                ) {
                    results.add(
                        buildJsonObject {
                            put("title", title)
                            put("url", actualUrl)
                            put("snippet", snippet.trim())
                        }
                    )
                }
            }
        }

        // Fallback: try a simpler pattern if the main one didn't match
        if (results.isEmpty()) {
            val simpleLinkPattern = Pattern.compile(
                """<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""",
                Pattern.DOTALL
            )
            val simpleMatcher = simpleLinkPattern.matcher(html)
            while (simpleMatcher.find() && results.size < maxResults) {
                val rawUrl = simpleMatcher.group(1) ?: continue
                val title = stripHtmlTags(simpleMatcher.group(2) ?: "")
                val actualUrl = extractDdgUrl(rawUrl)
                if (actualUrl.isNotEmpty() &&
                    !actualUrl.startsWith("https://duckduckgo.com") &&
                    title.isNotEmpty()
                ) {
                    results.add(
                        buildJsonObject {
                            put("title", title)
                            put("url", actualUrl)
                            put("snippet", "")
                        }
                    )
                }
            }
        }

        return JsonArray(results)
    }

    /**
     * DuckDuckGo redirects through //duckduckgo.com/l/?uddg=<encoded_url>&...
     * This extracts the actual destination URL.
     */
    private fun extractDdgUrl(rawUrl: String): String {
        if (rawUrl.contains("uddg=")) {
            val uddgPattern = Pattern.compile("[?&]uddg=([^&]+)")
            val matcher = uddgPattern.matcher(rawUrl)
            if (matcher.find()) {
                return try {
                    java.net.URLDecoder.decode(matcher.group(1) ?: "", "UTF-8")
                } catch (e: Exception) {
                    rawUrl
                }
            }
        }
        // If URL is already a direct link
        if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            return rawUrl
        }
        return ""
    }

    /**
     * Strips HTML tags and decodes common HTML entities.
     */
    private fun stripHtmlTags(html: String): String {
        return html
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Extracts readable text from HTML by removing scripts, styles,
     * navigation, and other non-content elements.
     */
    private fun extractReadableText(html: String): String {
        var text = html

        // Remove script and style blocks entirely
        text = text.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
        text = text.replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")
        text = text.replace(Regex("<nav[^>]*>.*?</nav>", RegexOption.DOT_MATCHES_ALL), " ")
        text = text.replace(Regex("<footer[^>]*>.*?</footer>", RegexOption.DOT_MATCHES_ALL), " ")
        text = text.replace(Regex("<header[^>]*>.*?</header>", RegexOption.DOT_MATCHES_ALL), " ")
        text = text.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        text = text.replace(Regex("<noscript[^>]*>.*?</noscript>", RegexOption.DOT_MATCHES_ALL), " ")

        // Convert common block elements to newlines for readability
        text = text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("</(p|div|h[1-6]|li|tr|blockquote|article|section)>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("<(p|div|h[1-6]|li|tr|blockquote|article|section)[^>]*>", RegexOption.IGNORE_CASE), "\n")

        // Strip remaining HTML tags
        text = stripHtmlTags(text)

        // Clean up whitespace
        text = text.replace(Regex("[ \\t]+"), " ")
        text = text.replace(Regex("\\n[ \\t]+"), "\n")
        text = text.replace(Regex("[ \\t]+\\n"), "\n")
        text = text.replace(Regex("\\n{3,}"), "\n\n")

        return text.trim()
    }
}
