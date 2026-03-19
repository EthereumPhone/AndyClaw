package org.ethereumphone.andyclaw.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Difficulty tier for model selection. Determined by the routing LLM based on
 * task complexity, then mapped to an appropriate model from the available pool.
 *
 * - [LIGHT]: Simple commands, factual Q&A, device operations → cheap/fast models
 * - [STANDARD]: Multi-step tasks, summarization, moderate reasoning → balanced models
 * - [POWERFUL]: Complex reasoning, code generation, creative writing, deep analysis → best models
 */
enum class ModelTier {
    LIGHT,
    STANDARD,
    POWERFUL;

    companion object {
        fun fromString(value: String?): ModelTier? = when (value?.lowercase()?.trim()) {
            "easy", "light", "simple" -> LIGHT
            "medium", "standard", "moderate" -> STANDARD
            "hard", "powerful", "complex" -> POWERFUL
            else -> null
        }
    }
}

/**
 * A model recommended by the registry for a specific tier.
 * Contains the model ID for the API request and metadata for logging/UI.
 */
data class RecommendedModel(
    val modelId: String,
    val displayName: String,
    val maxCompletionTokens: Int,
    val promptPricePerM: Double,
    val completionPricePerM: Double,
    val tier: ModelTier,
)

/**
 * Fetches, caches, and categorizes available models from the OpenRouter API.
 *
 * Models are classified into [ModelTier]s based on prompt pricing:
 * - LIGHT:    < $1/M prompt tokens  (flash/nano models)
 * - STANDARD: $1–$8/M prompt tokens (mid-tier: Sonnet, GPT-4.1, Gemini Pro)
 * - POWERFUL: ≥ $8/M prompt tokens  (flagship: Opus, GPT-5, Grok 4)
 *
 * Only models that support tool calling and produce text output are considered.
 * Results are cached in memory and on disk with a 24-hour TTL.
 */
