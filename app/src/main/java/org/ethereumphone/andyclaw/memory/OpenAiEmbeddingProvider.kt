package org.ethereumphone.andyclaw.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.ethereumphone.andyclaw.memory.embedding.EmbeddingException
import org.ethereumphone.andyclaw.memory.embedding.EmbeddingProvider
import java.util.concurrent.TimeUnit

/**
 * [EmbeddingProvider] backed by any OpenAI-compatible embeddings API.
 *
 * Works with OpenRouter, OpenAI, Azure OpenAI, and local servers that
 * implement the `/v1/embeddings` endpoint.
 *
 * ```kotlin
 * val provider = OpenAiEmbeddingProvider(
 *     apiKey = { securePrefs.apiKey.value },
 *     baseUrl = "https://openrouter.ai/api/v1",
 * )
 * memoryManager.setEmbeddingProvider(provider)
 * ```
 *
 * @param apiKey     Lambda returning the current API key (re-evaluated per request).
 * @param baseUrl    Base URL of the OpenAI-compatible API.
 * @param model      Embedding model to use.
 * @param dimensions Output dimensionality (pass null to use the model's default).
 */
class OpenAiEmbeddingProvider(
    private val apiKey: () -> String,
    private val baseUrl: String = "https://openrouter.ai/api/v1",
    private val model: String = DEFAULT_MODEL,
    override val dimensions: Int = DEFAULT_DIMENSIONS,
) : EmbeddingProvider {

    override val modelName: String get() = model

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        require(texts.isNotEmpty()) { "texts must not be empty" }

        val key = apiKey()
        if (key.isBlank()) throw EmbeddingException("No API key configured for embeddings")

        val requestBody = buildJsonObject {
            put("model", model)
            putJsonArray("input") {
                texts.forEach { add(JsonPrimitive(it)) }
            }
            put("dimensions", dimensions)
        }.toString()

        val url = "${baseUrl.trimEnd('/')}/embeddings"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw EmbeddingException("Embedding request failed: ${e.message}", e)
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string()?.take(500) ?: "no body"
                throw EmbeddingException("Embedding API returned ${resp.code}: $body")
            }

            val bodyStr = resp.body?.string()
                ?: throw EmbeddingException("Empty response body")

            parseEmbeddingResponse(bodyStr, texts.size)
        }
    }

    private fun parseEmbeddingResponse(body: String, expectedCount: Int): List<FloatArray> {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray
                ?: throw EmbeddingException("Missing 'data' field in response")

            if (data.size != expectedCount) {
                throw EmbeddingException(
                    "Expected $expectedCount embeddings, got ${data.size}"
                )
            }

            // Sort by index to ensure correct ordering
            data
                .map { it.jsonObject }
                .sortedBy { it["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }
                .map { obj ->
                    val embedding = obj["embedding"]?.jsonArray
                        ?: throw EmbeddingException("Missing 'embedding' in data entry")
                    FloatArray(embedding.size) { i ->
                        embedding[i].jsonPrimitive.float
                    }
                }
        } catch (e: EmbeddingException) {
            throw e
        } catch (e: Exception) {
            throw EmbeddingException("Failed to parse embedding response: ${e.message}", e)
        }
    }

    companion object {
        const val DEFAULT_MODEL = "openai/text-embedding-3-small"
        const val DEFAULT_DIMENSIONS = 512
    }
}