class OpenRouterModelRegistry(
    context: Context? = null,
) {
    companion object {
        private const val TAG = "OpenRouterModelRegistry"
        private const val API_URL = "https://openrouter.ai/api/v1/models"
        private const val PREFS_NAME = "openrouter_model_registry"
        private const val CACHE_TTL_MS = 86_400_000L // 24 hours

        // Price boundaries per prompt token (USD). Multiply by 1_000_000 to get $/M.
        private const val LIGHT_MAX_PRICE_PER_TOKEN = 0.000001    // < $1/M
        private const val STANDARD_MAX_PRICE_PER_TOKEN = 0.000008 // < $8/M
        // >= $8/M → POWERFUL

        // Minimum requirements for a model to be considered
        private const val MIN_CONTEXT_LENGTH = 8192
        private const val MIN_MAX_COMPLETION = 2048

        /**
         * Ranked model quality preferences per tier. Order matters — first match wins.
         * Higher-ranked models get a larger score boost so they reliably beat
         * lower-quality models that happen to have larger context windows or
         * cheaper pricing. Each entry is (substring pattern, tier, rank).
         * Rank 1 = best in tier, gets the highest boost.
         *
         * This ranking reflects real-world model quality for tool use and
         * instruction following, NOT benchmark scores or context window size.
         */
        private val RANKED_MODEL_PREFERENCES: List<Triple<String, ModelTier, Int>> = listOf(
            // Tier LIGHT — ranked by tool-use reliability and speed
            Triple("qwen3.5-flash", ModelTier.LIGHT, 1),
            Triple("gpt-4.1-mini", ModelTier.LIGHT, 2),
            Triple("gpt-4.1-nano", ModelTier.LIGHT, 3),
            Triple("haiku", ModelTier.LIGHT, 4),
            Triple("gemini-3-flash", ModelTier.LIGHT, 5),
            Triple("gemini-2.5-flash", ModelTier.LIGHT, 6),
            Triple("flash", ModelTier.LIGHT, 7),
            Triple("qwen", ModelTier.LIGHT, 8),

            // Tier STANDARD — ranked by reasoning quality and tool-use accuracy
            Triple("claude-sonnet-4", ModelTier.STANDARD, 1),
            Triple("gpt-4.1", ModelTier.STANDARD, 2),
            Triple("kimi-k2.5", ModelTier.STANDARD, 3),
            Triple("minimax-m2.5", ModelTier.STANDARD, 4),
            Triple("claude-sonnet", ModelTier.STANDARD, 5),
            Triple("gpt-4o", ModelTier.STANDARD, 6),
            Triple("gemini-3.1-pro", ModelTier.STANDARD, 7),
            Triple("gemini-3-pro", ModelTier.STANDARD, 8),
            Triple("gemini-2.5-pro", ModelTier.STANDARD, 9),
            Triple("glm-5", ModelTier.STANDARD, 10),

            // Tier POWERFUL — ranked by deep reasoning capability
            Triple("claude-opus-4", ModelTier.POWERFUL, 1),
            Triple("gpt-5", ModelTier.POWERFUL, 2),
            Triple("grok-4", ModelTier.POWERFUL, 3),
            Triple("o3", ModelTier.POWERFUL, 4),
            Triple("o4-mini", ModelTier.POWERFUL, 5),
            Triple("deepseek-r1", ModelTier.POWERFUL, 6),
            Triple("claude-opus", ModelTier.POWERFUL, 7),
        )
    }

    /**
     * Internal representation of an OpenRouter model with parsed metadata.
     */
    data class ParsedModel(
        val id: String,
        val name: String,
        val promptPricePerToken: Double,
        val completionPricePerToken: Double,
        val contextLength: Int,
        val maxCompletionTokens: Int,
        val supportsTools: Boolean,
        val tier: ModelTier,
    ) {
        val promptPricePerM: Double get() = promptPricePerToken * 1_000_000
        val completionPricePerM: Double get() = completionPricePerToken * 1_000_000
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val prefs: SharedPreferences? = try {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Exception) {
        null
    }

    private val mutex = Mutex()
    private var cachedModels: List<ParsedModel> = emptyList()
    private var lastFetchTimeMs: Long = 0

    init {
        loadFromDisk()
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Refresh the model list from OpenRouter if the cache is stale.
     * Safe to call frequently — returns immediately if cache is fresh.
     */
    suspend fun refreshIfNeeded() {
        if (explicitlyLoaded) return // Test-injected data — don't overwrite
        if (System.currentTimeMillis() - lastFetchTimeMs < CACHE_TTL_MS && cachedModels.isNotEmpty()) {
            Log.d(TAG, "Model cache fresh (${(System.currentTimeMillis() - lastFetchTimeMs) / 1000}s old, ${cachedModels.size} models), skipping refresh")
            return
        }
        refresh()
    }

    /**
     * Force-refresh the model list from OpenRouter.
     * Returns the parsed and categorized model list.
     */
    suspend fun refresh(): List<ParsedModel> = mutex.withLock {
        try {
            val models = fetchModels()
            cachedModels = models
            lastFetchTimeMs = System.currentTimeMillis()
            persistToDisk()
            Log.i(TAG, "Refreshed ${models.size} models: " +
                "${models.count { it.tier == ModelTier.LIGHT }} light, " +
                "${models.count { it.tier == ModelTier.STANDARD }} standard, " +
                "${models.count { it.tier == ModelTier.POWERFUL }} powerful")
            models
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch models: ${e.message}")
            cachedModels // return stale cache on failure
        }
    }

    /** All models in the registry. */
    fun getAllModels(): List<ParsedModel> = cachedModels

    /** Models for a specific tier, sorted by quality score (best first). */
    fun getModelsForTier(tier: ModelTier): List<ParsedModel> {
        val sorted = cachedModels.filter { it.tier == tier }.sortedByDescending { qualityScore(it) }
        if (sorted.isNotEmpty()) {
            val top3 = sorted.take(3).joinToString { "${it.id}(${String.format("%.1f", qualityScore(it))})" }
            Log.d(TAG, "getModelsForTier: tier=$tier -> ${sorted.size} models, top3=[$top3]")
        }
        return sorted
    }

    /**
     * Pick the best model for [tier]. If [currentModelId] falls within the
     * requested tier, prefer it (avoids unnecessary model switching).
     * Returns null if no suitable models are available.
     */
    fun getBestModelForTier(tier: ModelTier, currentModelId: String? = null): RecommendedModel? {
        val candidates = getModelsForTier(tier)
        if (candidates.isEmpty()) {
            Log.w(TAG, "getBestModelForTier: no models available for tier=$tier (registry has ${cachedModels.size} total models)")
            return null
        }

        // Prefer the user's current model if it's in the right tier
        if (currentModelId != null) {
            val currentInTier = candidates.find { it.id == currentModelId }
            if (currentInTier != null) {
                Log.d(TAG, "getBestModelForTier: tier=$tier, keeping current model $currentModelId (already in tier)")
                return currentInTier.toRecommended()
            }
        }

        val best = candidates.first()
        Log.i(TAG, "getBestModelForTier: tier=$tier -> ${best.id} (${best.name}, " +
            "\$${String.format("%.2f", best.promptPricePerM)}/M prompt, " +
            "ctx=${best.contextLength}, out of ${candidates.size} candidates)")
        return best.toRecommended()
    }

    /**
     * Load a pre-parsed model list into the registry's cache.
     * Intended for testing — allows verifying getBestModelForTier / getModelsForTier
     * without needing a network call or Android Context.
     * Sets the fetch timestamp far into the future so [refreshIfNeeded] won't trigger.
     */
    fun loadModels(models: List<ParsedModel>) {
        cachedModels = models
        lastFetchTimeMs = System.currentTimeMillis()
        explicitlyLoaded = true
    }

    /** Set by [loadModels] to prevent [refreshIfNeeded] from overwriting test data. */
    private var explicitlyLoaded = false

    /** Look up a model by its API ID. */
    fun getModelById(id: String): ParsedModel? = cachedModels.find { it.id == id }

    /** Returns true if the registry has any models loaded (from cache or fresh). */
    fun isReady(): Boolean = cachedModels.isNotEmpty()

    /**
     * Determine which tier a model falls into based on its pricing.
     * Returns null if the model is not in the registry.
     */
    fun tierForModel(modelId: String): ModelTier? = getModelById(modelId)?.tier

    // ── Fetching ────────────────────────────────────────────────────────

    private suspend fun fetchModels(): List<ParsedModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(API_URL)
            .get()
            .build()

        val responseBody = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("OpenRouter models API returned ${response.code}")
            }
            response.body?.string() ?: throw RuntimeException("Empty response body")
        }

        parseModels(responseBody)
    }

    /**
     * Parse the JSON response from `/api/v1/models` and filter/categorize models.
     * Exposed as a companion function so unit tests can call it without needing a Context.
     */
    fun parseModels(jsonBody: String): List<ParsedModel> {
        val root = JSONObject(jsonBody)
        val data = root.getJSONArray("data")
        val result = mutableListOf<ParsedModel>()
        var skippedNoTools = 0
        var skippedNoText = 0
        var skippedFree = 0
        var skippedSmallCtx = 0
        var skippedSmallOutput = 0

        for (i in 0 until data.length()) {
            val model = data.getJSONObject(i)
            val parsed = parseModel(model, skipCounters = object : SkipCounters {
                override fun noTools() { skippedNoTools++ }
                override fun noText() { skippedNoText++ }
                override fun free() { skippedFree++ }
                override fun smallContext() { skippedSmallCtx++ }
                override fun smallOutput() { skippedSmallOutput++ }
            }) ?: continue
            result.add(parsed)
        }

        Log.d(TAG, "parseModels: ${data.length()} total -> ${result.size} accepted, " +
            "filtered out: $skippedNoTools no-tools, $skippedNoText no-text, " +
            "$skippedFree free, $skippedSmallCtx small-ctx, $skippedSmallOutput small-output")
        return result
    }

    /** Callbacks for tracking model filter reasons without cluttering per-model logs. */
    private interface SkipCounters {
        fun noTools()
        fun noText()
        fun free()
        fun smallContext()
        fun smallOutput()
    }

    private fun parseModel(json: JSONObject, skipCounters: SkipCounters? = null): ParsedModel? {
        val id = json.optString("id", "") .takeIf { it.isNotBlank() } ?: return null
        val name = json.optString("name", id)
        val contextLength = json.optInt("context_length", 0)

        // Filter: minimum context length
        if (contextLength < MIN_CONTEXT_LENGTH) {
            skipCounters?.smallContext()
            return null
        }

        // Parse pricing
        val pricing = json.optJSONObject("pricing") ?: return null
        val promptPrice = pricing.optString("prompt", "0").toDoubleOrNull() ?: 0.0
        val completionPrice = pricing.optString("completion", "0").toDoubleOrNull() ?: 0.0

        // Filter: skip free models (often rate-limited, unreliable for tool use)
        if (promptPrice <= 0 && completionPrice <= 0) {
            skipCounters?.free()
            return null
        }

        // Parse max completion tokens
        val topProvider = json.optJSONObject("top_provider")
        val maxCompletion = topProvider?.optInt("max_completion_tokens", 0) ?: 0
        val effectiveMaxCompletion = if (maxCompletion > 0) maxCompletion else 8192

        // Filter: minimum output capability
        if (effectiveMaxCompletion < MIN_MAX_COMPLETION) {
            skipCounters?.smallOutput()
            return null
        }

        // Check output modalities — must produce text
        val architecture = json.optJSONObject("architecture")
        val outputModalities = architecture?.optJSONArray("output_modalities")
        if (outputModalities != null) {
            var hasText = false
            for (j in 0 until outputModalities.length()) {
                if (outputModalities.optString(j) == "text") hasText = true
            }
            if (!hasText) {
                skipCounters?.noText()
                return null
            }
        }

        // Check tool support
        val supportedParams = json.optJSONArray("supported_parameters")
        var supportsTools = false
        if (supportedParams != null) {
            for (j in 0 until supportedParams.length()) {
                if (supportedParams.optString(j) == "tools") {
                    supportsTools = true
                    break
                }
            }
        }

        // Only include models that support tool calling
        if (!supportsTools) {
            skipCounters?.noTools()
            return null
        }

        // Categorize by pricing
        val tier = when {
            promptPrice < LIGHT_MAX_PRICE_PER_TOKEN -> ModelTier.LIGHT
            promptPrice < STANDARD_MAX_PRICE_PER_TOKEN -> ModelTier.STANDARD
            else -> ModelTier.POWERFUL
        }

        return ParsedModel(
            id = id,
            name = name,
            promptPricePerToken = promptPrice,
            completionPricePerToken = completionPrice,
            contextLength = contextLength,
            maxCompletionTokens = effectiveMaxCompletion,
            supportsTools = true,
            tier = tier,
        )
    }

    // ── Quality scoring ─────────────────────────────────────────────────

    /**
     * Composite quality score for ranking models within a tier.
     * Higher is better. The ranking is dominated by explicit quality preferences
     * (known good models for tool use). Context window and pricing are minor
     * tie-breakers, NOT primary ranking factors.
     *
     * Score breakdown:
     * - Known model rank: 1000 - (rank * 50). Rank 1 = 950, rank 10 = 500.
     *   Unknown models get 0. This ensures known good models always beat unknowns.
     * - Context window: 0–5 points (minor tie-breaker, capped to prevent dominance)
     * - Max completion: 0–3 points (minor)
     * - Cost efficiency: 0–5 points (minor, cheaper = slight bonus)
     */
    private fun qualityScore(model: ParsedModel): Double {
        var score = 0.0

        // Primary signal: explicit quality ranking (dominates the score)
        val idLower = model.id.lowercase()
        for ((pattern, tier, rank) in RANKED_MODEL_PREFERENCES) {
            if (tier == model.tier && idLower.contains(pattern)) {
                score += 1000.0 - (rank * 50.0)
                break
            }
        }

        // Minor tie-breaker: context window (capped at 5 points)
        // ln(1M) ≈ 13.8, ln(8K) ≈ 9.0 → range ~5 → normalize to 0-5
        score += (kotlin.math.ln(model.contextLength.toDouble()) - 9.0).coerceIn(0.0, 5.0)

        // Minor tie-breaker: max completion tokens (capped at 3 points)
        score += (kotlin.math.ln(model.maxCompletionTokens.toDouble()) - 8.0).coerceIn(0.0, 3.0)

        // Minor tie-breaker: cost efficiency (cheaper completion = slight bonus, 0-5 points)
        if (model.completionPricePerToken > 0) {
            score += 5.0 / (1.0 + model.completionPricePerToken * 100_000)
        }

        return score
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private fun persistToDisk() {
        try {
            val arr = JSONArray()
            for (m in cachedModels) {
                arr.put(JSONObject().apply {
                    put("id", m.id)
                    put("name", m.name)
                    put("promptPrice", m.promptPricePerToken)
                    put("completionPrice", m.completionPricePerToken)
                    put("contextLength", m.contextLength)
                    put("maxCompletion", m.maxCompletionTokens)
                    put("tier", m.tier.name)
                })
            }
            prefs?.edit()
                ?.putString("models", arr.toString())
                ?.putLong("fetchTime", lastFetchTimeMs)
                ?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist models: ${e.message}")
        }
    }

    private fun loadFromDisk() {
        try {
            lastFetchTimeMs = prefs?.getLong("fetchTime", 0L) ?: 0L
            val raw = prefs?.getString("models", null) ?: return
            val arr = JSONArray(raw)
            val models = mutableListOf<ParsedModel>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val tier = try {
                    ModelTier.valueOf(obj.getString("tier"))
                } catch (_: Exception) {
                    continue
                }
                models.add(ParsedModel(
                    id = obj.getString("id"),
                    name = obj.optString("name", obj.getString("id")),
                    promptPricePerToken = obj.getDouble("promptPrice"),
                    completionPricePerToken = obj.getDouble("completionPrice"),
                    contextLength = obj.getInt("contextLength"),
                    maxCompletionTokens = obj.getInt("maxCompletion"),
                    supportsTools = true,
                    tier = tier,
                ))
            }
            cachedModels = models
            if (models.isNotEmpty()) {
                Log.i(TAG, "Loaded ${models.size} models from disk cache")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load models from disk: ${e.message}")
        }
    }

    // ── Utility ────────────────────────────────────────────────────────

    private fun ParsedModel.toRecommended() = RecommendedModel(
        modelId = id,
        displayName = name,
        maxCompletionTokens = maxCompletionTokens,
        promptPricePerM = promptPricePerM,
        completionPricePerM = completionPricePerM,
        tier = tier,
    )
}
